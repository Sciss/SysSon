package at.iem.sysson.gui.impl

import java.awt.Color

import scala.swing.{Alignment, Insets, Graphics2D, Label}

class ElapsedBar extends Label {
  private var _value    = 0f
  private var _painted  = 0
  private var _textPerc = null: String

  private var _textVisible = true

  def textVisible: Boolean = _textVisible
  def textVisible_=(value: Boolean): Unit = if (_textVisible != value) {
    _textVisible = value
    text = if (value) _textPerc else null
  }

  text = "99%"    // for getting the preferred width
  preferredSize = {
    val d = preferredSize
    d.width = math.max(d.width, 100 + 1)
    d
  }
  text = null
  horizontalAlignment = Alignment.Center

  def value: Float = _value
  def value_=(x: Float): Unit = {
    val c         = math.max(0f, math.min(1f, x))
    _value        = c
    val w         = valuePix
    val textOld   = _textPerc
    _textPerc     = valueText
    if (_textVisible && _textPerc != textOld) text = _textPerc
    else if (w != _painted) repaint()
  }

  // private var innerWidth  = 0
  private val in          = new Insets(0, 0, 0, 0)

  private def valuePix: Int = {
    peer.getInsets(in)
    val innerWidth = peer.getWidth - (in.left + in.right)
    ((innerWidth - 1) * _value + 0.5f).toInt
  }

  private def valueText: String = {
    val i = (_value * 100 + 0.5f).toInt
    if (i == 0 || i == 100) null else s"$i%"
  }

  override protected def paintComponent(g: Graphics2D): Unit = {
    _painted = valuePix
    g.setColor(Color.lightGray)
    val h = peer.getHeight - (in.top + in.bottom)
    g.fillRect(in.left, in.top, _painted, h)
    if (_painted > 0) {
      g.setColor(Color.gray)
      val xr = peer.getWidth - (in.right + 1)
      g.drawLine(xr, in.top, xr, h)
    }
    super.paintComponent(g)
  }
}