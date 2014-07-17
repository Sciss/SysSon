/*
 *  UserValue.scala
 *  (SysSon)
 *
 *  Copyright (c) 2013-2014 Institute of Electronic Music and Acoustics, Graz.
 *  Written by Hanns Holger Rutz.
 *
 *	This software is published under the GNU General Public License v3+
 *
 *
 *	For further information, please contact Hanns Holger Rutz at
 *	contact@sciss.de
 */

package at.iem.sysson.graph

import de.sciss.synth.proc.UGenGraphBuilder
import de.sciss.synth.{proc, Rate, scalar, GE, UGenInLike}
import de.sciss.synth

object UserValue {
  case class GE(rate: Rate, peer: UserValue) extends synth.GE.Lazy with SonificationElement {
    override def productPrefix = "UserValue$GE"

    protected def makeUGens: UGenInLike = {
      val b = UGenGraphBuilder.get
      // re-write as a proc attribute
      val key: String = ??? // = AuralSonificationOLD.current().attributeKey(peer)
      proc.graph.attribute(key).ir(peer.default)
    }
  }
}
case class UserValue(key: String, default: Double) extends UserInteraction {
  def ir   : GE = UserValue.GE(scalar , this)
  def value: GE = ir
}