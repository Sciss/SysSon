/*
 *  NetCdfFileUtil.scala
 *  (SysSon)
 *
 *  Copyright (c) 2013-2016 Institute of Electronic Music and Acoustics, Graz.
 *  Copyright (c) 2014-2016 Hanns Holger Rutz. All rights reserved.
 *
 *	This software is published under the GNU General Public License v3+
 *
 *
 *	For further information, please contact Hanns Holger Rutz at
 *	contact@sciss.de
 */

package at.iem.sysson
package util

import de.sciss.equal
import de.sciss.file._
import de.sciss.kollflitz
import de.sciss.processor.Processor
import de.sciss.processor.impl.ProcessorImpl
import ucar.nc2.constants.CDM
import ucar.{ma2, nc2}

import scala.collection.JavaConverters._
import scala.collection.breakOut
import scala.collection.immutable.{IndexedSeq => Vec}
import scala.concurrent.{Await, ExecutionContext, blocking}
import scala.concurrent.duration.Duration
import scala.language.implicitConversions

object NetCdfFileUtil {
  object OutDim {
    implicit def byName(name: String): OutDim = Keep(name)
  }
  sealed trait OutDim { def isCopy: Boolean; def name: String }

  /** The dimension is copied from input and will be
    * traversed as indices during transformation.
    */
  final case class Keep(name: String) extends OutDim { def isCopy = true }

  object Create { def apply(name: String, units: Option[String], values: ma2.Array): Create =
    new Create(name, units, values)
  }
  /** The dimension is created anew during transformation.
    */
  final class Create(val name: String, val units: Option[String], val values: ma2.Array)
    extends OutDim { def isCopy = false }

  /** The dimension is copied from input, but will not be indexed
    * during traversal but instead the transformation function
    * takes and outputs that dimension.
    */
  final case class Recreate(name: String) extends OutDim { def isCopy = false }


  // cf. http://www.unidata.ucar.edu/software/thredds/current/netcdf-java/tutorial/NetcdfWriting.html

  /** Transforms an input NetCDF file into an output NetCDF file, by copying a given
    * variable and applying an optional transform to the data.
    *
    * '''Note:''' This is not optimized for speed, yet.
    *
    * @param in         the input file to transform
    * @param out        the file to which the output will be written
    * @param varName    the variable to copy/transform
    * @param inDims     the dimensions of the variable to transform. these will be removed
    *                   from the target variable
    * @param outDimsSpec the dimensions of the output variable. each spec can either
    *                   indicate a verbatim copy (`Keep`) or the result of the transformation (`Create`).
    *                   If the type is `Recreate`, the dimension appears as part of the array shape
    *                   given to the function and must be output as such.
    * @param fun        a function that will transform the variable's data matrix. It is passed the origin
    *                   in the kept dimensions (origin of the output shape minus the created dimensions)
    *                   and an object
    *                   of dimension `inDims.size` and is required to output an object of
    *                   dimension `outDims.filterNot(_.isCopy).size`. The dimensions are sorted to
    *                   correspond with `inDims`. The function is called repeatedly, iterating
    *                   over all other input dimensions except those in `inDims`.
    */
  def transform(in: nc2.NetcdfFile, out: File, varName: String, inDims: Vec[String], outDimsSpec: Vec[OutDim])
               (fun: (Vec[Int], ma2.Array) => ma2.Array): Processor[Unit] with Processor.Prepared =
    new ProcessorImpl[Unit, Processor[Unit]] with Processor[Unit] {
      protected def body(): Unit = blocking {
        transformBody(this, in = in, out = out, varName = varName, inDims = inDims, outDimsSpec = outDimsSpec,
          fun = fun)
      }

      override def toString = s"$varName-transform"
    }

