/*
 *  SonificationSourceViewImpl.scala
 *  (SysSon)
 *
 *  Copyright (c) 2013-2014 Institute of Electronic Music and Acoustics, Graz.
 *  Written by Hanns Holger Rutz.
 *
 *	This software is published under the GNU General Public License v2+
 *
 *
 *	For further information, please contact Hanns Holger Rutz at
 *	contact@sciss.de
 */

package at.iem.sysson
package gui
package impl

import de.sciss.lucre.event.Sys
import de.sciss.lucre.stm.Disposable
import scala.swing._
import at.iem.sysson.gui.DragAndDrop.{MappingDrag, MatrixDrag}
import at.iem.sysson.sound.Sonification
import de.sciss.desktop.UndoManager
import de.sciss.lucre.{stm, expr}
import at.iem.sysson.Implicits._
import de.sciss.swingplus.Implicits._
import language.reflectiveCalls
import de.sciss.lucre.expr.Expr
import scala.concurrent.stm.Ref
import de.sciss.model
import de.sciss.lucre.expr.{String => StringEx}
import de.sciss.lucre.swing.edit.{EditVar, EditMutableMap}
import de.sciss.lucre.swing.impl.ComponentHolder
import de.sciss.lucre.swing._
import de.sciss.lucre.matrix.gui.MatrixView
import de.sciss.lucre.matrix.Matrix
import de.sciss.icons.raphael
import javax.swing.TransferHandler
import java.awt.datatransfer.Transferable
import java.awt.Color

object SonificationSourceViewImpl {
  private val DEBUG = true

  def apply[S <: Sys[S]](map: expr.Map[S, String, Sonification.Source[S], Sonification.Source.Update[S]],
                         key: String, keyDimNames: Vec[String])
                        (implicit tx: S#Tx, workspace: Workspace[S],
                         undoManager: UndoManager): SonificationSourceView[S] = {
    // de.sciss.lucre.event.showLog = true
    // de.sciss.lucre.matrix.gui.impl.MatrixViewImpl.DEBUG = true

    import workspace.cursor
    val mapHOpt     = map.modifiableOption.map(tx.newHandle(_))
    val matView     = MatrixView[S]
    matView.nameVisible = false
    val res         = new Impl[S](matView, mapHOpt, key = key, keyDimNames = keyDimNames)
    res.sourceObserver  = map.changed.react {
      implicit tx => upd => upd.changes.foreach {
        case expr.Map.Added  (`key`, source) => res.updateSource(Some(source)) // .data.file.base))
        case expr.Map.Removed(`key`, source) => res.updateSource(None)
        // case expr.Map.Element(`key`, source, sourceUpdate)  => res.update(now       )
        case _ =>
      }
    }
    if (DEBUG) println(s"SonificationSourceView(map = $map; ${map.changed}); sourceObserver = ${res.sourceObserver}")

    deferTx(res.guiInit())
    res.updateSource(map.get(key))
    res
  }

