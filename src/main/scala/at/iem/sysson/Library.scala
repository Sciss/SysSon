/*
 *  Library.scala
 *  (SysSon)
 *
 *  Copyright (c) 2013 Institute of Electronic Music and Acoustics, Graz.
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

import de.sciss.lucre.event.Sys
import de.sciss.lucre.expr.Expr
import impl.{LibraryImpl => Impl}
import de.sciss.lucre.stm
import de.sciss.model.Change
import de.sciss.serial.Writable
import de.sciss.serial
import de.sciss.lucre.stm.Identifiable

object Library {
  def apply[S <: Sys[S]](implicit tx: S#Tx): Library[S] = Impl[S]

  sealed trait NodeLike[S <: Sys[S]] extends stm.Mutable[S#ID, S#Tx] {
    def name: Expr[S, String]
  }

  sealed trait LeafChange
  case class Renamed      (change: Change[String]) extends LeafChange // with BranchChange
  case class SourceChanged(change: Change[String]) extends LeafChange

  type LeafUpdate = Vec[LeafChange]

  type Update       [S <: Sys[S]] = TreeLike.Update      [S, Library[S]]
  type BranchChange [S <: Sys[S]] = TreeLike.BranchChange[S, Library[S]]
  type LeafChanged  [S <: Sys[S]] = TreeLike.LeafChanged [S, Library[S]]
  type BranchUpdate [S <: Sys[S]] = TreeLike.BranchUpdate[S, Library[S]]

  trait Branch[S <: Sys[S]] extends TreeLike.Branch[S, Library[S]] with NodeLike[S] {
    def insertLeaf  (idx: Int, name: Expr[S, String], source: Expr[S, String])(implicit tx: S#Tx): Leaf[S]
    def insertBranch(idx: Int, name: Expr[S, String])(implicit tx: S#Tx): Branch[S]
  }
  trait Leaf[S <: Sys[S]] extends NodeLike[S] {
    def name  : Expr.Var[S, String]
    def source: Expr.Var[S, String]
  }

  implicit def serializer[S <: Sys[S]]: serial.Serializer[S#Tx, S#Acc, Library[S]] = Impl.serializer[S]
}
trait Library[S <: Sys[S]]
  extends TreeLike[S, Library[S]] with stm.Mutable[S#ID, S#Tx]
  with Writable {

  type BU     = Library.Renamed
  type LU     = Library.LeafUpdate
  type Leaf   = Library.Leaf  [S]
  type Branch = Library.Branch[S]
}
