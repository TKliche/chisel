package Chisel
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.HashMap
import scala.collection.mutable.HashSet

object FameDecoupledIO
{
  def connect[T <: Bundle](flattened: FameDecoupledIO[Bits], connectTo: FameDecoupledIO[T], tgt_bits_type: Bundle): Unit = {
    val is_flip = (flattened.host_ready.dir == OUTPUT)
    if(is_flip){
      flattened.host_valid := connectTo.host_valid
      connectTo.host_ready := flattened.host_ready
      flattened.target.valid := connectTo.target.valid
      connectTo.target.ready := flattened.target.ready
      flattened.target.bits := connectTo.target.bits.toBits
    } else {
      connectTo.host_valid := flattened.host_valid
      flattened.host_ready := connectTo.host_ready
      connectTo.target.valid := flattened.target.valid
      flattened.target.ready := connectTo.target.ready
      connectTo.target.bits := tgt_bits_type.fromBits(flattened.target.bits)
    }
  }
}

class FameDecoupledIO[T <: Data](data: T) extends Bundle
{
  val host_valid = Bool(OUTPUT)
  val host_ready = Bool(INPUT)
  val target = new DecoupledIO(data)
  override def clone: this.type = { new FameDecoupledIO(data).asInstanceOf[this.type]}
}

class FameQueue[T <: Data] (val entries: Int)(data: => T) extends Module
{
  val io = new Bundle{
    val deq = new FameDecoupledIO(data)
    val enq = new FameDecoupledIO(data).flip()
  }
  
  val target_queue = Module(new Queue(data, entries))
  val tracker = Module(new FameQueueTracker(entries, entries))
  
  target_queue.io.enq.valid := io.enq.host_valid && io.enq.target.valid
  target_queue.io.enq.bits := io.enq.target.bits
  io.enq.target.ready := target_queue.io.enq.ready
  
  io.deq.target.valid := tracker.io.entry_avail && target_queue.io.deq.valid
  io.deq.target.bits := target_queue.io.deq.bits
  target_queue.io.deq.ready := io.deq.host_ready && io.deq.target.ready && tracker.io.entry_avail
  
  tracker.io.tgt_queue_count := target_queue.io.count
  tracker.io.produce := io.enq.host_valid && io.enq.host_ready
  tracker.io.consume := io.deq.host_valid && io.deq.host_ready
  tracker.io.tgt_enq := target_queue.io.enq.valid && target_queue.io.enq.ready
  tracker.io.tgt_deq := io.deq.target.valid && target_queue.io.deq.ready

  io.enq.host_ready := !tracker.io.full && target_queue.io.enq.ready 
  io.deq.host_valid := !tracker.io.empty
  
}

class FameQueueTrackerIO() extends Bundle{
  val tgt_queue_count = UInt(INPUT)
  val produce = Bool(INPUT)
  val consume = Bool(INPUT)
  val tgt_enq = Bool(INPUT)
  val tgt_deq = Bool(INPUT)
  val empty = Bool(OUTPUT)
  val full = Bool(OUTPUT)
  val entry_avail = Bool(OUTPUT)
}

class FameQueueTracker(num_tgt_entries: Int, num_tgt_cycles: Int) extends Module{
  val io = new FameQueueTrackerIO()
  val aregs = Vec.fill(num_tgt_cycles){ Reg(init = UInt(0, width = log2Up(num_tgt_entries))) }
  val tail_pointer = Reg(init = UInt(1, width = log2Up(num_tgt_cycles)))
  
  val next_tail_pointer = UInt()
  tail_pointer := next_tail_pointer
  next_tail_pointer := tail_pointer
  when(io.produce && !io.consume){
    next_tail_pointer := tail_pointer + UInt(1)
  }.elsewhen(!io.produce && io.consume){
    next_tail_pointer := tail_pointer - UInt(1)
  }
  for (i <- 1 until num_tgt_cycles - 1){
    val next_reg_val = UInt()
    aregs(i) := next_reg_val
    next_reg_val := aregs(i)
    when(UInt(i) === tail_pointer){
      when(io.produce && io.tgt_enq && !io.consume){
        next_reg_val := aregs(i - 1) + UInt(1)
      }.elsewhen(io.produce && !io.tgt_enq && !io.consume){
        next_reg_val := aregs(i - 1)
      }
    }.elsewhen(UInt(i) === tail_pointer - UInt(1)){
      when(io.produce && io.tgt_enq && io.consume && io.tgt_deq){
      }.elsewhen(io.produce && io.tgt_enq && io.consume && !io.tgt_deq){
        next_reg_val := aregs(i) + UInt(1)
      }.elsewhen(io.produce && !io.tgt_enq && io.consume && io.tgt_deq){
        next_reg_val := aregs(i) - UInt(1)
      }
    }.otherwise{
      when(io.produce && io.tgt_enq && io.consume && io.tgt_deq){
        next_reg_val := aregs(i + 1) - UInt(1)
      }.elsewhen(io.produce && io.tgt_enq && io.consume && !io.tgt_deq){
        next_reg_val := aregs(i + 1)
      }.elsewhen(io.produce && !io.tgt_enq && io.consume && io.tgt_deq){
        next_reg_val := aregs(i + 1) - UInt(1)
      }.elsewhen(io.produce && !io.tgt_enq && io.consume && !io.tgt_deq){
        next_reg_val := aregs(i + 1)
      }.elsewhen(!io.produce && io.consume && io.tgt_deq){
        next_reg_val := aregs(i + 1) - UInt(1)
      }.elsewhen(!io.produce && io.consume && !io.tgt_deq){
        next_reg_val := aregs(i + 1)
      }
    }
  }
  val next_reg_val0 = UInt()
  aregs(0) := next_reg_val0
  next_reg_val0 := aregs(0)
  when(UInt(0) === tail_pointer){
    when(io.produce && io.tgt_enq && !io.consume){
      next_reg_val0 := io.tgt_queue_count + UInt(1)
    }.elsewhen(io.produce && !io.tgt_enq && io.consume && io.tgt_deq){
    }.elsewhen(io.produce && !io.tgt_enq && io.consume && !io.tgt_deq){
    }.elsewhen(io.produce && !io.tgt_enq && !io.consume){
      next_reg_val0 := io.tgt_queue_count
    }
  }.elsewhen(UInt(0) === tail_pointer - UInt(1)){
    when(io.produce && io.tgt_enq && io.consume && !io.tgt_deq){
      next_reg_val0 := aregs(0) + UInt(1)
    }.elsewhen(io.produce && !io.tgt_enq && io.consume && io.tgt_deq){
      next_reg_val0 := aregs(0) - UInt(1)
    }.elsewhen(io.produce && !io.tgt_enq && io.consume && !io.tgt_deq){
    }
  }.otherwise{
    when(io.produce && io.tgt_enq && io.consume && io.tgt_deq){
      next_reg_val0 := aregs(1) - UInt(1)
    }.elsewhen(io.produce && io.tgt_enq && io.consume && !io.tgt_deq){
      next_reg_val0 := aregs(1)
    }.elsewhen(io.produce && !io.tgt_enq && io.consume && io.tgt_deq){
      next_reg_val0 := aregs(1) - UInt(1)
    }.elsewhen(io.produce && !io.tgt_enq && io.consume && !io.tgt_deq){
      next_reg_val0 := aregs(1)
    }.elsewhen(!io.produce && io.consume && io.tgt_deq){
      next_reg_val0 := aregs(1) - UInt(1)
    }.elsewhen(!io.produce && io.consume && !io.tgt_deq){
      next_reg_val0 := aregs(1)
    }
  }
  val next_reg_val_last = UInt()
  aregs(num_tgt_cycles - 1) := next_reg_val_last
  next_reg_val_last := aregs(num_tgt_cycles - 1)
  when(UInt(num_tgt_cycles - 1) === tail_pointer){
    when(io.produce && io.tgt_enq && io.consume && !io.tgt_deq){
    }.elsewhen(io.produce && io.tgt_enq && !io.consume){
      next_reg_val_last := aregs(num_tgt_cycles - 1 - 1) + UInt(1)
    }.elsewhen(io.produce && !io.tgt_enq && !io.consume){
      next_reg_val_last := aregs(num_tgt_cycles - 1 - 1)
    }
  }.elsewhen(UInt(num_tgt_cycles - 1) === tail_pointer - UInt(1)){
    when(io.produce && io.tgt_enq && io.consume && !io.tgt_deq){
      next_reg_val_last := aregs(num_tgt_cycles - 1) + UInt(1)
    }.elsewhen(io.produce && !io.tgt_enq && io.consume && io.tgt_deq){
      next_reg_val_last := aregs(num_tgt_cycles - 1) - UInt(1)
    }.elsewhen(io.produce && !io.tgt_enq && io.consume && !io.tgt_deq){
    }
  }
  io.full := tail_pointer === UInt(num_tgt_cycles)
  io.empty := tail_pointer === UInt(0)
  io.entry_avail := aregs(0) != UInt(0)
}

