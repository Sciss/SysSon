package at.iem.sysson
package gui
package impl

import swing.{Component, ScrollPane, Swing}
import de.sciss.scalainterpreter.LogPane
import java.io.OutputStream
import javax.swing.{WindowConstants, BorderFactory}
import at.iem.sysson.gui.GUI
import swing.event.WindowClosing

// lazy window - opens as soon as something goes to the console
private[gui] final class LogWindowImpl extends LogWindow {
  frame =>
  peer.getRootPane.putClientProperty("Window.style", "small")

  val log = LogPane()

  private val observer: OutputStream = new OutputStream {
    override def write(b: Array[Byte], off: Int, len: Int) {
      log.makeDefault()               // detaches this observer
      log.outputStream.write(b, off, len)
      Swing.onEDT(frame.open())     // there we go
    }

    def write(b: Int) {
      write(Array(b.toByte), 0, 1)
    }
  }

  def observe() {
    Console.setOut(observer)
    Console.setErr(observer)
  }

  observe()
  peer.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE)
  listenTo(this)
  reactions += {
    case WindowClosing(_) =>
      frame.visible = false
      observe()
  }

  contents = new ScrollPane {
    contents  = Component.wrap(log.component)
    border    = BorderFactory.createEmptyBorder()
  }

  title   = "Log"
  menuBar = MenuFactory.root.create(this)
  pack()
  import LogWindow._
  GUI.placeWindow(frame, horizontal = horizontalPlacement, vertical = verticalPlacement, padding = placementPadding)
}