  private final class Impl[S <: Sys[S]](matrixView: MatrixView[S],
      mapHOpt: Option[stm.Source[S#Tx, expr.Map.Modifiable[S, String, Sonification.Source[S], Sonification.Source.Update[S]]]],
      key: String, keyDimNames: Vec[String])
     (implicit workspace: Workspace[S], undoManager: UndoManager)
    extends SonificationSourceView[S] with ComponentHolder[Component] {
    impl =>

    import workspace.cursor

    private var ggDataName: TextField = _
    // private var ggMappings = Vec.empty[ComboBox[String]]

    var sourceObserver: Disposable[S#Tx] = _

    private val assocViews = Ref(Vec.empty[SonificationAssocView[S]])

    def dispose()(implicit tx: S#Tx): Unit = {
      sourceOptRef.set(None)(tx.peer)
      sourceObserver.dispose()
      disposeDimMap()
      matrixView.dispose()
      // assocViews.foreach(_.dispose())
    }

    private def disposeDimMap()(implicit tx: S#Tx): Unit = {
      val as = assocViews.swap(Vec.empty)(tx.peer)
      if (as.nonEmpty) {
        matrixView.rowHeaders = Vec.empty
        as.foreach(_.dispose())
      }
      dimMap.set(None)(tx.peer)
    }

    private val dimMap = Ref(
      Option.empty[stm.Source[S#Tx, expr.Map.Modifiable[S, String, Expr[S, String], model.Change[String]]]]
    )

    private val sourceOptRef = Ref(Option.empty[stm.Source[S#Tx, Sonification.Source[S]]])

    def updateSource(sourceOpt: Option[Sonification.Source[S]])(implicit tx: S#Tx): Unit = {
      disposeDimMap()

      sourceOptRef.set(sourceOpt.map(tx.newHandle(_)))(tx.peer)
      matrixView.matrix = sourceOpt.map(_.matrix)

      val tup = sourceOpt.map { source =>
        val v             = source.matrix
        val _dataName     = v.name
        val sDims     = source.dims
        import StringEx.serializer
        dimMap.set(sDims.modifiableOption.map(tx.newHandle(_)))(tx.peer)
        // dimMapObserver.set(Some(mapObs))(tx.peer)

        val as = v.dimensions.map { dim => SonificationAssocView[S](source, dim.name) }
        assocViews.set(as)(tx.peer)
        matrixView.rowHeaders = as

        _dataName
      }

      deferTx {
        tup.fold {
          ggDataName.text = ""
        } { dataName =>
          ggDataName.text = dataName
        }
      }
    }

    def guiInit(): Unit = {
      ggDataName  = new TextField(16)
      // dataName0.foreach(ggDataName.text = _)
      ggDataName.editable = false
      ggDataName.border   = Swing.CompoundBorder(outside = ggDataName.border,
        inside = IconBorder(Icons.Target(DropButton.IconSize)))
      mapHOpt.foreach { mapH =>
        DropButton.installTransferHandler[MatrixDrag](ggDataName, DragAndDrop.MatrixFlavor) { drag0 =>
          if (drag0.workspace == workspace) {
            val drag  = drag0.asInstanceOf[MatrixDrag { type S1 = S }] // XXX TODO: how to make this more pretty?
            val edit  = cursor.step { implicit tx =>
              val map     = mapH()
              val v       = drag.matrix()
              val name    = "Assign Data Source Variable"
              map.get(key).flatMap(src => Matrix.Var.unapply(src.matrix)).fold {
                val vr      = Matrix.Var(v) // so that the matrix becomes editable in its view
                val source  = Sonification.Source(vr)
                EditMutableMap(name, map, key, Some(source))
              } { vr =>
                EditVar(name, vr, v)
              }
            }
            undoManager.add(edit)
            true

          } else {
            println("ERROR: Cannot drag data sources across workspaces")
            false
          }
        }
      }

      val keyDimButs = keyDimNames.map { key0 =>
        new DragAndDrop.Button {
          text = key0

          protected def export(): Option[Transferable] = sourceOptRef.single.get.map { src =>
            DragAndDrop.Transferable(DragAndDrop.MappingFlavor)(new MappingDrag {
              type S1 = S
              def source: stm.Source[S#Tx, Sonification.Source[S]] = src
              def key: String = key0
              def workspace: Workspace[S] = impl.workspace
            })
          }

          protected def sourceAction(mod: Int) = TransferHandler.LINK

          protected def sourceActions: Int =
            TransferHandler.LINK | TransferHandler.COPY | TransferHandler.MOVE
        }
      }
      if (keyDimButs.nonEmpty) {
        val d = new Dimension(0, 0)
        keyDimButs.foreach { but =>
          val pd = but.preferredSize
          d.width = math.max(d.width, pd.width)
          d.height= math.max(d.height, pd.height)
        }
        keyDimButs.foreach { but =>
          but.preferredSize = d
          but.minimumSize   = d
          but.maximumSize   = d
        }
      }

      val ggMap = new BoxPanel(Orientation.Horizontal) {
        contents += new Label(null) {
          icon = raphael.Icon(extent = 24, fill = Color.gray)(raphael.Shapes.Hand) // Clip
        }
        contents ++= keyDimButs
        contents += Swing.HGlue
      }

      component = new BoxPanel(Orientation.Vertical) {
        override lazy val peer = {
          val p = new javax.swing.JPanel with SuperMixin {
            // cf. http://stackoverflow.com/questions/11726739/use-getbaselineint-w-int-h-of-a-child-component-in-the-parent-container
            override def getBaseline(w: Int, h: Int): Int = {
              val size = ggDataName.preferredSize
              ggDataName.location.y + ggDataName.peer.getBaseline(size.width, size.height)
            }
          }
          val l = new javax.swing.BoxLayout(p, Orientation.Vertical.id)
          p.setLayout(l)
          p
        }

        contents += ggDataName
        contents += matrixView.component
        contents += ggMap
      }
    }
  }
}