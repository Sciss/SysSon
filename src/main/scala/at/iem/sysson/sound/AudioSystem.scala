/*
 *  AudioSystem.scala
 *  (SysSon)
 *
 *  Copyright (c) 2013-2014 Institute of Electronic Music and Acoustics, Graz.
 *  Written by Hanns Holger Rutz.
 *
 *	This software is published under the GNU General Public License v2+
 *
 *
 *	For further information, please contact Hanns Holger Rutz at
 *	contact@sciss.de
 */

package at.iem.sysson
package sound

import impl.{AudioSystemImpl => Impl}
import de.sciss.synth
import synth.Server
import de.sciss.osc.TCP
import de.sciss.model.Model

object AudioSystem {
  def instance: AudioSystem = Impl.instance

  def start(config: synth.Server.Config = defaultConfig): AudioSystem = instance.start(config)

  sealed trait Update
  final case class Booting(connection: synth.ServerConnection) extends Update
  final case class Started(server    : synth.Server          ) extends Update
  case object      Stopped                                     extends Update

  type Listener = Model.Listener[Update]

  lazy val defaultConfig = {
    val cfg         = synth.Server.Config()
    cfg.transport   = TCP   // allows larger SynthDefs somehow
    cfg.wireBuffers = 4096  // make it possible to have massively multi-channel buffers
    cfg.pickPort()
    cfg.build
  }
}
trait AudioSystem extends Model[AudioSystem.Update] {
  def server: Option[synth.ServerLike]
  def start(config: synth.Server.Config = AudioSystem.defaultConfig): this.type
  def stop(): this.type

  def isBooting: Boolean
  def isRunning: Boolean

  def whenBooted(fun: Server => Unit): this.type
}