class RegIO[T <: Data](data: T) extends Bundle
{
  val bits = data.clone.asOutput
}

class FameReg[T <: Data] (entries: Int)(data: T, resetVal: T = null) extends Module
{
  val io = new Bundle{
    val deq = new DecoupledIO(data)
    val enq = new DecoupledIO(data).flip()
  }
  
  val shiftRegs = new ArrayBuffer[T]
  for(i <- 0 until entries){
    if(i == 0){
      shiftRegs += RegInit(resetVal)
    } else {
      shiftRegs += Reg(data.clone)
    }
  }
  
  val tailPointer = Reg(init = UInt(1, width = log2Up(entries)))
  val enqueue = io.enq.valid && io.enq.ready
  val dequeue = io.deq.valid && io.deq.ready
  when(enqueue && !dequeue){
    tailPointer := tailPointer + UInt(1)
  }.elsewhen(!enqueue && dequeue){
    tailPointer := tailPointer - UInt(1)
  }
  val empty = tailPointer === UInt(0)
  val full = (tailPointer === UInt(entries))

  for(i <- 0 until (entries - 1)){
    when(dequeue){
      shiftRegs(i) := shiftRegs(i + 1)
    }
  }
  
  for(i <- 0 until entries){
    when(UInt(i) === tailPointer){
      when(enqueue){
        when(!dequeue){
          shiftRegs(i) := io.enq.bits
        }
      }
    }.elsewhen(UInt(i) === (tailPointer - UInt(1))){
      when(enqueue){
        when(dequeue){
          shiftRegs(i) := io.enq.bits
        }
      }
    }
  }
  io.deq.valid := !empty
  io.deq.bits := shiftRegs(0)
  io.enq.ready := !full

}

class Fame1WrapperIO(num_queues: Int, num_regs: Int, num_debug: Int) extends Bundle {
  var queues:Vec[FameDecoupledIO[Bits]] = null
  if(num_queues > 0) {
    queues = Vec.fill(num_queues){ new FameDecoupledIO(Bits())}
  }
  var regs:Vec[DecoupledIO[Bits]] = null
  if(num_regs > 0) {
    regs = Vec.fill(num_regs){ new DecoupledIO(Bits())}
  }
  var debug:Vec[Bits] = null
  if(num_debug > 0) {
    debug = Vec.fill(num_debug){Bits()}
  }
}

class Fame1Wrapper(f: => Module) extends Module {
  def transform(isTop: Boolean, module: Module, parent: Module): Unit = {
    Fame1Transform.fame1Modules += module
    val isFire = Bool(INPUT)
    isFire.isIo = true
    isFire.setName("is_fire")
    isFire.component = module
    Fame1Transform.fireSignals(module) = isFire
    if(!isTop){
      Predef.assert(Fame1Transform.fireSignals(parent) != null)
      isFire := Fame1Transform.fireSignals(parent)
    }
    module.io.asInstanceOf[Bundle] += isFire
    for(submodule <- module.children){
      transform(false, submodule, module)
    }
  }
  
  val originalModule = Module(f)
  transform(true, originalModule, null)

  //counter number of RegIO and Decoupled IO in original module
  var num_decoupled_io = 0
  var num_reg_io = 0
  var num_debug_io = 0
  for ((name, io) <- originalModule.io.asInstanceOf[Bundle].elements){ 
    io match { 
      case q : DecoupledIO[_] => num_decoupled_io += 1; 
      case r : RegIO[_] => num_reg_io += 1;
      case _ => {
        if (name != "is_fire") {
          num_debug_io += 1
        }
      }
    }
  }

  val io = new Fame1WrapperIO(num_decoupled_io, num_reg_io, num_debug_io)
  
  val RegIOs = new HashMap[String, DecoupledIO[Bits]]()
  val DecoupledIOs  = new HashMap[String, FameDecoupledIO[Bits]]()
  val DebugIOs = new HashMap[String, Data]()

  var decoupled_counter = 0
  var reg_counter = 0
  var debug_counter = 0 
  //populate fame1RegIO and fame1DecoupledIO bundles with the elements from the original RegIO and DecoupleIOs
  for ((name, ioNode) <- originalModule.io.asInstanceOf[Bundle].elements) {
    ioNode match {
      case decoupled : DecoupledIO[_] => {
        val is_flip = (decoupled.ready.dir == OUTPUT)
        val fame1Decoupled      = io.queues(decoupled_counter)
        if (is_flip) {
          fame1Decoupled.flip()
          fame1Decoupled.target.ready := decoupled.ready
          decoupled.valid := fame1Decoupled.target.valid
          val decoupledBitsClone = decoupled.bits.clone()
          decoupled.bits := decoupledBitsClone.fromBits(fame1Decoupled.target.bits)
        } else {
          decoupled.ready := fame1Decoupled.target.ready
          fame1Decoupled.target.bits := decoupled.bits.toBits
          fame1Decoupled.target.valid := decoupled.valid 
        }
        DecoupledIOs(name) = fame1Decoupled
        decoupled_counter += 1
      }
      case reg : RegIO[_] => {
        val is_flip = (reg.bits.flatten(0)._2.dir == INPUT)
        val fame1RegIO = io.regs(reg_counter)
        if (is_flip) {
          fame1RegIO.flip()
          val regBitsClone = reg.bits.clone()
          reg.bits := regBitsClone.fromBits(fame1RegIO.bits)
        } else {
          fame1RegIO.bits := reg.bits.toBits
        }
        RegIOs(name) = fame1RegIO
        reg_counter += 1
      }
      case _ => {
        if (name != "is_fire") {
          Predef.assert(ioNode.isInstanceOf[Bits])
          if(ioNode.toBits.dir == INPUT){
            io.debug(debug_counter).asInput
            ioNode := io.debug(debug_counter)
          } else {
            io.debug(debug_counter).asOutput
            io.debug(debug_counter) := ioNode.toBits
          }
          DebugIOs(name) = io.debug(debug_counter)
          debug_counter += 1
        }
      }
    }
  }
  //generate fire_tgt_clk signal
  var fire_tgt_clk = Bool(true)
  if (io.queues != null){
    for (q <- io.queues)
      fire_tgt_clk = fire_tgt_clk && 
        (if (q.host_valid.dir == OUTPUT) q.host_ready else q.host_valid)
  }
  if (io.regs != null){
    for (r <- io.regs) {
      fire_tgt_clk = fire_tgt_clk && 
        (if (r.valid.dir == OUTPUT) r.ready else r.valid)
    }
  }
  
