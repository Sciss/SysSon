package at.iem.sysson

import util.{KDTree, AuralApp}
import de.sciss.synth._
import ugen._

object NearestNTest extends AuralApp {
//  val t = KDTree()

  play {
    WhiteNoise.ar(0.2)
  }
}