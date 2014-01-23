/*
 *  AuralWorkspaceImpl.scala
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
package impl

import de.sciss.lucre.event.Sys
import de.sciss.lucre.stm.{IdentifierMap, Disposable}

object AuralWorkspaceImpl {
  def apply[S <: Sys[S]](workspace: Workspace[S])(implicit tx: S#Tx): AuralWorkspace[S] = {
    val map = tx.newInMemoryIDMap[AuralSonification[S]]
    val res = new Impl(workspace, map)
    workspace.addDependent(res)
    res
  }

  private final class Impl[S <: Sys[S]](val workspace: Workspace[S],
                                        map: IdentifierMap[S#ID, S#Tx, AuralSonification[S]])
    extends AuralWorkspace[S] with Disposable[S#Tx] {
    impl =>

    def view(sonif: Sonification[S])(implicit tx: S#Tx): AuralSonification[S] =
      map.get(sonif.id).getOrElse {
        val view = AuralSonificationImpl(impl, sonif)
        map.put(sonif.id, view)
        view
      }

    def dispose()(implicit tx: S#Tx): Unit = {
      map.dispose() // XXX TODO: iterate and dispose AuralSonification instances?
    }
  }
}