  //generate host read and host valid signals
  Fame1Transform.fireSignals(originalModule) := fire_tgt_clk
  if (io.queues != null){
    for (q <- io.queues) {
      if (q.host_valid.dir == OUTPUT) 
        q.host_valid := fire_tgt_clk
      else
        q.host_ready := fire_tgt_clk
    }
  }
  if (io.regs != null){
    for (r <- io.regs) {
      if (r.valid.dir == OUTPUT) 
        r.valid := fire_tgt_clk
      else
        r.ready := fire_tgt_clk
    }
  }
}

object Fame1Transform {
  val fame1Modules = new HashSet[Module]
  val fireSignals = new HashMap[Module, Bool]
}

trait Fame1Transform extends Backend {
  private def collectMems(module: Module): ArrayBuffer[(Module, Mem[Data])] = {
    val mems = new ArrayBuffer[(Module, Mem[Data])]
    //find all the mems in FAME1 modules
    def findMems(module: Module): Unit = {
      if(Fame1Transform.fame1Modules.contains(module)){
        for(mem <- module.nodes.filter(_.isInstanceOf[Mem[Data]])){
          mems += ((module, mem.asInstanceOf[Mem[Data]]))
        }
      }
      for(childModule <- module.children){
        findMems(childModule)
      }
    }
    findMems(module)
    return mems
  }
  
  private def appendFireToRegWriteEnables(top: Module) = {
    //find regs that are part of sequential mem read ports
    val mems = collectMems(top)
    val seqMemReadRegs = new HashSet[Reg]
    for((module, mem) <- mems){
      val memSeqReads = mem.seqreads ++ mem.readwrites.map(_.read)
      /*if(mem.seqRead){
        for(memRead <- mem.reads){
          seqMemReadRegs += memRead.addr.inputs(0).asInstanceOf[Reg]
        }
      }*/
      for(memSeqRead <- memSeqReads){
        seqMemReadRegs += memSeqRead.addrReg
      }
    }

    //find all the registers in FAME1 modules
    val regs = new ArrayBuffer[(Module, Reg)]
    def findRegs(module: Module): Unit = {
      if(Fame1Transform.fame1Modules.contains(module)){
        for(reg <- module.nodes.filter(_.isInstanceOf[Reg])){
          if(!seqMemReadRegs.contains(reg.asInstanceOf[Reg])){
            regs += ((module, reg.asInstanceOf[Reg]))
          }
        }
      }
      for(childModule <- module.children){
        findRegs(childModule)
      }
    }
    findRegs(top)
    
    
    for((module, reg) <- regs){
      reg.enable = reg.enable && Fame1Transform.fireSignals(module)
      if(reg.updates.length == 0){
        val regOutput = Bits()
        regOutput.inputs += reg
        val regMux = Bits()
        regMux.inputs += reg.inputs(0)
        reg.inputs(0) = Mux(Fame1Transform.fireSignals(module), regMux, regOutput)
      } else {
        for(i <- 0 until reg.updates.length){
          val wEn = reg.updates(i)._1
          val wData = reg.updates(i)._2
          reg.updates(i) = ((wEn && Fame1Transform.fireSignals(module), wData))
        }
      }
    }
  }
 
  private def appendFireToMemEnables(top: Module) = {
    val mems = collectMems(top)

    for((module, mem) <- mems){
      val memWrites = mem.writes ++ mem.readwrites.map(_.write)
      val memSeqReads = mem.seqreads ++ mem.readwrites.map(_.read)
      for(memWrite <- memWrites){
        if(mem.seqRead){
          if(Module.backend.isInstanceOf[CppBackend]){
            if(memWrite.inputs(0).asInstanceOf[Data].comp != null && memWrite.inputs(1).asInstanceOf[Data].comp != null){//huge hack for extra MemWrite generated for seqread mems in CPP backed; if both the cond and enable both happen to be directly from registers, this will fail horribly
              memWrite.inputs(1) = memWrite.inputs(1).asInstanceOf[Bool] && Fame1Transform.fireSignals(module)
            } else {
              memWrite.inputs(1) = Bool(false)
            }
          } else {
            memWrite.inputs(1) = memWrite.inputs(1).asInstanceOf[Bool] && Fame1Transform.fireSignals(module)
          }
        } else {
          memWrite.inputs(1) = memWrite.inputs(1).asInstanceOf[Bool] && Fame1Transform.fireSignals(module)
        }
      }
      for(memSeqRead <- memSeqReads){
        Predef.assert(memSeqRead.addrReg.updates.length == 1)
        val oldReadAddr = Bits()
        oldReadAddr.inputs += memSeqRead.addrReg.updates(0)._2
        val oldReadAddrReg = Reg(Bits())
        oldReadAddrReg.comp.component = module
        oldReadAddrReg.comp.asInstanceOf[Reg].enable = if (oldReadAddrReg.comp.asInstanceOf[Reg].isEnable) oldReadAddrReg.comp.asInstanceOf[Reg].enable || Fame1Transform.fireSignals(module) else Fame1Transform.fireSignals(module)
        oldReadAddrReg.comp.asInstanceOf[Reg].isEnable = true
        oldReadAddrReg.comp.asInstanceOf[Reg].updates += ((Fame1Transform.fireSignals(module), oldReadAddr))
        
        val newReadAddr = Mux(Fame1Transform.fireSignals(module), oldReadAddr, oldReadAddrReg)
        
        val oldReadEn = Bool()
        oldReadEn.inputs += memSeqRead.addrReg.updates(0)._1
        val renReg = Reg(init=Bool(false))
        renReg.comp.component = module
        
        renReg.comp.asInstanceOf[Reg].enable = if(renReg.comp.asInstanceOf[Reg].isEnable) renReg.comp.asInstanceOf[Reg].enable || Fame1Transform.fireSignals(module) else Fame1Transform.fireSignals(module)
        renReg.comp.asInstanceOf[Reg].isEnable = true
        renReg.comp.asInstanceOf[Reg].updates += ((Fame1Transform.fireSignals(module), oldReadEn))
        val newRen = Mux(Fame1Transform.fireSignals(module), oldReadEn, renReg)
        
        memSeqRead.addrReg.enable = newRen
        memSeqRead.addrReg.updates.clear
        memSeqRead.addrReg.updates += ((newRen, newReadAddr))
      }
    }
  }
  
  
  preElaborateTransforms += ((top: Module) => collectNodesIntoComp(initializeDFS))
  preElaborateTransforms += ((top: Module) => appendFireToRegWriteEnables(top))
  preElaborateTransforms += ((top: Module) => top.genAllMuxes)
  preElaborateTransforms += ((top: Module) => appendFireToMemEnables(top))
  preElaborateTransforms += ((top: Module) => collectNodesIntoComp(initializeDFS))
  preElaborateTransforms += ((top: Module) => top.genAllMuxes)
}

