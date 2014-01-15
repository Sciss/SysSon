/*
 *  DocumentHandler.scala
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

import impl.{DocumentHandlerImpl => Impl}
import de.sciss.model.Model
import de.sciss.lucre.{event => evt}
import language.existentials
import evt.Sys
import de.sciss.file.File

object DocumentHandler {
  type Document = Workspace[_ <: Sys[_]]

  lazy val instance: DocumentHandler = Impl()

  sealed trait Update
  final case class Opened[S <: Sys[S]](doc: Workspace[S]) extends Update
  final case class Closed[S <: Sys[S]](doc: Workspace[S]) extends Update
}
trait DocumentHandler extends Model[DocumentHandler.Update] {
  import DocumentHandler.Document

  private[sysson] def addDocument[S <: Sys[S]](doc: Workspace[S])(implicit tx: S#Tx): Unit

  // def openRead(path: String): Document
  def allDocuments: Iterator[Document]
  def getDocument(folder: File): Option[Document]

  def isEmpty: Boolean
}