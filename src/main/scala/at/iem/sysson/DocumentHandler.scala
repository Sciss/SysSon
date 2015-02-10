/*
 *  DocumentHandler.scala
 *  (SysSon)
 *
 *  Copyright (c) 2013-2015 Institute of Electronic Music and Acoustics, Graz.
 *  Copyright (c) 2014-2015 Hanns Holger Rutz. All rights reserved.
 *
 *	This software is published under the GNU General Public License v3+
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
import de.sciss.mellite.Workspace

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