/*
 *  VarPlayLinear.scala
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

package de.sciss.fscape
package stream

import akka.stream.stage.OutHandler
import akka.stream.{Attributes, SourceShape}
import de.sciss.fscape.stream.impl.{BlockingGraphStage, NodeImpl}
import de.sciss.lucre.matrix.Matrix
import de.sciss.synth.io

object VarPlayLinear {
  def apply(matrix: Matrix.Reader)(implicit b: Builder): OutD = {
    val source  = new Stage(matrix)
    val stage   = b.add(source)
    stage.out
  }

  private final val name = "VarPlayLinear"

  private type Shape = SourceShape[BufD]

  private final class Stage(matrix: Matrix.Reader)(implicit ctrl: Control)
    extends BlockingGraphStage[Shape](s"$name($matrix)") {

    val shape = SourceShape(OutD(s"$name.out"))

    def createLogic(attr: Attributes): NodeImpl[Shape] =
      new Logic(shape, matrix)
  }

  private final class Logic(shape: Shape, matrix: Matrix.Reader)(implicit ctrl: Control)
    extends NodeImpl(s"$name($matrix)", shape) with OutHandler {

    private[this] var af        : io.AudioFile  = _
    private[this] var buf       : io.Frames     = _
    private[this] var bufSize   : Int           = _

    private[this] var framesRead  = 0L

    shape.outlets.foreach(setHandler(_, this))

    override def preStart(): Unit = {
//      logStream(s"preStart() $this")
//      af          = io.AudioFile.openRead(f)
//      if (af.numChannels != numChannels) {
//        Console.err.println(s"Warning: DiskIn - channel mismatch (file has ${af.numChannels}, UGen has $numChannels)")
//      }
//      bufSize     = ctrl.blockSize
//      buf         = af.buffer(bufSize)
      ???
    }

    override protected def stopped(): Unit = {
      logStream(s"postStop() $this")
      buf = null
      //      try {
      af.close()
      //      } catch {
      //        case NonFatal(ex) =>  // XXX TODO -- what with this?
      //      }
    }

    override def onDownstreamFinish(): Unit =
      if (shape.outlets.forall(out => isClosed(out))) {
        logStream(s"completeStage() $this")
        completeStage()
      }

    override def onPull(): Unit =
      ??? // if (numChannels == 1 || shape.outlets.forall(out => isClosed(out) || isAvailable(out))) process()

    private def process(): Unit = {
      val chunk = math.min(bufSize, af.numFrames - framesRead).toInt
      if (chunk == 0) {
        logStream(s"completeStage() $this")
        completeStage()
      } else {
        af.read(buf, 0, chunk)
        framesRead += chunk
        var ch = 0
        ???
//        while (ch < numChannels) {
//          val out = shape.out(ch)
//          if (!isClosed(out)) {
//            val bufOut = ctrl.borrowBufD()
//            val b = bufOut.buf
//            if (ch < buf.length) {
//              val a = buf(ch)
//              var i = 0
//              while (i < chunk) {
//                b(i) = a(i).toDouble
//                i += 1
//              }
//            } else {
//              Util.clear(b, 0, chunk)
//            }
//            bufOut.size = chunk
//            //            println(s"disk   : ${bufOut.hashCode.toHexString} - ${bufOut.buf.toVector.hashCode.toHexString}")
//            push(out, bufOut)
//          }
//          ch += 1
//        }
      }
    }
  }
}