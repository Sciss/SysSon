//package at.iem.sysson.turbulence
//
//import de.sciss.desktop.DocumentHandler
//import de.sciss.file._
//import de.sciss.lucre.expr.{Expr, Double => DoubleEx}
//import de.sciss.lucre.stm
//import de.sciss.lucre.swing.{defer, requireEDT}
//import de.sciss.lucre.synth.{Sys => SSys}
//import de.sciss.lucre.event.Sys
//import de.sciss.mellite.{Mellite, Workspace, Application}
//import de.sciss.mellite.gui.ActionOpenWorkspace
//import de.sciss.synth.proc
//import de.sciss.synth.proc.{DoubleElem, Scan, Proc, Transport, ExprImplicits, AuralObj, IntElem, Elem, Ensemble, Obj, Folder, SoundProcesses}
//import proc.Implicits._
//
//import scala.concurrent.stm.{Ref, Txn}
//import scala.language.higherKinds
//
//object Motion {
//  private var _instance: Option[Algorithm[_]] = None
//
//  def instance: Option[Algorithm[_]] = {
//    requireEDT()
//    _instance
//  }
//
//  def run(start: Boolean): Unit = defer {
//    _instance.foreach(_.stopGUI())
//    _instance = None
//    val f = Turbulence.baseDir / "workspaces" / "Turbulence.mllt"
//    Application.documentHandler.addListener {
//      case DocumentHandler.Added(doc: Workspace.Confluent) if doc.folder == f =>
//        implicit val cursor = doc.cursors.cursor
//        SoundProcesses.scheduledExecutorService.submit(new Runnable {
//          def run(): Unit = {
//            val i = cursor.step { implicit tx => init(doc, start = start) }
//            defer { _instance = i }
//          }
//        })
//    }
//    ActionOpenWorkspace.perform(f)
//  }
//
//  private def warn(what: String): Unit = Console.err.println(s"WARNING: $what")
//
//  private def info(what: String): Unit = Console.out.println(s"INFO: $what")
//
//  implicit class ResolveObj[S <: Sys[S]](private val obj: Obj[S]) {
//    def as[E[~ <: Sys[~]] <: Elem[~]](implicit tx: S#Tx, companion: Elem.Companion[E]): Option[E[S]#Peer] = {
//      val res = if (obj.elem.typeID == companion.typeID) Some(obj.elem.peer.asInstanceOf[E[S]#Peer]) else None
//      if (res.isEmpty) warn(s"Object $obj is not of type ${companion.typeID.toHexString}")
//      res
//    }
//  }
//
//  private def init[S <: SSys[S]](workspace: Workspace[S], start: Boolean)
//                                (implicit tx: S#Tx, cursor: stm.Cursor[S]): Option[Algorithm[S]] = {
//    val root = workspace.root()
//    for {
//      Ensemble.Obj(layers) <- root / "layers"
//    } yield {
//      // println(s"State is ${state.value}")
//      val t = Transport[S](Mellite.auralSystem)
//      t.addObject(layers)
//      // AuralObj(layers).play()
//      // t.play()
//
//      val alg = new Algorithm(tx.newHandle(layers), t)
//      if (start) {
//        info("Play layers")
//        alg.start()
//      }
//      alg
//    }
//  }
//
//  def rrand(lo: Double, hi: Double) = math.random * (hi - lo) + lo
//
//  def getScan[S <: Sys[S]](p: Proc.Obj[S], key: String)(implicit tx: S#Tx): Option[Scan[S]] = {
//    val res = p.elem.peer.scans.get(key)
//    if (res.isEmpty) warn(s"Scan $key not found in ${p.attr.name}")
//    res
//  }
//
//  def setInt[S <: Sys[S]](obj: Obj[S], key: String, value: Int, quiet: Boolean = false)(implicit tx: S#Tx): Unit = {
//    val res = obj.attr[IntElem](key).collect {
//      case Expr.Var(vr) =>
//        val imp = ExprImplicits[S]
//        import imp._
//        vr() = value
//    }
//    if (res.isEmpty && !quiet) warn(s"Int attr $key not found / a var, in ${obj.attr.name}")
//  }
//
//  def setDouble[S <: Sys[S]](obj: Obj[S], key: String, value: Double, force: Boolean = false, quiet: Boolean = false)
//                            (implicit tx: S#Tx): Unit = {
//    val imp = ExprImplicits[S]
//    import imp._
//    val res = obj.attr[DoubleElem](key).collect {
//      case Expr.Var(vr) =>
//        vr() = value
//
//      case _ if force =>
//        obj.attr.put(key, Obj(DoubleElem(DoubleEx.newVar(value))))
//    }
//    if (res.isEmpty && !quiet) warn(s"Double attr $key not found / a var, in ${obj.attr.name}")
//  }
//
//  def getDouble[S <: Sys[S]](obj: Obj[S], key: String, quiet: Boolean = false)(implicit tx: S#Tx): Option[Double] = {
//    val res = obj.attr[DoubleElem](key).map(_.value)
//    if (res.isEmpty && !quiet) warn(s"Double attr $key not found, in ${obj.attr.name}")
//    res
//  }
//
//  def toggleFilter[S <: Sys[S]](p: Proc.Obj[S], pred: Proc.Obj[S], succ: Proc.Obj[S], bypass: Boolean)(implicit tx: S#Tx): Unit =
//    for {
//      predOut   <- getScan(pred, "out")
//      succIn    <- getScan(succ, "in" )
//      filterIn  <- getScan(p,    "in" )
//      filterOut <- getScan(p,    "out")
//    } {
//      if (bypass) {
//        predOut  .addSink     (Scan.Link.Scan(succIn   ))
//        predOut  .removeSink  (Scan.Link.Scan(filterIn ))
//        filterOut.removeSink  (Scan.Link.Scan(succIn   ))
//      } else {
//        filterOut.addSink     (Scan.Link.Scan(succIn   ))
//        predOut  .addSink     (Scan.Link.Scan(filterIn ))
//        predOut  .removeSink  (Scan.Link.Scan(succIn   ))
//      }
//    }
//
//  def play[S <: Sys[S]](obj: Ensemble.Obj[S], value: Boolean)(implicit tx: S#Tx): Unit = {
//    val res = Expr.Var.unapply(obj.elem.peer.playing)
//    res.foreach { vr =>
//      val imp = ExprImplicits[S]
//      import imp._
//      vr() = value
//    }
//    if (res.isEmpty) println(s"WARNING: Ensemble playing not a var")
//  }
//
//  def playGate[S <: Sys[S]](obj: Ensemble.Obj[S], value: Boolean)(implicit tx: S#Tx): Unit = {
//    setInt(obj, "gate", if (value) 1 else 0, quiet = value)
//    if (value) play(obj, value)
//  }
//
//  class Algorithm[S <: SSys[S]](layersH: stm.Source[S#Tx, Ensemble.Obj[S]], transport: Transport[S])
//                              (implicit cursor: stm.Cursor[S]) {
//    private val imp = ExprImplicits[S]
//    import imp._
//
//    def start()(implicit tx: S#Tx): Unit = {
//      stopAll()
//      iterate()
//      transport.play()
//    }
//
//    def startGUI(): Unit = cursor.step { implicit tx => start() }
//
//    def stop()(implicit tx: S#Tx): Unit = transport.stop()
//
//    def stopGUI(): Unit = cursor.step { implicit tx => stop() }
//
//    def layers(implicit tx: S#Tx) = layersH()
//
//    def after(secs: Double)(code: S#Tx => Unit): Unit = {
//      val t = new Thread {
//        override def run(): Unit = {
//          Thread.sleep((secs * 1000).toLong)
//          cursor.step { implicit tx =>
//            if (transport.isPlaying) code(tx)
//          }
//        }
//      }
//      Txn.findCurrent.fold(t.start()) { implicit tx =>
//        Txn.afterCommit(_ => t.start())
//      }
//    }
//
//    ////////////////////////////////////////////
//
//    def stopAll()(implicit tx: S#Tx): Unit = {
//      stopData1()
//      stopData2()
//      removeFilter()
//    }
//
//    def iterate()(implicit tx: S#Tx): Unit =
//      if (layers.isPlaying) {
//        playFreesound()
//        val d = rrand(60, 120)
//        after(d) { implicit tx => playData1() }
//      }
//
//    def playFreesound()(implicit tx: S#Tx): Unit =
//      for {
//        Ensemble.Obj(freesound) <- layers / "freesound"
//      } {
//        info("---- start freesound ----")
//        freesound.play()
//      }
//
//    //    def playData1X()(implicit tx: S#Tx): Unit = {
//    //      val atk = toggleData1(value = true) .getOrElse(10.0)
//    //      val d   = atk
//    //      after(d) { implicit tx => stopFreesound() }
//    //    }
//
//    def playData1()(implicit tx: S#Tx): Unit = {
//      for {
//        Proc.Obj(col1)          <- layers  / "col-1"
//        Proc.Obj(col2)          <- layers  / "out"
//        Ensemble.Obj(bgF)       <- layers  / "bg-filter"
//        Proc.Obj(bgFP)          <- bgF     / "proc"
//        Ensemble.Obj(data1)     <- layers  / "data-1"
//      } {
//        info(s"---- play data-1 ----")
//        val atk = rrand(45, 90)
//        setDouble(bgF, "dur", atk)
//        play(bgF, value = true)
//        toggleFilter(bgFP, pred = col1, succ = col2, bypass = false)
//
//        val rls = rrand(45, 90)
//        setDouble(data1, "attack" , atk)
//        setDouble(data1, "release", rls)
//        playGate (data1, value = true)
//
//        after(atk) { implicit tx => releaseFreesound() }
//      }
//    }
//
//    def releaseFreesound()(implicit tx: S#Tx): Unit = {
//      for {
//        Ensemble.Obj(freesound) <- layers / "freesound"
//      } {
//        info("---- release freesound ----")
//        freesound.release()
//        val d = rrand(45, 60)
//        after(d) { implicit tx =>
////          for {
////            Ensemble.Obj(freesound) <- layers / "freesound"
////          } {
////            info("---- stop freesound ----")
////            play(freesound, value = false)
////            startData2()
////          }
//          removeFilter()
//          startData2()
//        }
//      }
//    }
//
//    def removeFilter()(implicit tx: S#Tx): Unit = {
//      for {
//        Proc.Obj(col1)          <- layers  / "col-1"
//        Proc.Obj(col2)          <- layers  / "out"
//        Ensemble.Obj(bgF)       <- layers  / "bg-filter"
//        Proc.Obj(bgFP)          <- bgF     / "proc"
//      } {
//        info(s"---- remove filter ----")
//        play(bgF, value = false)
//        toggleFilter(bgFP, pred = col1, succ = col2, bypass = true)
//      }
//    }
//
//    def startData2()(implicit tx: S#Tx): Unit = {
//      for {
//        Ensemble.Obj(data1) <- layers / "data-1"
//        Ensemble.Obj(data2) <- layers / "data-2"
//      } {
//        info(s"---- play data-2 ----")
//
//        val rls1 = getDouble(data1, "release").getOrElse(10.0)
//
//        val atk2 = rrand(45, 90)
//        val rls2 = rrand(45, 90)
//        setDouble(data2, "attack" , atk2)
//        setDouble(data2, "release", rls2)
//
//        playGate(data1, value = false)
//        playGate(data2, value = true )
//
//        val d = math.max(rls1, atk2)
//        after(d) { implicit tx =>
//          stopData1()
//          after(rrand(45, 90)) { implicit tx =>
//            releaseData2()
//          }
//        }
//      }
//    }
//
//    def stopData1()(implicit tx: S#Tx): Unit = {
//      for {
//        Ensemble.Obj(data1) <- layers / "data-1"
//      } {
//        info(s"---- stop data-1 ----")
//        play(data1, value = false)
//      }
//    }
//
//    def releaseData2()(implicit tx: S#Tx): Unit = {
//      for {
//        Ensemble.Obj(data2) <- layers / "data-2"
//      } {
//        info(s"---- release data-2 ----")
//        playGate(data2, value = false)
//
//        val rls = getDouble(data2, "release").getOrElse(10.0)
//        after(rls * 0.7) { implicit tx =>
//          playFreesound()
//          after(rls * 0.5) { implicit tx =>
//            stopData2()
//            val d = rrand(60, 120)
//            after(d) { implicit tx =>
//              playData1()
//            }
//          }
//        }
//      }
//    }
//
//    def stopData2()(implicit tx: S#Tx): Unit = {
//      for {
//        Ensemble.Obj(data2) <- layers / "data-2"
//      } {
//        info(s"---- stop data-2 ----")
//        play(data2, value = false)
//      }
//    }
//
////    def toggleData1(value: Boolean)(implicit tx: S#Tx): Option[Double] = {
////      for {
////        Proc.Obj(col1)          <- layers  / "col-1"
////        Proc.Obj(col2)          <- layers  / "out"
////        Ensemble.Obj(bgF)       <- layers  / "bg-filter"
////        Proc.Obj(bgFP)          <- bgF     / "proc"
////        Ensemble.Obj(data)      <- layers  / "data-1"
////      } yield {
////        info(s"---- ${if (value) "play" else "stop"} data-1 ----")
////        val atk = rrand(45, 90)
////        setDouble(bgF, "dur", atk)
////        play(bgF, value = value)
////        toggleFilter(bgFP, pred = col1, succ = col2, bypass = !value)
////
////        val tim = if (value) {
////          val rls = rrand(45, 90)
////          setDouble(data, "attack" , atk)
////          setDouble(data, "release", rls)
////          atk
////
////        } else {
////          getDouble(data, "release").getOrElse(10.0)
////        }
////
////        playGate(data, value = value)
////        tim
////      }
////    }
//  }
//
//  // E freesound
//  // E layer-1
//  // P col-1
//  // E bg-filter
//  // E data-1
//  // P out
//}
