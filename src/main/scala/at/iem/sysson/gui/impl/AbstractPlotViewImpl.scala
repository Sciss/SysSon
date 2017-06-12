/*
 *  AbstractPlotViewImpl.scala
 *  (SysSon)
 *
 *  Copyright (c) 2013-2017 Institute of Electronic Music and Acoustics, Graz.
 *  Copyright (c) 2014-2017 Hanns Holger Rutz. All rights reserved.
 *
 *	This software is published under the GNU General Public License v3+
 *
 *
 *	For further information, please contact Hanns Holger Rutz at
 *	contact@sciss.de
 */

package at.iem.sysson
package gui
package impl

import de.sciss.equal
import de.sciss.lucre.expr.StringObj
import de.sciss.lucre.matrix.{DataSource, Matrix}
import de.sciss.lucre.stm.{Disposable, Sys}
import de.sciss.lucre.swing.impl.ComponentHolder
import de.sciss.lucre.swing.{defer, deferTx}
import de.sciss.lucre.{stm, event => evt}
import de.sciss.mellite.gui.ViewHasWorkspace
import de.sciss.processor.Processor
import de.sciss.processor.impl.ProcessorImpl
import de.sciss.synth.proc.{GenContext, SoundProcesses}

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.concurrent.stm.{Ref, TMap}
import scala.swing.Component
import scala.util.{Failure, Success}

object AbstractPlotViewImpl {
  private final val DEBUG = false

  final class PlotData(val hName: String, val mUnits: String, val hData: Array[Float],
                       val vName: String, val hUnits: String, val vData: Array[Float],
                       val mName: String, val vUnits: String, val mData: Array[Array[Float]])

  private final class Reader(mName: String, mUnits: String, mReaderF: Future[Matrix.Reader],
                             hName: String, hUnits: String, hReaderF: Future[Matrix.Reader],
                             vName: String, vUnits: String, vReaderF: Future[Matrix.Reader])
    extends ProcessorImpl[PlotData, Reader] {

    private def readDim(r: Matrix.Reader): Array[Float] = {
      assert(r.numChannels == 1)
      val sz    = r.numFrames.toInt
      val res   = new Array[Float](sz)
      r.readFloat2D(Array(res), 0, sz)
      checkAborted()
      res
    }

    protected def body(): PlotData = {
      // XXX TODO --- await is not good; should be able to flat-map processes
      val hReader = Await.result(hReaderF, Duration.Inf)
      val vReader = Await.result(vReaderF, Duration.Inf)
      val mReader = Await.result(mReaderF, Duration.Inf)

      val hData = readDim(hReader)
      val vData = readDim(vReader)

      val mCh   = mReader.numChannels
      val mSz   = mReader.numFrames.toInt
      val mData = Array.ofDim[Float](mCh, mSz)
      mReader.readFloat2D(mData, 0, mSz)
      checkAborted()

      new PlotData(
        hName = hName, hUnits = hUnits, hData = hData,
        vName = vName, vUnits = vUnits, vData = vData,
        mName = mName, mUnits = mUnits, mData = mData)
    }
  }
}
trait AbstractPlotViewImpl[S <: Sys[S]] extends ViewHasWorkspace[S] with ComponentHolder[Component] {
  import AbstractPlotViewImpl._

  // ---- abstract ----

  // called on EDT
  protected def updatePlot(data: PlotData): Unit

  // ---- impl ----

  implicit private[this] val resolver: DataSource.Resolver[S] = WorkspaceResolver[S]

  // checks if the shape is reducible in all but the provided dimensions
  private def checkShape1D(shape: Vec[Int], hIdx: Int, vIdx: Int): Boolean =
    shape.zipWithIndex.forall { case (n, i) => n == 1 || i == hIdx || i == vIdx }

  private[this] val readerRef = Ref(new Reader(null, null, null, null, null, null, null, null, null))