class Fame1CppBackend extends CppBackend with Fame1Transform
class Fame1VerilogBackend extends VerilogBackend with Fame1Transform
class Fame1FPGABackend extends FPGABackend with Fame1Transform

/*
class Fame5WrapperIO(num_copies: Int, num_queues: Int, num_regs: Int, num_debug: Int) extends Bundle {
  var queues:Vec[FameDecoupledIO[Bits]] = null
  if(num_queues > 0) {
    queues = Vec.fill(num_copies*num_queues){ new FameDecoupledIO(Bits())}
  }
  var regs:Vec[DecoupledIO[Bits]] = null
  if(num_regs > 0) {
    regs = Vec.fill(num_copies*num_regs){ new DecoupledIO(Bits())}
  }
  var debug:Vec[Bits] = null
  if(num_debug > 0) {
    debug = Vec.fill(num_copies*num_debug){Bits()}
  }
}

class Fame5Wrapper(num_copies: Int, f: => Module) extends Module {
  def addFireToIO(isTop: Boolean, module: Module, parent: Module): Unit = {
    Fame5Transform.fame1Modules += module
    val isFire = Bool(INPUT)
    isFire.isIo = true
    isFire.setName("is_fire")
    isFire.component = module
    Fame5Transform.fireSignals(module) = isFire
    if(!isTop){
      Predef.assert(Fame5Transform.fireSignals(parent) != null)
      isFire := Fame5Transform.fireSignals(parent)
    }
    module.io.asInstanceOf[Bundle] += isFire
    for(submodule <- module.children){
      addFireToIO(false, submodule, module)
    }
  }
  
  def replicateIO(module: Module): Unit = {
  }

  val originalModules = new ArrayBuffer[Module]
  for(i <- 0 until num_copies){
    originalModules += Module(f)
  }
  for(originalModule <- originalModules){
    addFireToIO(true, originalModule, null)
  }
  
  //counter number of RegIO and Decoupled IO in original module
  var num_decoupled_io = 0
  var num_reg_io = 0
  var num_debug_io = 0
  for ((name, io) <- originalModules(0).io.asInstanceOf[Bundle].elements){ 
    io match { 
      case q : DecoupledIO[_] => num_decoupled_io += 1; 
      case r : RegIO[_] => num_reg_io += 1;
      case _ => {
        if (name != "is_fire") {
          num_debug_io += 1
        }
      }
    }
  }

  val io = new Fame5WrapperIO(num_copies, num_decoupled_io, num_reg_io, num_debug_io)
  
  val RegIOs = new ArrayBuffer[HashMap[String, DecoupledIO[Bits]]]()
  val DecoupledIOs  = new ArrayBuffer[HashMap[String, FameDecoupledIO[Bits]]]()
  val DebugIOs = new ArrayBuffer[HashMap[String, Data]]()

  for(i <- 0 until num_copies){
    RegIOs += new HashMap[String, DecoupledIO[Bits]]()
    DecoupledIOs  += new HashMap[String, FameDecoupledIO[Bits]]()
    DebugIOs += new HashMap[String, Data]()
  }

  for(i <- 0 until num_copies){
    val originalModule = originalModules(i)
    var decoupled_counter = 0
    var reg_counter = 0
    var debug_counter = 0 
    //populate fame1RegIO and fame1DecoupledIO bundles with the elements from the original RegIO and DecoupleIOs
    for ((name, ioNode) <- originalModule.io.asInstanceOf[Bundle].elements) {
      ioNode match {
        case decoupled : DecoupledIO[_] => {
          val is_flip = (decoupled.ready.dir == OUTPUT)
          val fame1Decoupled = io.queues(num_copies*i + decoupled_counter)
          if (is_flip) {
            fame1Decoupled.flip()
            fame1Decoupled.target.ready := decoupled.ready
            decoupled.valid := fame1Decoupled.target.valid
            val decoupledBitsClone = decoupled.bits.clone()
            decoupled.bits := decoupledBitsClone.fromBits(fame1Decoupled.target.bits)
          } else {
            decoupled.ready := fame1Decoupled.target.ready
            fame1Decoupled.target.bits := decoupled.bits.toBits
            fame1Decoupled.target.valid := decoupled.valid 
          }
          DecoupledIOs(i)(name) = fame1Decoupled
          decoupled_counter += 1
        }
        case reg : RegIO[_] => {
          val is_flip = (reg.bits.flatten(0)._2.dir == INPUT)
          val fame1RegIO = io.regs(num_copies*i + reg_counter)
          if (is_flip) {
            fame1RegIO.flip()
            val regBitsClone = reg.bits.clone()
            reg.bits := regBitsClone.fromBits(fame1RegIO.bits)
          } else {
            fame1RegIO.bits := reg.bits.toBits
          }
          RegIOs(i)(name) = fame1RegIO
          reg_counter += 1
        }
        case _ => {
          if (name != "is_fire") {
            Predef.assert(ioNode.isInstanceOf[Bits])
            if(ioNode.toBits.dir == INPUT){
              io.debug(debug_counter).asInput
              ioNode := io.debug(debug_counter)
            } else {
              io.debug(debug_counter).asOutput
              io.debug(debug_counter) := ioNode.toBits
            }
            DebugIOs(i)(name) = io.debug(num_copies*i + debug_counter)
            debug_counter += 1
          }
        }
      }
    }

    //generate fire_tgt_clk signal
    var fire_tgt_clk = Bool(true)
    for (queue <- DecoupledIOs(i).values){
      fire_tgt_clk = fire_tgt_clk && (if (queue.host_valid.dir == OUTPUT) queue.host_ready else queue.host_valid)
    }
    for (reg <- RegIOs(i).values) {
      fire_tgt_clk = fire_tgt_clk && (if (reg.valid.dir == OUTPUT) reg.ready else reg.valid)
    }
    
    //generate host read and host valid signals
    Fame5Transform.fireSignals(originalModule) := fire_tgt_clk
    for (queue <- DecoupledIOs(i).values) {
      if (queue.host_valid.dir == OUTPUT){ 
        queue.host_valid := fire_tgt_clk
      } else {
        queue.host_ready := fire_tgt_clk
      }
    }
    for (reg <- RegIOs(i).values) {
      if (reg.valid.dir == OUTPUT) {
        reg.valid := fire_tgt_clk
      } else {
        reg.ready := fire_tgt_clk
      }
    }
  }
}

object Fame5Transform {
  val fame1Modules = new HashSet[Module]
  val fireSignals = new HashMap[Module, Bool]
}

trait Fame5Transform extends Backend {
  private def collectMems(module: Module): ArrayBuffer[(Module, Mem[Data])] = {
    val mems = new ArrayBuffer[(Module, Mem[Data])]
    //find all the mems in FAME1 modules
    def findMems(module: Module): Unit = {
      if(Fame5Transform.fame1Modules.contains(module)){
        for(mem <- module.nodes.filter(_.isInstanceOf[Mem[Data]])){
          mems += ((module, mem.asInstanceOf[Mem[Data]]))
        }
      }
      for(childModule <- module.children){
        findMems(childModule)
      }
    }
    findMems(module)
    return mems
  }
  
  private def appendFireToRegWriteEnables(top: Module) = {
    //find regs that are part of sequential mem read ports
    val mems = collectMems(top)
    val seqMemReadRegs = new HashSet[Reg]
    for((module, mem) <- mems){
      val memSeqReads = mem.seqreads ++ mem.readwrites.map(_.read)
      for(memSeqRead <- memSeqReads){
        seqMemReadRegs += memSeqRead.addrReg
      }
    }

    //find all the registers in FAME1 modules
    val regs = new ArrayBuffer[(Module, Reg)]
    def findRegs(module: Module): Unit = {
      if(Fame5Transform.fame1Modules.contains(module)){
        for(reg <- module.nodes.filter(_.isInstanceOf[Reg])){
          if(!seqMemReadRegs.contains(reg.asInstanceOf[Reg])){
            regs += ((module, reg.asInstanceOf[Reg]))
          }
        }
      }
      for(childModule <- module.children){
        findRegs(childModule)
      }
    }
    findRegs(top)
    
    
    for((module, reg) <- regs){
      reg.enable = reg.enable && Fame5Transform.fireSignals(module)
      if(reg.updates.length == 0){
        val regOutput = Bits()
        regOutput.inputs += reg
        val regMux = Bits()
        regMux.inputs += reg.inputs(0)
        reg.inputs(0) = Mux(Fame5Transform.fireSignals(module), regMux, regOutput)
      } else {
        for(i <- 0 until reg.updates.length){
          val wEn = reg.updates(i)._1
          val wData = reg.updates(i)._2
          reg.updates(i) = ((wEn && Fame5Transform.fireSignals(module), wData))
        }
      }
    }
  }
 
  private def appendFireToMemEnables(top: Module) = {
    val mems = collectMems(top)

    for((module, mem) <- mems){
      val memWrites = mem.writes ++ mem.readwrites.map(_.write)
      val memSeqReads = mem.seqreads ++ mem.readwrites.map(_.read)
      for(memWrite <- memWrites){
        if(mem.seqRead){
          if(Module.backend.isInstanceOf[CppBackend]){
            if(memWrite.inputs(0).asInstanceOf[Data].comp != null && memWrite.inputs(1).asInstanceOf[Data].comp != null){//huge hack for extra MemWrite generated for seqread mems in CPP backed; if both the cond and enable both happen to be directly from registers, this will fail horribly
              memWrite.inputs(1) = memWrite.inputs(1).asInstanceOf[Bool] && Fame5Transform.fireSignals(module)
            } else {
              memWrite.inputs(1) = Bool(false)
            }
          } else {
            memWrite.inputs(1) = memWrite.inputs(1).asInstanceOf[Bool] && Fame5Transform.fireSignals(module)
          }
        } else {
          memWrite.inputs(1) = memWrite.inputs(1).asInstanceOf[Bool] && Fame5Transform.fireSignals(module)
        }
      }
      for(memSeqRead <- memSeqReads){
        Predef.assert(memSeqRead.addrReg.updates.length == 1)
        val oldReadAddr = Bits()
        oldReadAddr.inputs += memSeqRead.addrReg.updates(0)._2
        val oldReadAddrReg = Reg(Bits())
        oldReadAddrReg.comp.component = module
        oldReadAddrReg.comp.asInstanceOf[Reg].enable = if (oldReadAddrReg.comp.asInstanceOf[Reg].isEnable) oldReadAddrReg.comp.asInstanceOf[Reg].enable || Fame5Transform.fireSignals(module) else Fame5Transform.fireSignals(module)
        oldReadAddrReg.comp.asInstanceOf[Reg].isEnable = true
        oldReadAddrReg.comp.asInstanceOf[Reg].updates += ((Fame5Transform.fireSignals(module), oldReadAddr))
        
        val newReadAddr = Mux(Fame5Transform.fireSignals(module), oldReadAddr, oldReadAddrReg)
        
        val oldReadEn = Bool()
        oldReadEn.inputs += memSeqRead.addrReg.updates(0)._1
        val renReg = Reg(init=Bool(false))
        renReg.comp.component = module
        
        renReg.comp.asInstanceOf[Reg].enable = if(renReg.comp.asInstanceOf[Reg].isEnable) renReg.comp.asInstanceOf[Reg].enable || Fame5Transform.fireSignals(module) else Fame5Transform.fireSignals(module)
        renReg.comp.asInstanceOf[Reg].isEnable = true
        renReg.comp.asInstanceOf[Reg].updates += ((Fame5Transform.fireSignals(module), oldReadEn))
        val newRen = Mux(Fame5Transform.fireSignals(module), oldReadEn, renReg)
        
        memSeqRead.addrReg.enable = newRen
        memSeqRead.addrReg.updates.clear
        memSeqRead.addrReg.updates += ((newRen, newReadAddr))
      }
    }
  }
  
  
  preElaborateTransforms += ((top: Module) => collectNodesIntoComp(initializeDFS))
  preElaborateTransforms += ((top: Module) => appendFireToRegWriteEnables(top))
  preElaborateTransforms += ((top: Module) => top.genAllMuxes)
  preElaborateTransforms += ((top: Module) => appendFireToMemEnables(top))
  preElaborateTransforms += ((top: Module) => collectNodesIntoComp(initializeDFS))
  preElaborateTransforms += ((top: Module) => top.genAllMuxes)
}

class Fame5CppBackend extends CppBackend with Fame5Transform
class Fame5VerilogBackend extends VerilogBackend with Fame5Transform
class Fame5FPGABackend extends FPGABackend with Fame5Transform*/

