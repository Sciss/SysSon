/*
 *  DragAndDrop.scala
 *  (SysSon)
 *
 *  Copyright (c) 2013 Institute of Electronic Music and Acoustics, Graz.
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
package gui

import java.awt.datatransfer.{UnsupportedFlavorException, Transferable, DataFlavor}
import collection.breakOut

object DragAndDrop {
  sealed trait Flavor[+A] extends DataFlavor

  def internalFlavor[A](implicit ct: reflect.ClassTag[A]): Flavor[A] =
    new DataFlavor(DataFlavor.javaJVMLocalObjectMimeType + ";class=\"" + ct.runtimeClass.getName + "\"") with Flavor[A]

  object Transferable {
    /** Creates a transferable for one particular flavor. */
    def apply[A](flavor: Flavor[A])(data: A): Transferable = new Transferable {
      override def toString = s"Transferable($data)"

      // private val flavor = internalFlavor[A]
      // println(s"My flavor is $flavor")
      def getTransferDataFlavors: Array[DataFlavor] = Array(flavor) // flavors.toArray
      def isDataFlavorSupported(_flavor: DataFlavor): Boolean = {
        // println(s"is $flavor the same as ${this.flavor} ? ${flavor == this.flavor}")
        _flavor == flavor
        // flavors.contains(flavor)
      }
      def getTransferData(_flavor: DataFlavor): AnyRef  = {
        if (!isDataFlavorSupported(_flavor)) throw new UnsupportedFlavorException(flavor)
        data  /* .getOrElse(throw new IOException()) */ .asInstanceOf[AnyRef]
      }
    }

    /** Creates a transferable by wrapping a sequence of existing transferables. */
    def seq(xs: Transferable*): Transferable = new Transferable {
      def getTransferDataFlavors: Array[DataFlavor] = xs.flatMap(_.getTransferDataFlavors)(breakOut)
      def isDataFlavorSupported(_flavor: DataFlavor): Boolean = xs.exists(_.isDataFlavorSupported(_flavor))
      def getTransferData(_flavor: DataFlavor): AnyRef = {
        val peer = xs.find(_.isDataFlavorSupported(_flavor)).getOrElse(throw new UnsupportedFlavorException(_flavor))
        peer.getTransferData(_flavor)
      }
    }
  }
}