package at.iem.sysson.turbulence

import at.iem.sysson.turbulence.Dymaxion.{Pt3, Polar, DymPt, MetersPerPixel}
import at.iem.sysson.turbulence.Turbulence.{LatLon, Spk, Radians}
import de.sciss.lucre.synth.{Bus, Group, BusNodeSetter, AudioBus, Node, Escape, Buffer, Synth, Txn, Server}
import de.sciss.{osc, synth, numbers}
import de.sciss.synth.{ControlSet, addToTail, addToHead, addAfter, AddAction, SynthGraph, message}
import de.sciss.file._

import scala.collection.immutable.{IndexedSeq => Vec}

object Binaural {
  final case class Person(pos: DymPt, azi: Radians)

  // - there are 360 / 15 = 24 azimuth   samples
  // - there are  90 / 15 =  6 elevation samples (downwards elevation not needed)
  // - not all elevation samples are taken for any azimuth
  // -

  final case  class IR(t: Int, p: Int) {
    require(t % 15 == 0 && t >= 0 && t < 360)
    require(p % 15 == 0 && ((p >= 0 && p <= 90) || (p >= 315 && p < 360)))

    def toPolar: Polar = LatLon(lat = if (p < 90) p else p - 360, lon = t).toPolar
    def toCartesian: Pt3 = toPolar.toCartesian

    def file(id: Int = 1004): File = Turbulence.audioWork / s"IRC_${id}_C" / f"IRC_${id}_C_R0195_T$t%03d_P$p%03d.wav"
  }

  //  final val Samples = Vector[IR](
  //    IR(000, 000), IR(000, 015), IR(000, 030), IR(000, 045), IR(000, 060), IR(000, 075), IR(000, 090),
  //    IR(015, 000), IR(015, 015), IR(015, 030), IR(015, 045),
  //    IR(030, 000), IR(030, 015), IR(030, 030), IR(030, 045), IR(030, 060),
  //    IR(045, 000), IR(045, 015), IR(045, 030), IR(045, 045),
  //    IR(060, 000), IR(060, 015), IR(060, 030), IR(060, 045), IR(060, 060), IR(060, 075),
  //    IR(075, 000), IR(075, 015), IR(075, 030), IR(075, 045),
  //    IR(090, 000), IR(090, 015), IR(090, 030), IR(090, 045), IR(090, 060),
  //    IR(105, 000), IR(105, 015), IR(105, 030), IR(105, 045),
  //    IR(120, 000), IR(120, 015), IR(120, 030), IR(120, 045), IR(120, 060), IR(120, 075),
  //    ...
  //  )

  final val Samples: Vec[IR] = (0 until 360 by 15).flatMap { t =>
    val pm = if (t % 360 == 0) 90 else if (t % 60 == 0) 75 else if (t % 30 == 0) 60 else 45
    (0 to pm by 15).map { p =>
      IR(t, p)
    }
  }

  final val SamplePoints: Vec[(Pt3, Int)] = Samples.map(_.toCartesian).zipWithIndex

  final case class Position(index: Int, distance: Double) {
    /** Acoustical delay in seconds, based on `distance`
      * and a speed of sound of 342 m/s
      */
    def delay: Double = distance / 342

    /** Amplitude factor (less than 1) in Forum Stadtpark, based on `distance` and
      * a distance of 1.5 meters corresponding to a factor of 1.0
      */
    def attenuation: Double = {
      import numbers.Implicits._
      val d = distance.clip(1.5, 5.8)
      val decibels = 1.1034 * d.squared - 12.6433 * d + 14.3775
      (decibels + 1.2).dbamp
    }
  }

  /** Calculates the closest HRIR sample index and distance of a person with respect
    * to a given speaker
    * 
    * @param listener position and orientation of listener
    * @param spk      speaker index
    * @return a pair of HRIR sample index and distance in meters
    */
  def calc(listener: Person, spk: Spk): Position = {
    val q   = Turbulence.ChannelToMatrixMap(spk).toPoint.equalize
    val p   = listener.pos.equalize
    val azi = p angleTo q
    val dh  = (p distanceTo q) * MetersPerPixel
    val dv  = 1.5 * MetersPerPixel    // ja?
    val dist = math.sqrt(dh * dh + dv * dv)
    val ele = math.atan2(dv, dh)
    val ll  = LatLon(lat = ele.toDegrees, lon = azi.value.toDegrees)
    val r   = ll.toCartesian
    val idx = SamplePoints.minBy(_._1 distanceTo r)._2
    Position(index = idx, distance = dist)
  }

  case class PreparePartConv(sourceBuf: Buffer, fftSize: Int)
    extends message.BufferGen.Command {

    def name: String = "PreparePartConv"

    def isSynchronous: Boolean = true // !

    def args: Seq[Any] = Seq(sourceBuf.id, fftSize)
  }