class Fame5WrapperIO(num_copies: Int, num_queues: Int, num_regs: Int, num_debug: Int) extends Bundle {
  var queues:Vec[FameDecoupledIO[Bits]] = null
  if(num_queues > 0) {
    queues = Vec.fill(num_copies*num_queues){ new FameDecoupledIO(Bits())}
  }
  var regs:Vec[DecoupledIO[Bits]] = null
  if(num_regs > 0) {
    regs = Vec.fill(num_copies*num_regs){ new DecoupledIO(Bits())}
  }
  var debug:Vec[Bits] = null
  if(num_debug > 0) {
    debug = Vec.fill(num_copies*num_debug){Bits(OUTPUT)}
  }
}

class Fame5Wrapper(num_copies: Int, f: => Module) extends Module {
  def MarkFame5Modules(module: Module): Unit = {
    Fame5Transform.fame5Modules += module
    for(submodule <- module.children){
      MarkFame5Modules(submodule)
    }
  }
  
  def replicateIO(): Unit = {
    for ((name, io) <- originalModule.io.asInstanceOf[Bundle].elements){ 
      io match { 
        case queue : DecoupledIO[_] => {
          val queueAsBits = queue.asInstanceOf[DecoupledIO[Bits]]
          Fame5Transform.DecoupledIOs(name) = new ArrayBuffer[DecoupledIO[Bits]]
          Fame5Transform.DecoupledIOs(name) += queueAsBits
          for(i <- 1 until num_copies){
            val ioCopy = new DecoupledIO(queueAsBits.bits.asInstanceOf[Bits])
            if(queueAsBits.ready.dir == OUTPUT){
              ioCopy.flip
            }
            ioCopy.valid.isIo = true
            ioCopy.ready.isIo = true
            ioCopy.bits.isIo = true
            ioCopy.setName(name + "_" + i)
            ioCopy.component = originalModule
            Fame5Transform.DecoupledIOs(name) += ioCopy
          }
        }
        case reg : RegIO[_] => {
          val regAsBits = reg.asInstanceOf[RegIO[Bits]]
          Fame5Transform.RegIOs(name) = new ArrayBuffer[RegIO[Bits]]
          Fame5Transform.RegIOs(name) += regAsBits
          for(i <- 1 until num_copies){
            val ioCopy = new RegIO(regAsBits.bits.asInstanceOf[Bits])
            if(regAsBits.bits.dir == INPUT){
              ioCopy.flip
            }
            ioCopy.bits.isIo = true
            ioCopy.setName(name + "_" + i)
            ioCopy.component = originalModule
            Fame5Transform.RegIOs(name) += ioCopy
          }
        }
        case _ => {
          val ioAsBits = io.asInstanceOf[Bits]
          Fame5Transform.DebugIOs(name) = new ArrayBuffer[Bits]
          Fame5Transform.DebugIOs(name) += ioAsBits
          for(i <- 1 until num_copies){
            val ioCopy = ioAsBits.clone
            Predef.assert(ioCopy.inputs.size == 0)
            Predef.assert(ioCopy.updates.size == 0)
            ioCopy.dir = ioAsBits.dir
            ioCopy.isIo = true
            ioCopy.setName(name + "_" + i)
            ioCopy.component = originalModule
            Fame5Transform.DebugIOs(name) += ioCopy
          }
        }
      }
    } 
  }
  