  private def transformBody(self: ProcessorImpl[Unit, Processor[Unit]],
                            in: nc2.NetcdfFile, out: File, varName: String, inDims: Vec[String],
                            outDimsSpec: Vec[OutDim], fun: (Vec[Int], ma2.Array) => ma2.Array): Unit = {
    val location  = out.path
    val writer    = nc2.NetcdfFileWriter.createNew(nc2.NetcdfFileWriter.Version.netcdf3, location, null)

    import Implicits._

    val inVar     = in.variableMap(varName)
    val allInDims = inVar.dimensions
    val (alterInDims, keepInDims) = allInDims.partition(d => inDims.contains(d.name))

    val (keepOutDims, keepOutDimsV) = keepInDims.map { inDim =>
      val outDim  = writer.addDimension(null, inDim.name, inDim.size)
      val inVar   = in.variableMap(inDim.name)
      val outVarD = dupVar(writer, inVar, Seq(outDim))
      (outDim, outVarD)
    } .unzip

    val (alterOutDims: Vec[nc2.Dimension], alterOutDimsD: Vec[ma2.Array], alterOutDimsV: Vec[nc2.Variable]) =
      outDimsSpec.collect {
        case c: Create =>
          val outDim  = writer.addDimension(null, c.name, c.values.size.toInt)
          val dt      = ma2.DataType.getType(c.values.getElementType)
          val outVarD = writer.addVariable(null, c.name, dt, Seq(outDim).asJava)
          c.units.foreach(units => outVarD.addAttribute(new nc2.Attribute(CDM.UNITS, units)))
          (outDim, c.values, outVarD)
        case r: Recreate =>
          val inDim   = alterInDims.find(_.name == r.name).get
          val inVar   = in.variableMap(r.name)
          val dimVals = inVar.read()
          val outDim  = writer.addDimension(null, r.name, inDim.size)
          val outVarD = dupVar(writer, inVar, Seq(outDim))
          (outDim, dimVals, outVarD)

      } .unzip3

    import equal.Implicits._
    val outDims = outDimsSpec.map {
      case Keep(name) => keepOutDims .find(_.name ===   name).getOrElse(sys.error(s"No dimension '$name'"))
      case c          => alterOutDims.find(_.name === c.name).getOrElse(sys.error(s"No dimension '${c.name}'"))
    }

    val outVar = writer.addVariable(null, inVar.getShortName, inVar.getDataType, outDims.asJava)

    // create the file; ends "define mode"
    writer.create()

    (keepInDims zip keepOutDimsV).foreach { case (inDim, outDimV) =>
      val inVar   = in.variableMap(inDim.name)
      val dimData = inVar.read()
      writer.write(outDimV, dimData)
    }

    (alterOutDimsD zip alterOutDimsV).foreach { case (dimData, outVarD) =>
      writer.write(outVarD, dimData)
    }

    val permutations: Array[Int] = inDims.map(name => alterInDims.indexWhere(_.name === name))(breakOut)

    val expectedRank  = alterOutDimsD.size
    val expectedSize  = alterOutDimsD.map(_.size).product
    val writeShape: Array[Int] = outDimsSpec.map {
      case Keep(_)      => 1
      case c: Create    => c.values.size.toInt
      case r: Recreate  => allInDims.find(_.name == r.name).get.size
    } (breakOut)

    def countSteps(rem: Vec[OutDim], sum: Int): Int = rem match {
      case head +: tail =>
        head match {
          case Keep(name) =>
            val dim = keepInDims.find(_.name === name).get
            var sumOut = sum
            for (i <- 0 until dim.size) {
              sumOut = countSteps(tail, sumOut)
            }
            sumOut

          case _ =>
            countSteps(tail, sum)
        }

      case _ => sum + 1
    }

    val numSteps = countSteps(outDimsSpec, 0)

    def iter(sec: VariableSection, origin: Vec[Int], rem: Vec[OutDim], steps: Int): Int = rem match {
      case head +: tail =>
        head match {
          case Keep(name) =>
            val dim = keepInDims.find(_.name === name).get
            var stepsOut = steps
            for (i <- 0 until dim.size) {
              val sec1 = (sec in name).select(i)
              stepsOut = iter(sec1, origin :+ i, tail, steps = stepsOut)
            }
            stepsOut
          case _ =>
            iter(sec, origin :+ 0, tail, steps = steps)
        }

      case _ =>
        // `dataInF` has the original rank and dimensional order
        val dataInF = sec.readSafe()
        // `dataInR` removes the dimensions not included in `inDims`
        val dataInR = (dataInF /: allInDims.zipWithIndex.reverse) { case (a, (dim, idx)) =>
          if (inDims.contains(dim.name)) a else {
            // println(s"Reducing $idx; a.rank = ${a.rank}")
            a.reduce(idx)
          }
        }
        // `dataIn` transposes the dimensions to reflect the order in `inDims`
        val dataIn  = dataInR.permute(permutations)
        // println(s"Shape = ${dataIn.shape}")
        // assert(dataInF.getRank == inDims.size, s"Array has rank ${dataInF.getRank} while expecting ${inDims.size}")
        val originF = (origin zip outDimsSpec).collect {
          case (i, Keep(_)) => i
        }
        val dataOut = fun(originF, dataIn) // fun(dataIn)

        require(dataOut.getRank == expectedRank,
          s"Transformation expected to output rank $expectedRank (observed: ${dataOut.getRank})")
        require(dataOut.size == expectedSize,
          s"Transformation expected to produce $expectedSize values (observed: ${dataOut.size})")

        // println(s"Origin: $origin")
        val dataOutR = dataOut.reshapeNoCopy(writeShape)
        writer.write(outVar, origin.toArray, dataOutR)

        val stepsOut = steps + 1
        self.progress = stepsOut.toDouble / numSteps
        self.checkAborted()
        stepsOut
    }

    iter(inVar.selectAll, Vector.empty, outDimsSpec, steps = 0)

    writer.close()
  }

