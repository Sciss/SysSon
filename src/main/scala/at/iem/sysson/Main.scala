/*
 *  Main.scala
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

import at.iem.sysson
import at.iem.sysson.gui.SwingApplication
import at.iem.sysson.legacy.NcviewSync

import scala.util.control.NonFatal

object Main {
  final val useNcView = false
  final val useGUI    = true

  lazy val name   : String = buildInfoString("name"   )
  lazy val version: String = buildInfoString("version")

  private def buildInfoString(key: String): String = try {
    val clazz = Class.forName("at.iem.sysson.BuildInfo")
    val m     = clazz.getMethod(key)
    m.invoke(null).toString
  } catch {
    case NonFatal(e) => "?"
  }

  def main(args: Array[String]): Unit = {
    logInfo(s"Welcome to $name v$version")

    // ---- type extensions ----

    sysson.initTypes()

    if (useNcView) {
      val ncView = NcviewSync()
      ncView.dump(on = true)
      ncView.start()
    }

    if (useGUI) {
      SwingApplication.main(args)
      //      // this is just for simple IDEA run configurations.
      //      // the app-bundle will have these already
      //      sys.props("com.apple.mrj.application.apple.menu.about.name")  = name
      //      sys.props("apple.laf.useScreenMenuBar")                       = "true"
      //      Swing.onEDT(gui.GUI.init())
    }
  }
}