package at.iem.sysson

import de.sciss.desktop.Preferences
import Preferences.{Entry, Type}
import de.sciss.file._
import javax.swing.UIManager
import UIManager.LookAndFeelInfo

object Prefs {
  import gui.SwingApplication.userPrefs

  implicit object LookAndFeelType extends Type[LookAndFeelInfo] {
    def toString(value: LookAndFeelInfo): String = value.getClassName
    def valueOf(string: String): Option[LookAndFeelInfo] =
      UIManager.getInstalledLookAndFeels.find(_.getClassName == string)
  }

  // ---- gui ----

  def defaultLookAndFeel: LookAndFeelInfo = {
    val clazzName = UIManager.getSystemLookAndFeelClassName
    LookAndFeelType.valueOf(clazzName).getOrElse(new LookAndFeelInfo("<system>", clazzName))
  }

  def lookAndFeel: Entry[LookAndFeelInfo] = userPrefs("look-and-feel")

  def nativeWindowDecoration: Entry[Boolean] = userPrefs("native-window-decoration")

  // ---- audio ----

  final val defaultSuperCollider    = file("<SC_HOME>")
  final val defaultAudioDevice      = "<default>"
  final val defaultAudioNumOutputs  = 8
  final val defaultHeadphonesBus    = 0

  def superCollider  : Entry[File  ] = userPrefs("supercollider"    )
  def audioDevice    : Entry[String] = userPrefs("audio-device"     )
  def audioNumOutputs: Entry[Int   ] = userPrefs("audio-num-outputs")
  def headphonesBus  : Entry[Int   ] = userPrefs("headphones-bus"   )
}