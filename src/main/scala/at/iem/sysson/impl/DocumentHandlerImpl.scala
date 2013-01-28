package at.iem.sysson
package impl

private[sysson] object DocumentHandlerImpl {
  def apply(): DocumentHandler = new Impl

  private final class Impl extends DocumentHandler with ModelImpl[DocumentHandler.Update] {
    override def toString = "DocumentHandler"

    private val sync  = new AnyRef
    private var all   = Vector.empty[Document]

    private val docListener: Document.Listener = {
      case Document.Closed(doc) => removeDoc(doc)
    }

    def openRead(path: String): Document = {
      val doc = DocumentImpl.openRead(path)
      doc.addListener(docListener)
      sync.synchronized( all :+= doc )
      dispatch(DocumentHandler.Opened(doc))
      doc
    }

    private def removeDoc(doc: Document) {
      sync.synchronized {
        val idx = all.indexOf(doc)
        assert(idx >= 0)
        doc.removeListener(docListener)
        all = all.patch(idx, Nil, 1)
      }
      dispatch(DocumentHandler.Closed(doc))
    }

    def allDocuments: Iterator[Document] = sync.synchronized( all.iterator )
  }
}