  def addThreadReadyToIO(isTop: Boolean, module: Module, parent: Module): Unit = {
    Fame5Transform.threadReadySignals(module) = new ArrayBuffer[Bool]
    for(i <- 0 until num_copies){
      val threadReady = Bool(INPUT)
      threadReady.isIo = true
      threadReady.setName("thread_ready" + "_" + i)
      threadReady.component = module
      Fame5Transform.threadReadySignals(module) += threadReady
      if(!isTop){
        Predef.assert(Fame5Transform.threadReadySignals(parent)(i) != null)
        threadReady := Fame5Transform.threadReadySignals(parent)(i)
      }
      module.io.asInstanceOf[Bundle] += threadReady
    }
    for(submodule <- module.children){
      addThreadReadyToIO(false, submodule, module)
    }
  }
  
  def addThreadSelIDToIO(isTop: Boolean, module: Module, parent: Module): Unit = {
    val threadSelID = UInt(INPUT, width = log2Up(num_copies))
    threadSelID.isIo = true
    threadSelID.setName("thread_sel_id")
    threadSelID.component = module
    Fame5Transform.threadSelIDSignals(module) = threadSelID
    if(!isTop){
      Predef.assert(Fame5Transform.threadSelIDSignals(parent) != null)
      threadSelID := Fame5Transform.threadSelIDSignals(parent)
    }
    module.io.asInstanceOf[Bundle] += threadSelID
    for(submodule <- module.children){
      addThreadSelIDToIO(false, submodule, module)
    }
  }
  
  def connectWrapperTargetIOs(): Unit = {
    var decoupled_counter = 0
    var reg_counter = 0
    var debug_counter = 0
    for((name, decoupledIOs) <- Fame5Transform.DecoupledIOs){
      Predef.assert(decoupledIOs.length == num_copies)
      for(i <- 0 until decoupledIOs.length){
        val decoupled = decoupledIOs(i)
        val is_flip = (decoupled.ready.dir == OUTPUT)
        val fame1Decoupled = io.queues(decoupled_counter)
        if (is_flip) {
          fame1Decoupled.flip()
          fame1Decoupled.target.ready := decoupled.ready
          decoupled.valid.inputs += fame1Decoupled.target.valid
          decoupled.bits.asInstanceOf[Bits].inputs += fame1Decoupled.target.bits
        } else {
          decoupled.ready.inputs += fame1Decoupled.target.ready
          fame1Decoupled.target.bits := decoupled.bits.toBits
          fame1Decoupled.target.valid := decoupled.valid 
        }
        DecoupledIOs(i)(name) = fame1Decoupled
        decoupled_counter += 1
      }
    }
    for((name, regIOs) <- Fame5Transform.RegIOs){
      Predef.assert(regIOs.length == num_copies)
      for(i <- 0 until regIOs.length){
        val reg = regIOs(i)
        val is_flip = (reg.bits.flatten(0)._2.dir == INPUT)
        val fame1RegIO = io.regs(reg_counter)
        if (is_flip) {
          fame1RegIO.flip()
          reg.bits.asInstanceOf[Bits].inputs += fame1RegIO.bits
        } else {
          fame1RegIO.bits := reg.bits.asInstanceOf[Bits]
        }
        RegIOs(i)(name) = fame1RegIO
        reg_counter += 1
      }
    }
    for((name, debugIOs) <- Fame5Transform.DebugIOs){
      Predef.assert(debugIOs.length == num_copies)
      for(i <- 0 until debugIOs.length){
        val debug = debugIOs(i)
        Predef.assert(debug.isInstanceOf[Bits])
        if(debug.toBits.dir == INPUT){
          io.debug(debug_counter).asInput
          debug := io.debug(debug_counter)
        } else {
          io.debug(debug_counter).asOutput
          io.debug(debug_counter) := debug.toBits
        }
        DebugIOs(i)(name) = io.debug(debug_counter)
        debug_counter += 1
      }
    }
  }

  def generateThreadReadySignals() = {
    for(i <- 0 until num_copies){
      var threadReadySignal = Bool(true)
      for (queue <- DecoupledIOs(i).values){
        threadReadySignal = threadReadySignal && (if (queue.host_valid.dir == OUTPUT) queue.host_ready else queue.host_valid)
      }
      for (reg <- RegIOs(i).values) {
        threadReadySignal = threadReadySignal && (if (reg.valid.dir == OUTPUT) reg.ready else reg.valid)
      }
      Fame5Transform.threadReadySignals(originalModule)(i) := threadReadySignal
    }
  }

  def generateThreadSelIDSignal() = {
    val counter = Reg(init = UInt(0, width = log2Up(num_copies)))
    counter := counter + UInt(1)
    when(counter === UInt(num_copies - 1)){
      counter := UInt(0)
    }
    val threadSelID = UInt()
    threadSelID := counter
    Fame5Transform.threadSelIDSignals(originalModule) := threadSelID
  }

  def connectWrapperHostIOs() = {
    for(i <- 0 until num_copies){
      for (queue <- DecoupledIOs(i).values) {
        if (queue.host_valid.dir == OUTPUT){ 
          queue.host_valid := Fame5Transform.threadReadySignals(originalModule)(i) && (UInt(i) === Fame5Transform.threadSelIDSignals(originalModule))
        } else {
          queue.host_ready := Fame5Transform.threadReadySignals(originalModule)(i) && (UInt(i) === Fame5Transform.threadSelIDSignals(originalModule))
        }
      }
      for (reg <- RegIOs(i).values) {
        if (reg.valid.dir == OUTPUT) {
          reg.valid := Fame5Transform.threadReadySignals(originalModule)(i) && (UInt(i) === Fame5Transform.threadSelIDSignals(originalModule))
        } else {
          reg.ready := Fame5Transform.threadReadySignals(originalModule)(i) && (UInt(i) === Fame5Transform.threadSelIDSignals(originalModule))
        }
      }
    }
  }

