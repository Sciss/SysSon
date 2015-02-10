/*
 *  DocumentHandlerImpl.scala
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
package impl

import de.sciss.file._
import de.sciss.lucre.event.Sys
import de.sciss.lucre.stm.Disposable
import de.sciss.lucre.swing._
import de.sciss.mellite.Workspace
import de.sciss.model.impl.ModelImpl

import scala.concurrent.stm.{Ref => STMRef, TMap}

private[sysson] object DocumentHandlerImpl {
  import at.iem.sysson.DocumentHandler.Document

  def apply(): DocumentHandler = new Impl

  private final class Impl extends DocumentHandler with ModelImpl[DocumentHandler.Update] {
    override def toString = "DocumentHandler"

    private val all   = STMRef(Vec.empty[Document])
    private val map   = TMap.empty[File, Document] // path to document

    // def openRead(path: String): Document = ...

    def addDocument[S <: Sys[S]](doc: Workspace[S])(implicit tx: S#Tx): Unit = {
      // doc.addListener(docListener)
      doc.folder.foreach { p =>
        require(!map.contains(p)(tx.peer), s"Workspace for path '$p' is already registered")
        map.+=(p -> doc)(tx.peer)
      }
      all.transform(_ :+ doc)(tx.peer)

      doc.addDependent(new Disposable[S#Tx] {
        def dispose()(implicit tx: S#Tx): Unit = removeDoc(doc)
      })

      deferTx(dispatch(DocumentHandler.Opened(doc)))
    }

    private def removeDoc[S <: Sys[S]](doc: Workspace[S])(implicit tx: S#Tx): Unit = {
      val folderOpt = doc.folder
      all.transform { in =>
        val idx = in.indexOf(doc)
        require(idx >= 0, s"Workspace for path '${folderOpt.map(_.path).getOrElse("")}' was not registered")
        in.patch(idx, Nil, 1)
      } (tx.peer)
      folderOpt.foreach { p => map.-=(p)(tx.peer) }

      deferTx(dispatch(DocumentHandler.Closed(doc)))
    }

    def allDocuments: Iterator[Document] = all.single().iterator
    def getDocument(file: File): Option[Document] = map.single.get(file)

    def isEmpty: Boolean = map.single.isEmpty
  }
}