  def concatAndWait(in1: nc2.NetcdfFile, in2: nc2.NetcdfFile, out: File, varName: String, dimName: String = "time"): Unit = {
    val proc = concat(in1 = in1, in2 = in2, out = out, varName = varName, dimName = dimName)
    import ExecutionContext.Implicits.global
    proc.start()
    Await.result(proc, Duration.Inf)
  }

  /** Creates a new NetCDF file that contains one variable resulting from the concatenation
    * of that variable present in two input files.
    *
    * @param in1      the first input file (data will appear first)
    * @param in2      the second input file (data will appear second)
    * @param out      the output file to write to
    * @param varName  the name of the variable to take from the inputs and concatenate
    * @param dimName  the dimension along which to the variable is split across the two inputs
    */
  def concat(in1: nc2.NetcdfFile, in2: nc2.NetcdfFile, out: File, varName: String,
             dimName: String = "time"): Processor[Unit] with Processor.Prepared =
    new ProcessorImpl[Unit, Processor[Unit]] with Processor[Unit] {
      protected def body(): Unit = blocking {
        concatBody(this, in1 = in1, in2 = in2, out = out, varName = varName, dimName = dimName)
      }

      override def toString = s"$varName-concat"
    }

  private def concatBody(self: ProcessorImpl[Unit, Processor[Unit]], in1: nc2.NetcdfFile, in2: nc2.NetcdfFile,
                         out: File, varName: String, dimName: String): Unit = {
    val location  = out.path
    val writer    = nc2.NetcdfFileWriter.createNew(nc2.NetcdfFileWriter.Version.netcdf3, location, null)

    import Implicits._

    val inVar1    = in1.variableMap(varName)
    val inVar2    = in2.variableMap(varName)

    val allInDims = inVar1.dimensions

    import equal.Implicits._
    val (outDims, keepOutDimsV) = allInDims.map { inDim =>
      val size    = if (inDim.name === dimName) inDim.size + inVar2.dimensionMap(inDim.name).size else inDim.size
      val outDim  = writer.addDimension(null, inDim.name, size)
      val inVar   = in1.variableMap(inDim.name)
      val outVarD = writer.addVariable(null, inVar.getShortName, inVar.dataType, Seq(outDim).asJava)
      inVar.units.foreach(units => outVarD.addAttribute(new nc2.Attribute(CDM.UNITS, units)))
      (outDim, outVarD)
    } .unzip

    val outVar = writer.addVariable(null, inVar1.getShortName, inVar1.getDataType, outDims.asJava)
    inVar1.units.foreach(units => outVar.addAttribute(new nc2.Attribute(CDM.UNITS, units)))

    // create the file; ends "define mode"
    writer.create()

    (allInDims zip keepOutDimsV).zipWithIndex.foreach { case ((inDim, outDimV), dimIdx0) =>
      val inVar1    = in1.variableMap(inDim.name)
      val dimData1  = inVar1.read()
      writer.write(outDimV, dimData1)
      if (inDim.name === dimName) {
        val inVar2      = in2.variableMap(inDim.name)
        val dimData2    = inVar2.read()
        val origin      = new Array[Int](dimData2.rank)
        origin(dimIdx0) = dimData1.shape(dimIdx0)
        writer.write(outDimV, origin, dimData2)
      }
    }

    val dim1    = inVar1.dimensionMap(dimName)
    val dim2    = inVar2.dimensionMap(dimName)
    val dimIdx  = inVar1.dimensions.indexWhere(_.name === dimName)
    val dim1Sz  = dim1.size
    val dim2Sz  = dim2.size

    val totalSz = dim1Sz + dim2Sz

    for (i <- 0 until dim1Sz) {
      val sec     = (inVar1 in dimName) select i
      val d       = sec.read()
      val origin  = new Array[Int](inVar1.rank)
      origin(dimIdx) = i
      writer.write(outVar, origin, d)

      self.progress = (i + 1).toDouble / totalSz
      self.checkAborted()
    }

    for (i <- 0 until dim2Sz) {
      val sec     = (inVar2 in dimName) select i
      val d0      = sec.read()
      val d       = d0.copy()
      val origin  = new Array[Int](inVar2.rank)
      origin(dimIdx) = i + dim1.size
      writer.write(outVar, origin, d)

      self.progress = (i + 1 + dim1Sz).toDouble / totalSz
      self.checkAborted()
    }

    writer.close()
  }