  private def mkTail(listener: Person, target: Node, addAction: AddAction,
                     delayBus: AudioBus, stereoBus: AudioBus)(implicit tx: Txn): Synth = {
    import Turbulence.{ChannelIndices, Channels, audioWork, NumChannels => N}
    require(delayBus .numChannels == N)
    require(stereoBus.numChannels == 2)

    val irSize    = 80000
    val fftSize   = 2048
    val numPart   = (irSize * 2.0 / fftSize).ceil.toInt  // 49
    val partSize  = fftSize * numPart  // 100352
    val s         = target.server
    val partBufL  = Buffer(s)(numFrames = partSize)
    val partBufR  = Buffer(s)(numFrames = partSize)
    val fullBufL  = Buffer(s)(numFrames = irSize  )
    val fullBufR  = Buffer(s)(numFrames = irSize  )
    fullBufL.read((audioWork / "ForumVerb-L.aif").absolutePath)
    fullBufR.read((audioWork / "ForumVerb-R.aif").absolutePath)
    // currently no predefined method for this command!
    Escape.addMessage(partBufL, message.BufferGen(partBufL.id, PreparePartConv(fullBufL, fftSize)),
      audible = false, dependencies = fullBufL :: Nil)
    Escape.addMessage(partBufR, message.BufferGen(partBufR.id, PreparePartConv(fullBufR, fftSize)),
      audible = false, dependencies = fullBufL :: Nil)
    // fullBufL.dispose()
    // fullBufR.dispose()

    val tailGraph = SynthGraph {
      import synth._
      import ugen._
      val in    = In.ar(ChannelIndices)
      val inF   = Flatten(in)
      val dlyT  = "delay".ir(Vec.fill(N)(0f))
      val amp   = "amp"  .kr(Vec.fill(N)(0f))
      val inA   = DelayN.ar(inF, dlyT, dlyT) * amp
      val mix   = Mix(inA)
      // RunningSum.ar(mix).poll(1, "sum")
      val bufL  = "bufL".ir
      val bufR  = "bufR".ir
      val convL = PartConv.ar(mix, fftSize, bufL)
      val convR = PartConv.ar(mix, fftSize, bufR)
      // RunningSum.ar(convL).poll(1, "conv")
      val outR  = "reverb-out".kr
      val outD  = "delay-out".kr
      // out.poll(0, "out")
      Out.ar(outD, inA)
      Out.ar(outR, Seq(convL, convR))
    }

    val pos     = Channels.map { spk => calc(listener, spk) }
    val dlySet: ControlSet = "delay" -> pos.map(_.delay      .toFloat)
    val attSet: ControlSet = "amp"   -> pos.map(_.attenuation.toFloat)

    val res         = Synth(s, tailGraph, Some("reverb-tail"))
    val reverbBusW  = BusNodeSetter.writer("reverb-out", stereoBus, res)
    val delayBusW   = BusNodeSetter.writer("delay-out" , delayBus , res)
    val args        = dlySet :: attSet :: List[ControlSet]("bufL" -> partBufL.id, "bufR" -> partBufR.id)
    res.play(target = target, args = args,
      addAction = addAction, dependencies = partBufL :: partBufR :: Nil)
    reverbBusW.add()
    delayBusW .add()
    res.onEndTxn { implicit tx =>
      reverbBusW.remove()
      delayBusW .remove()
      fullBufL  .dispose()
      fullBufR  .dispose()
      partBufL  .dispose()
      partBufR  .dispose()
    }
    res
  }

  def build(s: Server, listener: Person)(implicit tx: Txn): Group = {
    import Turbulence.{NumChannels => N}
    val g         = Group(s.defaultGroup, addAfter)
    val stereoBus = Bus.audio(s, 2)
    val delayBus  = Bus.audio(s, N)
    mkTail(listener, g, addToHead, delayBus = delayBus, stereoBus = stereoBus)

    val rplcGraph = SynthGraph {
      import synth._
      import ugen._
      val in  = "in".kr
      val sig = In.ar(in, 2)
      Mix(sig).poll(1, "route")
      ReplaceOut.ar(0, sig)
    }

    val rplcSynth = Synth(s, rplcGraph, Some("binaural-mix"))
    rplcSynth.play(g, Nil, addToTail, Nil)
    val busR = BusNodeSetter.reader("in", stereoBus, rplcSynth)
    busR.add()
    rplcSynth.onEndTxn { implicit tx =>
      busR.remove()
    }

    var binBufs = Map.empty[Int, (Buffer, Buffer)]

    //    Turbulence.Channels.map { spk =>
    //      val pos = calc(listener, spk)
    //      val useConv = pos.distance < 6
    //      val chanGraph = SynthGraph {
    //        import synth._
    //        import ugen._
    //        val in      = "in".kr
    //        val inSig   = In.ar(in)
    //        val dly     = "delay".ir
    //        val dlySig  = DelayN.ar(inSig, dly, dly)
    //        val amp     = "amp".kr
    //        val ampSig  = dlySig * amp
    //
    //        val outSig: GE = if (!useConv) Seq(ampSig, ampSig) else {
    //          val bufL  = "bufL".ir
    //          val bufR  = "bufR".ir
    //          val convL = Convolution2.ar(ampSig, bufL, frameSize = 512)
    //          val convR = Convolution2.ar(ampSig, bufR, frameSize = 512)
    //          Seq(convL, convR)
    //        }
    //
    //        val out   = "out".kr
    //        Out.ar(out, outSig)
    //      }
    //
    //      val chanSynth = Synth(s, chanGraph, Some(if (useConv) "model-chan-bin" else "model-chan"))
    //      if (useConv) {
    //        binBufs.getOrElse(pos.index, {
    //          val bufL  = Buffer(s)(numFrames = 512)
    //          val ir    = Samples(pos.index)
    //          bufL.read(ir.file.absolutePath)
    //
    //        })
    //      }
    //    }

    g
  }
}
