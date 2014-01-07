/*
 *  LazyImpl.scala
 *  (SysSon)
 *
 *  Copyright (c) 2013-2014 Institute of Electronic Music and Acoustics, Graz.
 *  Written by Hanns Holger Rutz.
 *
 *	This software is free software; you can redistribute it and/or
 *	modify it under the terms of the GNU General Public License
 *	as published by the Free Software Foundation; either
 *	version 2, june 1991 of the License, or (at your option) any later version.
 *
 *	This software is distributed in the hope that it will be useful,
 *	but WITHOUT ANY WARRANTY; without even the implied warranty of
 *	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 *	General Public License for more details.
 *
 *	You should have received a copy of the GNU General Public
 *	License (gpl.txt) along with this software; if not, write to the Free Software
 *	Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 *
 *	For further information, please contact Hanns Holger Rutz at
 *	contact@sciss.de
 */

package at.iem.sysson
package graph
package impl

import de.sciss.synth.{UGenGraph, UGenInLike, GE}
import at.iem.sysson.sound.UGenGraphBuilder

trait LazyImpl extends GE.Lazy {
  protected final def makeUGens: UGenInLike =
    UGenGraph.builder match {
      case b: UGenGraphBuilder => makeUGens(b)
      case _ => sys.error("Expansion out of context")
    }

  protected def makeUGens(builder: UGenGraphBuilder): UGenInLike
}
