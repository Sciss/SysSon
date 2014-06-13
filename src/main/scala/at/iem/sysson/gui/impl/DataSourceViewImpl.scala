/*
 *  DataSourceViewImpl.scala
 *  (SysSon)
 *
 *  Copyright (c) 2013-2014 Institute of Electronic Music and Acoustics, Graz.
 *  Written by Hanns Holger Rutz.
 *
 *	This software is published under the GNU General Public License v3+
 *
 *
 *	For further information, please contact Hanns Holger Rutz at
 *	contact@sciss.de
 */

package at.iem.sysson
package gui
package impl

import de.sciss.mellite.gui.impl.WindowImpl
import ucar.nc2
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.{JComponent, TransferHandler, JTree}
import javax.swing.table.AbstractTableModel
import annotation.{tailrec, switch}
import scala.swing.{SplitPane, ScrollPane, BorderPanel, BoxPanel, Orientation, Action, Table, Component, Swing}
import Swing._
import de.sciss.swingtree.{Tree, ExternalTreeModel}
import de.sciss.swingtree.event.TreeNodeSelected
import de.sciss.desktop
import desktop.OptionPane
import de.sciss.file._
import de.sciss.icons.raphael
import de.sciss.lucre.event.Sys
import de.sciss.lucre.stm
import java.awt.datatransfer.Transferable
import at.iem.sysson.gui.DragAndDrop.MatrixDrag
import de.sciss.lucre.swing.impl.ComponentHolder
import de.sciss.lucre.swing._
import de.sciss.lucre.matrix.{Matrix, DataSource}
import scala.swing.event.TableRowsSelected
import de.sciss.mellite.Workspace
import de.sciss.mellite.gui.GUI

object DataSourceViewImpl {
  import Implicits._

  def apply[S <: Sys[S]](source: DataSource[S])(implicit workspace: Workspace[S], tx: S#Tx,
                                                cursor: stm.Cursor[S]): DataSourceView[S] = {
    implicit val resolver = WorkspaceResolver[S]
    val data  = source.data()
    val docH  = tx.newHandle(source)
    val res   = new Impl[S](docH, source.file, data)
    deferTx(res.guiInit())
    res
  }

  private final class GroupModel(root: nc2.Group)
    extends ExternalTreeModel[nc2.Group](root :: Nil, _.children) {
  }

  private object GroupRenderer extends DefaultTreeCellRenderer {
    override def getTreeCellRendererComponent(tree: JTree, value: Object, isSelected: Boolean, isExpanded: Boolean,
                                              isLeaf: Boolean, row: Int, hasFocus: Boolean) : java.awt.Component = {
      val v2 = value match {
        case g: nc2.Group =>
          val n = g.name
          if (n == "") "<untitled group>" else n
        case _ => value
      }
      super.getTreeCellRendererComponent(tree, v2, isSelected, isExpanded, isLeaf, row, hasFocus)
    }
  }

  private trait TableModel[A] extends AbstractTableModel {
    def data: Vec[A]
    def getRowCount = data.size
  }

  private final class AttrsModel(val data: Vec[nc2.Attribute]) extends TableModel[nc2.Attribute] {
    def getColumnCount  = 2 // name, value representation
    override def getColumnName(col: Int) = (col: @switch) match {
      case 0 => "Name"
      case 1 => "Value"
    }

    def getValueAt(row: Int, col: Int) = {
      val attr = data(row)
      (col: @switch) match {
        case 0 => attr.name
        case 1 =>
          //          val typ = attr.dataType
          if (attr.size == 1) {
            attr.values.getObject(0).toString
          } else {
            s"${attr.size} elements of type ${attr.dataType}"
          }
      }
    }
  }

  private final class VarsModel(val data: Vec[nc2.Variable]) extends TableModel[nc2.Variable] {
    def getColumnCount = 5 // name, /* full-name, */ description, data-type, shape /* , size */
    override def getColumnName(col: Int) = (col: @switch) match {
      case 0 => "Name"
      case 1 => "Description"
      case 2 => "Data Type"
      case 3 => "Shape"
      case 4 => "Units"
    }

    def getValueAt(row: Int, col: Int) = {
      val vr = data(row)
      (col: @switch) match {
        case 0 => vr.name
//        case 1 => attr.fullName
        case 1 /* 2 */ => vr.description.getOrElse("")
        case 2 /* 3 */ => vr.dataType
        case 3 /* 4 */ => (vr.dimensions zip vr.shape).map {
          case (dim, sz) => dim.nameOption match {
            case Some(name) => s"$name:$sz"
            case _ => sz.toString
          }
        } mkString ("[", ", ", "]")

        case 4 => vr.units.getOrElse("")
      }
    }
  }