  val originalModule = Module(f)
  Fame5Transform.topLevelFame5Module = originalModule
  //count number of RegIO and DecoupledIO in original module
  var num_decoupled_io = 0
  var num_reg_io = 0
  var num_debug_io = 0
  for ((name, io) <- originalModule.io.asInstanceOf[Bundle].elements){ 
    io match { 
      case q : DecoupledIO[_] => num_decoupled_io += 1; 
      case r : RegIO[_] => num_reg_io += 1;
      case _ => num_debug_io += 1;
    }
  }

  val io = new Fame5WrapperIO(num_copies, num_decoupled_io, num_reg_io, num_debug_io)
  
  val RegIOs = new ArrayBuffer[HashMap[String, DecoupledIO[Bits]]]()
  val DecoupledIOs  = new ArrayBuffer[HashMap[String, FameDecoupledIO[Bits]]]()
  val DebugIOs = new ArrayBuffer[HashMap[String, Data]]()

  for(i <- 0 until num_copies){
    RegIOs += new HashMap[String, DecoupledIO[Bits]]()
    DecoupledIOs  += new HashMap[String, FameDecoupledIO[Bits]]()
    DebugIOs += new HashMap[String, Data]()
  }
  
  MarkFame5Modules(originalModule)
  replicateIO()//replicateIO must be called before addThreadReadyToIO and addThreadSelToIO because we don't want the added threadReady and threadSelID signals to be replecated
  connectWrapperTargetIOs()
  addThreadReadyToIO(true, originalModule, null)
  addThreadSelIDToIO(true, originalModule, null)
  generateThreadReadySignals()
  generateThreadSelIDSignal()
  connectWrapperHostIOs()
}

object Fame5Transform {
  val fame5Modules = new HashSet[Module]
  var topLevelFame5Module: Module = null
  val threadReadySignals = new HashMap[Module, ArrayBuffer[Bool]]
  val threadSelIDSignals = new HashMap[Module, UInt]
  val DecoupledIOs = new HashMap[String, ArrayBuffer[DecoupledIO[Bits]]]
  val RegIOs = new HashMap[String, ArrayBuffer[RegIO[Bits]]]
  val DebugIOs = new HashMap[String, ArrayBuffer[Bits]]
  val consumerMap = new HashMap[Node, ArrayBuffer[(Node, Int)]]
}

trait Fame5Transform extends Backend {
  private def findConsumerMap(module: Module) = {
    val allNodes = new ArrayBuffer[Node]
    def findAllNodes(module: Module):Unit = {
      for(node <- module.nodes){
        allNodes += node
      }
      for(child <- module.children){
        findAllNodes(child)
      }
    }
    findAllNodes(module)
    for(node <- allNodes){
      if(!Fame5Transform.consumerMap.contains(node)){
        Fame5Transform.consumerMap(node) = new ArrayBuffer[(Node, Int)]
        for(i <- 0 until node.inputs.length){
          val nodeInput = node.inputs(i)
          if(Fame5Transform.consumerMap.contains(nodeInput)){
            Fame5Transform.consumerMap(nodeInput) += ((node, i))
          }
        }
      }
    }
  }
  
  private def driveOutputs(): Unit = {
    for((name, decoupledIOs) <- Fame5Transform.DecoupledIOs){
      for(i <- 1 until decoupledIOs.length){
        val decoupled = decoupledIOs(i)
        val is_flip = (decoupled.ready.dir == OUTPUT)
        if (is_flip) {
          Predef.assert(decoupledIOs(0).ready.inputs.length == 1)
          decoupled.ready.inputs += decoupledIOs(0).ready.inputs(0)
        } else {
          Predef.assert(decoupledIOs(0).valid.inputs.length == 1)
          decoupled.valid.inputs += decoupledIOs(0).valid.inputs(0)
          Predef.assert(decoupledIOs(0).bits.isInstanceOf[Bits])
          Predef.assert(decoupledIOs(0).bits.asInstanceOf[Bits].inputs.length == 1)
          decoupled.bits.asInstanceOf[Bits].inputs += decoupledIOs(0).bits.asInstanceOf[Bits].inputs(0)
        }
      }
    }
    for((name, regIOs) <- Fame5Transform.RegIOs){
      for(i <- 1 until regIOs.length){
        val reg = regIOs(i)
        if(reg.bits.flatten(0)._2.dir == OUTPUT){
          Predef.assert(regIOs(0).bits.isInstanceOf[Bits])
          Predef.assert(regIOs(0).bits.asInstanceOf[Bits].inputs.length == 1)
          reg.bits.asInstanceOf[Bits].inputs += regIOs(0).bits.asInstanceOf[Bits].inputs(0)
        }
      }
    }
    for((name, debugIOs) <- Fame5Transform.DebugIOs){
      for(i <- 1 until debugIOs.length){
        val debug = debugIOs(i)
        if(debug.toBits.dir == OUTPUT){
          Predef.assert(debugIOs(0).inputs.length == 1)
          debug.inputs += debugIOs(0).inputs(0)
        }
      }
    }
  }

  private def muxInputs(): Unit = {
    for((name, decoupledIOs) <- Fame5Transform.DecoupledIOs){
      val originalDecoupledIO = decoupledIOs(0)
      if(originalDecoupledIO.valid.dir == INPUT){
        val validCopies = new ArrayBuffer[Bits]
        for(decoupledIO <- decoupledIOs){
          validCopies += decoupledIO.valid
        }
        insertMuxOnConsumers(originalDecoupledIO.valid, validCopies, Fame5Transform.threadSelIDSignals(Fame5Transform.topLevelFame5Module))
        val dataCopies = new ArrayBuffer[Bits]
        for(decoupledIO <- decoupledIOs){
          dataCopies += decoupledIO.bits.asInstanceOf[Bits]
        }
        insertMuxOnConsumers(originalDecoupledIO.bits.asInstanceOf[Bits], dataCopies, Fame5Transform.threadSelIDSignals(Fame5Transform.topLevelFame5Module))
      } else {
        val readyCopies = new ArrayBuffer[Bits]
        for(decoupledIO <- decoupledIOs){
          readyCopies += decoupledIO.ready
        }
        insertMuxOnConsumers(originalDecoupledIO.bits.asInstanceOf[Bits], readyCopies, Fame5Transform.threadSelIDSignals(Fame5Transform.topLevelFame5Module))
      }
    }
    for((name, regIOs) <- Fame5Transform.RegIOs){
      val originalRegIO = regIOs(0)
      if(originalRegIO.bits.asInstanceOf[Bits].dir == INPUT){
        val copies = new ArrayBuffer[Bits]
        for (regIO <- regIOs){
          copies += regIO.bits.asInstanceOf[Bits]
        }
        insertMuxOnConsumers(originalRegIO.bits.asInstanceOf[Bits], copies, Fame5Transform.threadSelIDSignals(Fame5Transform.topLevelFame5Module))
      }
    }
    for((name, debugIOs) <- Fame5Transform.DebugIOs){
      val originalDebug = debugIOs(0)
      if(originalDebug.toBits.dir == INPUT){
        val copies = new ArrayBuffer[Bits]
        for(debugIO <- debugIOs){
          copies += debugIO.asInstanceOf[Bits]
        }
        insertMuxOnConsumers(originalDebug, copies, Fame5Transform.threadSelIDSignals(Fame5Transform.topLevelFame5Module))
      }
    }
  }

