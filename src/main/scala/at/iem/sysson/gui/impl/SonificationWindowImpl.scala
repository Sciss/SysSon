/*
 *  SonificationWindowImpl.scala
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
package gui
package impl

import de.sciss.lucre.event.Sys
import at.iem.sysson.sound.Sonification

object SonificationWindowImpl {
  def apply[S <: Sys[S]](workspace: Workspace[S], sonification: Sonification[S])
                        (implicit tx: S#Tx): SonificationWindow[S] = {
    val view  = SonificationView(workspace, sonification)
    val res   = new Impl(view)
    res.init()
    res
  }

  private final class Impl[S <: Sys[S]](val view: SonificationView[S])
    extends WindowImpl[S](title0 = "Sonification Editor")
    with SonificationWindow[S] {
  }
}