  private final class Impl[S <: Sys[S]](sourceH: stm.Source[S#Tx, DataSource[S]], val file: File, data: nc2.NetcdfFile)
                                       (implicit val workspace: Workspace[S], val cursor: stm.Cursor[S])
    extends DataSourceView[S] with ComponentHolder[Component] {
    impl =>
    
    private var _selVar     = Option.empty[nc2.Variable]

    private var mGroupAttrs = new AttrsModel(Vec.empty)
    private var mGroupVars  = new VarsModel (Vec.empty)

    private var tGroupAttrs: Table = _
    private var tGroupVars : Table = _

    private var tGroups    : Tree[nc2.Group]  = _

    def source(implicit tx: S#Tx) = sourceH()

    def guiInit(): Unit = {
      requireEDT()

      val mGroups = new GroupModel(data.rootGroup)
      tGroups = new Tree(mGroups) {
        selection.mode  = Tree.SelectionMode.Single
        renderer        = Tree.Renderer.wrap(GroupRenderer)
        listenTo(selection)
        reactions += {
          case TreeNodeSelected(g: nc2.Group) => groupSelected(g)
        }
        peer.setVisibleRowCount(3)
      }

      tGroupAttrs = {
        val res                     = new Table()
        res.selection.intervalMode  = Table.IntervalMode.Single
        res.model                   = mGroupAttrs
        res
      }

      tGroupVars = new Table() {
        selection.intervalMode  = Table.IntervalMode.Single
        model                   = mGroupVars
        listenTo(selection)
        reactions += {
          case TableRowsSelected(_, range, adjusting) if !adjusting =>
            val vro: Option[nc2.Variable] = if (range.isEmpty) None else {
              val data = mGroupVars.data
              // val row   = range.end // weird scala-swing convention! screw the range, let's use the leadIndex instead
              val row = selection.rows.leadIndex
              if (row >= 0 && data.size > row) Some(data(row)) else None
            }
            deafTo(selection) // avoid feedback
            try {
              selectedVariable = vro
            } finally {
              listenTo(selection)
            }
        }
        
        peer.setDragEnabled(true)
        peer.setTransferHandler(new TransferHandler {
          // ---- export ----

          // dragging only works when MOVE _and_ COPY are included. Why?
          override def getSourceActions(c: JComponent): Int =
            TransferHandler.LINK | TransferHandler.COPY | TransferHandler.MOVE

          override def createTransferable(c: JComponent): Transferable = {
            selectedVariable.flatMap { vr =>
              val varHOpt= impl.cursor.step { implicit tx =>
                val ds      = sourceH()
                val p       = vr.parents
                val n       = vr.name
                val dsvOpt  = ds.variables.find(dsv => dsv.name == n && dsv.parents == p)
                dsvOpt.map(tx.newHandle[Matrix[S]])
              }
              varHOpt.map(varH => DragAndDrop.Transferable(DragAndDrop.MatrixFlavor) {
                new MatrixDrag {
                  type S1 = S
                  val workspace = impl.workspace
                  val matrix  = varH
                }
              })
            } .orNull
          }
        })
      }

      val actionPlot = Action("Plot") {
        selectedVariable.foreach { v =>
          val in          = v.file
          // val vm          = in.variableMap
          val dim         = v.dimensions // .filter(d => vm.get(d.name.getOrElse("?")).map(_.isFloat).getOrElse(false))
          val red         = v.reducedDimensions
          if (!v.isFloat) {
            // abort if variable is not in floating point format
            val opt = OptionPane.message(
              message = s"Variable '${v.name}' is not in floating point format.",
              messageType = OptionPane.Message.Info)
            opt.show(GUI.findWindow(component))

          } else if (dim.size < 2) {
            // abort if there is less than two dimensions
            val opt = OptionPane.message(
              message = s"Variable '${v.name}' has ${if (dim.size == 0) "no dimensions" else "only one dimension"}.\nNeed at least two for a plot.",
              messageType = OptionPane.Message.Info)
            opt.show(GUI.findWindow(component))
          } else {
            // identify latitude and longitude by their unit names, and time by its dimension name
            // (that seems to be working with the files we have)
            val latOpt = red.find { d =>
              in.variableMap.get(d.name).flatMap(_.units) == Some("degrees_north")
            }
            val lonOpt = red.find { d =>
              in.variableMap.get(d.name).flatMap(_.units) == Some("degrees_east")
            }
            val timeOpt = red.find { d => d.name == "time" }

            // first see if there are useful dimensions such as lon/lat
            val xyOpt0: Option[(nc2.Dimension, nc2.Dimension)] = (latOpt, lonOpt, timeOpt) match {
              case (Some(lat), Some(lon), _)  => Some(lat -> lon)
              // case (Some(lat), _, Some(tim))  => Some(lat -> tim)
              // case (_, Some(lon), Some(tim))  => Some(lon -> tim) // does this make sense?
              case _                          => None
            }

            // if not, ask the user which dims she wants to plot
            val xyOpt = xyOpt0.orElse {
              val infos = dim.map { d => s"${d.name} [${d.size}]" } .mkString(", ")
              val pairs: Vec[(nc2.Dimension, nc2.Dimension)] = dim.flatMap { dx =>
                dim.collect {
                  case dy if dy != dx => (dx, dy)
                }
              }

              val names = pairs.map { case (dx, dy) => s"x = ${dx.name}, y = ${dy.name}" }

              val opt = OptionPane(message = s"Select the dimensions of '${v.name}' to plot.\n$infos",
                messageType = OptionPane.Message.Question,
                optionType  = OptionPane.Options.OkCancel, entries = names)
              val res = opt.show(GUI.findWindow(component)).id
              if (res >= 0) {
                Some(pairs(res))
              } else None: Option[(nc2.Dimension, nc2.Dimension)]
            }

            // open the actual plot if we got the dimensions
            xyOpt.foreach { case (yDim, xDim) =>
              cursor.step { implicit tx =>
                val frame = new WindowImpl[S](s"Plot : ${v.name}") {
                  val view: View[S] = ClimateView(source, v.selectAll, xDim = xDim, yDim = yDim)
                  // size = (600, 600)
                }
                frame.init()
              }
            }
          }
        }
      }

      tGroups.selectInterval(0, 0)

      val ggPlot  = GUI.toolButton(actionPlot, raphael.Shapes.LineChart)
      val ggSplit = new SplitPane(Orientation.Horizontal, new ScrollPane(tGroupAttrs), new ScrollPane(tGroupVars))

      component = new BorderPanel {
        add(new ScrollPane(tGroups), BorderPanel.Position.North)
        add(ggSplit, BorderPanel.Position.Center)
        // new ScrollPane(tGroupAttrs),
        // new ScrollPane(tGroupVars),
        // new BoxPanel(Orientation.Horizontal) {
        //  contents += ggPlot
        // }
        add(new BoxPanel(Orientation.Horizontal) {
          contents += HGlue
          contents += ggPlot
          contents += HGlue
        }, BorderPanel.Position.South)
      }
    }

    def dispose()(implicit tx: S#Tx) = () // : Unit = GUI.fromTx(disposeGUI())

    private def groupSelected(g: nc2.Group): Unit = {
      mGroupAttrs       = new AttrsModel(g.attributes)
      mGroupVars        = new VarsModel (g.variables )
      tGroupAttrs.model = mGroupAttrs
      tGroupVars .model = mGroupVars
    }

    private def selectGroup(group: nc2.Group): Unit = {
      @tailrec def loop(path: Tree.Path[nc2.Group], g: nc2.Group): Tree.Path[nc2.Group] = {
        val p2 = g +: path
        g.parentOption match {
          case Some(p)  => loop(p2, p)
          case _        => p2
        }
      }
      val fullPath = loop(Tree.Path.empty[nc2.Group], group)
      tGroups.expandPath(fullPath)
      tGroups.selection.paths += fullPath
      tGroups.peer.makeVisible(tGroups.pathToTreePath(fullPath))
      // the following in fact kicks in automatically due to the event dispatch;
      // but the caller needs to make selections to tGroupVars for example,
      // so that update must happen synchronously. eventually we could
      // add a filter to `groupSelected` not to do the work twice.
      groupSelected(group)
    }

    def selectedVariable: Option[nc2.Variable] = {
      requireEDT()
      _selVar
    }

    def selectedVariable_=(opt: Option[nc2.Variable]): Unit = {
      requireEDT()
      _selVar = opt
      val sel = tGroupVars.selection.rows
      opt match {
        case Some(vr) =>
          selectGroup(vr.group)
          val row = mGroupVars.data.indexOf(vr)
          if (row >= 0 && sel.leadIndex != row) sel += row

        case None =>
          if (sel.size != 0) sel.clear()
      }
    }
  }
}