  private def updateData(plot: Plot[S])(implicit tx: S#Tx): Unit = {
    val m       = plot.matrix
    val dimMap  = plot.dims
    val dims    = m.dimensions
    val hName   = dimMap.get(Plot.HKey).map(_.value).getOrElse("?")
    val vName   = dimMap.get(Plot.VKey).map(_.value).getOrElse("?")
    import equal.Implicits._
    val hIdx    = dims.indexWhere(_.name === hName)
    val vIdx    = dims.indexWhere(_.name === vName)
    val mShape  = m.shape
    val shapeOk = checkShape1D(mShape, hIdx, vIdx)

    if (DEBUG) println(s"updateData. hIdx = $hIdx, vIdx = $vIdx, ok? $shapeOk")

    if (hIdx >= 0 && vIdx >= 0 && shapeOk) {
      //        println(s"h-unit: ${dims(hIdx).units}")
      //        println(s"v-unit: ${dims(vIdx).units}")

      import scala.concurrent.ExecutionContext.Implicits.global

      // this locks:
//      import SoundProcesses.executionContext
      implicit val context = GenContext[S]

      val hKey    = m.prepareDimensionReader(hIdx, useChannels = false)
      val vKey    = m.prepareDimensionReader(vIdx, useChannels = false)
      val hReader = hKey.reader()
      val vReader = vKey.reader()
      val mName   = m.name  // or plot-obj attr name?
      val mReader = m.reader(streamDim = hIdx)  // rows = channels, columns = frames
      val hDim    = dims(hIdx)
      val vDim    = dims(vIdx)
      val mUnits  = m   .units
      val hUnits  = hDim.units
      val vUnits  = vDim.units
      val proc    = new Reader(mName = mName, mUnits = mUnits, mReaderF = mReader,
        hName = hName, hUnits = hUnits, hReaderF = hReader, vName = vName, vUnits = vUnits,
        vReaderF = vReader)
      val oldProc = readerRef.swap(proc)(tx.peer)
      tx.afterCommit {
        oldProc.abort()
        if (!proc.aborted) {
          proc.start()
          proc.onComplete {
            case Success(plotData) =>
              defer {
                if (DEBUG) println(s"X-Axis: ${plotData.hName}; ${plotData.hData.take(144).mkString(",")}")
                if (DEBUG) println(s"Y-Axis: ${plotData.vName}; ${plotData.vData.take(144).mkString(",")}")
                updatePlot(plotData)
              }

            case Failure(Processor.Aborted()) =>
            case Failure(ex) =>
              Console.err.println("Matrix reader failed:")
              ex.printStackTrace()
          }
        }
      }
    }
  }

  // ----

  private[this] final var _observers = List.empty[Disposable[S#Tx]]

  protected final def addObserver(obs: Disposable[S#Tx]): Unit = _observers ::= obs

  private[this] var  _plotH: stm.Source[S#Tx, Plot[S]] = _

  protected final def plotH: stm.Source[S#Tx, Plot[S]] = _plotH

  def init(plot: Plot[S])(implicit tx: S#Tx): this.type = {
    _plotH = tx.newHandle(plot)

//    implicit val booleanEx  = BooleanObj
//    implicit val stringEx   = StringObj
//    implicit val doubleEx   = DoubleObj

    updateData(plot)  // XXX TODO --- might this be too early for sub-classes?

    _observers ::= plot.changed.react { implicit tx => u =>
      if (DEBUG) println("plot changed")
      updateData(u.plot)
      u.changes.foreach {
        case Plot.DimsChange(mu) => mu.changes.foreach {
          case evt.Map.Added   (key, value) => addPlotDim   (key, value)
          case evt.Map.Removed (key, value) => removePlotDim(key, value)
          case evt.Map.Replaced(key, before, now) =>
            removePlotDim(key, before)
            addPlotDim   (key, now   )
        }
        case _ =>
      }
    }

    plot.dims.iterator.foreach { case (key, value) => addPlotDim(key, value) }

    this
  }

  private[this] val plotDimObs = TMap.empty[String, Disposable[S#Tx]]

  private def addPlotDim(key: String, value: StringObj[S])(implicit tx: S#Tx): Unit = {
    implicit val ptx = tx.peer
    val obs = value.changed.react { implicit tx => _ =>
      updateData(_plotH())
    }
    plotDimObs.put(key, obs)
  }

  private def removePlotDim(key: String, value: StringObj[S])(implicit tx: S#Tx): Unit = {
    implicit val ptx = tx.peer
    plotDimObs.remove(key).foreach(_.dispose())
  }

  def dispose()(implicit tx: S#Tx): Unit = {
    implicit val ptx = tx.peer
    _observers.foreach(_.dispose())
    val p = readerRef.get(tx.peer)
    plotDimObs.foreach(_._2.dispose())
    plotDimObs.clear()
    deferTx {
      p.abort()
    }
  }
}