  private def checkNaNFun(fillValue: Float): Float => Boolean = if (java.lang.Float.isNaN(fillValue))
    java.lang.Float.isNaN
  else
    _ == fillValue

  /** Calculates anomalies of a time series. It assumes that the time resolution
    * in the input is months!
    * The output will have a matrix of the same size as the input, where each
    * cell is the difference between the input cell and the normal value for that
    * cell at that time.
    *
    * @param in         the input  file to process
    * @param out        the output file to create
    * @param varName    the variable to process
    * @param timeName   the time dimension in the variable
    * @param windowYears the number of years to average across
    */
  def anomalies(in: nc2.NetcdfFile, out: File, varName: String, timeName: String = "time",
                windowYears: Int = 30, useMedian: Boolean = false): Processor[Unit] with Processor.Prepared =
    new ProcessorImpl[Unit, Processor[Unit]] with Processor[Unit] {
      protected def body(): Unit = blocking {
        anomaliesBody(this, in = in, out = out, varName = varName, timeName = timeName,
          windowYears = windowYears, useMedian = useMedian)
      }

      override def toString = s"$varName-anomalies"
    }

  private def copyAttr(in: nc2.Variable, out: nc2.Variable): Unit = {
    import Implicits._
    in.units      .foreach(units => out.addAttribute(new nc2.Attribute(CDM.UNITS      , units)))
    in.description.foreach(desc  => out.addAttribute(new nc2.Attribute(CDM.DESCRIPTION, desc )))
  }

  private def dupVar(writer: nc2.NetcdfFileWriter, in: nc2.Variable, dims: Seq[nc2.Dimension]): nc2.Variable = {
    import Implicits._
    val out = writer.addVariable(null, in.getShortName, in.dataType, dims.asJava)
    copyAttr(in = in, out = out)
    out
  }