  private def insertMuxOnConsumers(node: Bits, copies: ArrayBuffer[Bits], threadSelId: UInt): Unit = {
    val muxMapping = new ArrayBuffer[(Bool, Bits)]
    for(i <- 0 until copies.length){
      muxMapping += ((threadSelId === UInt(i), copies(i)))
    }
    //using MuxCase here is a hack, it is much more effiient to directly use the threadSelId as the signal to a large n-way mux
    val mux = MuxCase(node, muxMapping)
    for((consumer, inputNum) <- Fame5Transform.consumerMap(node)){
      consumer.inputs(inputNum) = mux
    }
  }
  
  /*private def collectMems(module: Module): ArrayBuffer[(Module, Mem[Data])] = {
    val mems = new ArrayBuffer[(Module, Mem[Data])]
    //find all the mems in FAME1 modules
    def findMems(module: Module): Unit = {
      if(Fame5Transform.fame5Modules.contains(module)){
        for(mem <- module.nodes.filter(_.isInstanceOf[Mem[Data]])){
          mems += ((module, mem.asInstanceOf[Mem[Data]]))
        }
      }
      for(childModule <- module.children){
        findMems(childModule)
      }
    }
    findMems(module)
    return mems
  }
  
  private def appendFireToRegWriteEnables(top: Module) = {
    //find regs that are part of sequential mem read ports
    val mems = collectMems(top)
    val seqMemReadRegs = new HashSet[Reg]
    for((module, mem) <- mems){
      val memSeqReads = mem.seqreads ++ mem.readwrites.map(_.read)
      for(memSeqRead <- memSeqReads){
        seqMemReadRegs += memSeqRead.addrReg
      }
    }

    //find all the registers in FAME1 modules
    val regs = new ArrayBuffer[(Module, Reg)]
    def findRegs(module: Module): Unit = {
      if(Fame5Transform.fame5Modules.contains(module)){
        for(reg <- module.nodes.filter(_.isInstanceOf[Reg])){
          if(!seqMemReadRegs.contains(reg.asInstanceOf[Reg])){
            regs += ((module, reg.asInstanceOf[Reg]))
          }
        }
      }
      for(childModule <- module.children){
        findRegs(childModule)
      }
    }
    findRegs(top)
    
    
    for((module, reg) <- regs){
      reg.enable = reg.enable && Fame5Transform.fireSignals(module)
      if(reg.updates.length == 0){
        val regOutput = Bits()
        regOutput.inputs += reg
        val regMux = Bits()
        regMux.inputs += reg.inputs(0)
        reg.inputs(0) = Mux(Fame5Transform.fireSignals(module), regMux, regOutput)
      } else {
        for(i <- 0 until reg.updates.length){
          val wEn = reg.updates(i)._1
          val wData = reg.updates(i)._2
          reg.updates(i) = ((wEn && Fame5Transform.fireSignals(module), wData))
        }
      }
    }
  }
 
  private def appendFireToMemEnables(top: Module) = {
    val mems = collectMems(top)

    for((module, mem) <- mems){
      val memWrites = mem.writes ++ mem.readwrites.map(_.write)
      val memSeqReads = mem.seqreads ++ mem.readwrites.map(_.read)
      for(memWrite <- memWrites){
        if(mem.seqRead){
          if(Module.backend.isInstanceOf[CppBackend]){
            if(memWrite.inputs(0).asInstanceOf[Data].comp != null && memWrite.inputs(1).asInstanceOf[Data].comp != null){//huge hack for extra MemWrite generated for seqread mems in CPP backed; if both the cond and enable both happen to be directly from registers, this will fail horribly
              memWrite.inputs(1) = memWrite.inputs(1).asInstanceOf[Bool] && Fame5Transform.fireSignals(module)
            } else {
              memWrite.inputs(1) = Bool(false)
            }
          } else {
            memWrite.inputs(1) = memWrite.inputs(1).asInstanceOf[Bool] && Fame5Transform.fireSignals(module)
          }
        } else {
          memWrite.inputs(1) = memWrite.inputs(1).asInstanceOf[Bool] && Fame5Transform.fireSignals(module)
        }
      }
      for(memSeqRead <- memSeqReads){
        Predef.assert(memSeqRead.addrReg.updates.length == 1)
        val oldReadAddr = Bits()
        oldReadAddr.inputs += memSeqRead.addrReg.updates(0)._2
        val oldReadAddrReg = Reg(Bits())
        oldReadAddrReg.comp.component = module
        oldReadAddrReg.comp.asInstanceOf[Reg].enable = if (oldReadAddrReg.comp.asInstanceOf[Reg].isEnable) oldReadAddrReg.comp.asInstanceOf[Reg].enable || Fame5Transform.fireSignals(module) else Fame5Transform.fireSignals(module)
        oldReadAddrReg.comp.asInstanceOf[Reg].isEnable = true
        oldReadAddrReg.comp.asInstanceOf[Reg].updates += ((Fame5Transform.fireSignals(module), oldReadAddr))
        
        val newReadAddr = Mux(Fame5Transform.fireSignals(module), oldReadAddr, oldReadAddrReg)
        
        val oldReadEn = Bool()
        oldReadEn.inputs += memSeqRead.addrReg.updates(0)._1
        val renReg = Reg(init=Bool(false))
        renReg.comp.component = module
        
        renReg.comp.asInstanceOf[Reg].enable = if(renReg.comp.asInstanceOf[Reg].isEnable) renReg.comp.asInstanceOf[Reg].enable || Fame5Transform.fireSignals(module) else Fame5Transform.fireSignals(module)
        renReg.comp.asInstanceOf[Reg].isEnable = true
        renReg.comp.asInstanceOf[Reg].updates += ((Fame5Transform.fireSignals(module), oldReadEn))
        val newRen = Mux(Fame5Transform.fireSignals(module), oldReadEn, renReg)
        
        memSeqRead.addrReg.enable = newRen
        memSeqRead.addrReg.updates.clear
        memSeqRead.addrReg.updates += ((newRen, newReadAddr))
      }
    }
  }*/
  
  preElaborateTransforms += ((top: Module) => levelChildren(top))
  preElaborateTransforms += ((top: Module) => {Module.sortedComps = gatherChildren(top).sortWith((x,y) => (x.level < y.level || (x.level == y.level && x.traversal < y.traversal)) )})
  preElaborateTransforms += ((top: Module) => collectNodesIntoComp(initializeDFS))
  preElaborateTransforms += ((top: Module) => findConsumerMap(top)) 
  preElaborateTransforms += ((top: Module) => driveOutputs())
  preElaborateTransforms += ((top: Module) => muxInputs())
  /*preElaborateTransforms += ((top: Module) => appendFireToRegWriteEnables(top))
  preElaborateTransforms += ((top: Module) => top.genAllMuxes)
  preElaborateTransforms += ((top: Module) => appendFireToMemEnables(top))
  preElaborateTransforms += ((top: Module) => collectNodesIntoComp(initializeDFS))
  preElaborateTransforms += ((top: Module) => top.genAllMuxes)*/

}

class Fame5CppBackend extends CppBackend with Fame5Transform
class Fame5VerilogBackend extends VerilogBackend with Fame5Transform
class Fame5FPGABackend extends FPGABackend with Fame5Transform