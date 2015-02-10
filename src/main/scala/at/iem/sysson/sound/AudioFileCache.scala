/*
 *  AudioFileCache.scala
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
package sound

import de.sciss.file._
import de.sciss.lucre.event.Sys
import de.sciss.lucre.matrix.{Matrix, DataSource}
import de.sciss.lucre.stm.TxnLike
import scala.concurrent.Future
import de.sciss.lucre.{matrix, stm}

/** The application wide cache for graphemes. */
object AudioFileCache {
  private val instance = {
    val config = matrix.AudioFileCache.Config()
    config.folder = dataDir / "cache" // XXX TODO should read prefs
    matrix.AudioFileCache(config)
  }

  type Value = matrix.AudioFileCache.Value

  def acquire[S <: Sys[S]](key: Matrix.Key)(implicit tx: S#Tx, resolver: DataSource.Resolver[S],
                                            cursor: stm.Cursor[S]): Future[Value] =
    instance.acquire(key)

  def release(key: Matrix.Key)(implicit tx: TxnLike): Unit = instance.release(key)
}