  private def anomaliesBody(self: ProcessorImpl[Unit, Processor[Unit]], in: nc2.NetcdfFile, out: File, varName: String,
                            timeName: String, windowYears: Int, useMedian: Boolean): Unit = {
    import Implicits.{checkNaNFun => _, _}  // must shadow on Scala 2.10

    val inVar         = in.variableMap(varName)
    val inDims        = inVar.dimensions
    import equal.Implicits._
    val dimTimeIdx    = inDims  .indexWhere(_.name === timeName)
    // val otherDims     = inDims  .patch(timeIdx, Nil, 1)
    // val otherShape    = inVar.shape.patch(timeIdx, Nil, 1)
    val numTime       = inDims(dimTimeIdx).size
    val otherSize     = (inVar.size / numTime).toInt
    val windowYearsH  = windowYears/2
    val windowMonthsH = windowYearsH * 12

    val isNaN = checkNaNFun(inVar.fillValue.toFloat)

    class NormData(val timeRange: Range, val norm: Array[Double])

    val normData = new Array[NormData](12)

    def calcNorm(data: ma2.Array, timeOff: Int): ma2.Array = {
      val month     = timeOff % 12
      val timeLo0   = timeOff - windowMonthsH
      val timeLo    = if (timeLo0 >= 0) timeLo0 else month
      val timeHi0   = timeOff + windowMonthsH
      val timeHi    = if (timeHi0 < numTime) timeHi0 else {
        val tmp = (numTime - numTime % 12) + month
        if (tmp < numTime) tmp + 12 else tmp
      }
      val timeRange = timeLo until timeHi by 12

      if (normData(month) == null || normData(month).timeRange != timeRange) {
        val norm = new Array[Double](otherSize)

        if (useMedian) {
          def loop(sec: VariableSection, rem: Vec[nc2.Dimension], idx: Int): Int = rem match {
            case dim +: tail =>
              (idx /: (0 until dim.size)) { case (idx1, j) =>
                val idx2 = loop(sec.in(dim.name).select(j), tail, idx1)
                idx2
              }
            case _ =>
              val sel = sec.in(timeName).select(timeRange)
              val arr = sel.readSafe().float1D
              assert(arr.size == timeRange.size)
              import kollflitz.Ops._
              val m = arr.sortedT.median
              norm(idx) = m
              idx + 1
          }

          val otherDims = inDims.patch(dimTimeIdx, Nil, 1)
          loop(inVar.selectAll, otherDims, idx = 0)

        } else {
          val count = new Array[Int](otherSize)
          timeRange.foreach { time =>
            val sel       = inVar in timeName select time
            val arrMonth  = sel.readSafe().float1D
            arrMonth.zipWithIndex.foreach { case (v, i) =>
              if (!isNaN(v)) {
                norm (i) += v
                count(i) += 1
              }
            }
          }
          for (i <- 0 until otherSize) {
            if (count(i) > 0) norm(i) /= count(i)
          }
        }

        normData(month) = new NormData(timeRange, norm)
      }

      assert(data.size == otherSize)
      val dataDif = data.copy()

      val norm = normData(month).norm
      for (i <- 0 until otherSize) {
        dataDif.setFloat(i, dataDif.getFloat(i) - norm(i).toFloat)
      }

      dataDif
    }

    val location  = out.path
    val writer    = nc2.NetcdfFileWriter.createNew(nc2.NetcdfFileWriter.Version.netcdf3, location, null)
    val (outDims, outDimsV) = inDims.map { inDim =>
      val size    = inDim.size
      val outDim  = writer.addDimension(null, inDim.name, size)
      val inVar   = in.variableMap(inDim.name)
      val outVarD = dupVar(writer, inVar, Seq(outDim))
      (outDim, outVarD)
    } .unzip

    val outVar = dupVar(writer, inVar, outDims)

    // create the file; ends "define mode"
    writer.create()

    // copy dimension data
    (inDims zip outDimsV).zipWithIndex.foreach { case ((inDim, outDimV), dimIdx0) =>
      val inVar1    = in.variableMap(inDim.name)
      val dimData1  = inVar1.read()
      writer.write(outDimV, dimData1)
    }

    for (time <- 0 until numTime) {
      val sec     = (inVar in timeName) select time
      val dataIn  = sec.readSafe()
      val origin  = new Array[Int](inVar.rank)
      origin(dimTimeIdx) = time
      val dataOut = calcNorm(data = dataIn, timeOff = time)
      writer.write(outVar, origin, dataOut)

      self.progress = (time + 1).toDouble / numTime
      self.checkAborted()
    }

    writer.close()
  }
}