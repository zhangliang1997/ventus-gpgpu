package pipeline

import chisel3._
import chisel3.util.{Cat, MuxLookup, is, switch}
import IDecode._
import parameters._

class RegFileBankIO extends Bundle  {
  val rs     = Output(UInt(xLen.W))
  val rsidx  = Input(UInt(depth_regBank.W))
  val rd     = Input(UInt(xLen.W))
  val rdidx  = Input(UInt(depth_regBank.W))
  val rdwen  = Input(Bool())
  //val ready  = Output(Bool())
}

class RegFileBank extends Module  {
  val io = IO(new RegFileBankIO())
  val regs = SyncReadMem(32*num_warp/num_bank, UInt(xLen.W))
  io.rs := Mux(((io.rsidx===io.rdidx)&io.rdwen),io.rd,Mux(RegNext(io.rsidx.orR), regs.read(io.rsidx), 0.U))
  //io.ready := true.B
  when (io.rdwen & io.rdidx.orR) {
    regs.write(io.rdidx, io.rd)
  }
}

class FloatRegFileBankIO(val unified: Boolean) extends Bundle  {
  val v0     = Output(Vec(num_thread,UInt((xLen).W)))//mask v0
  val rs     = Output(Vec(num_thread,UInt((xLen).W)))
  val rsidx  = Input(UInt(depth_regBank.W))
  val rd     = Input(Vec(num_thread,UInt((xLen).W)))
  val rdidx  = Input(UInt(depth_regBank.W))
  val rdwen  = Input(Bool())
  val rdwmask = Input(Vec(num_thread,Bool()))
  val rsType = if(unified) Some(Input(UInt(2.W))) else None
}
class FloatRegFileBank extends Module  {
  val io = IO(new FloatRegFileBankIO(false))
  val regs = SyncReadMem(32*num_warp/num_bank, Vec(num_thread,UInt(xLen.W)))  //Register files of all warps are divided to number of bank
  val internalMask = Wire(Vec(num_thread, Bool()))

  //  io.rs := regs.read(io.rsidx)
  io.rs := Mux(((io.rsidx===io.rdidx)&io.rdwen),io.rd,regs.read(io.rsidx))
  io.v0 := regs.read(0.U)
  internalMask:=io.rdwmask
  when (io.rdwen) {
    regs.write(io.rdidx, io.rd, internalMask)
  }
}
class unifiedBank extends Module  {
  val io = IO(new FloatRegFileBankIO(true))
  val regs = SyncReadMem(32*num_warp/num_bank, Vec(num_thread+1, UInt(xLen.W)))  //integrating scalar bank and vector bank, the most significant 32bit is scalar data
  val internalMask = Wire(Vec(num_thread, Bool()))
  switch(io.rsType.get){
    is(1.U){//scalar
      io.rs(0) := Mux(((io.rsidx===io.rdidx) & io.rdwen), io.rd(0), Mux(io.rsidx.orR, regs.read(io.rsidx)(num_thread+1), 0.U))
      when(io.rdwen & io.rdidx.orR) {
        regs(io.rdidx)(num_thread+1) := io.rd(0)
      }
    }
    is(2.U){//vector
      io.rs := Mux(((io.rsidx===io.rdidx) & io.rdwen), io.rd, VecInit(regs.read(io.rsidx).dropRight(1)))
      io.v0 := regs.read(0.U)
      internalMask:=io.rdwmask
      when (io.rdwen) {
        regs.write(io.rdidx, VecInit(io.rd++Seq.fill(1)(0.U(xLen.W))), internalMask)
        val x = regs(io.rdidx)
      }
    }
  }
}

class ImmGenIO extends Bundle {
  val inst = Input(UInt(32.W))
  val sel  = Input(UInt(3.W))
  val out  = Output(UInt(32.W))
}

class ImmGen extends Module {
  val io = IO(new ImmGenIO)

  val Iimm = io.inst(31, 20).asSInt // load, arithmetic, logic, jalr
  val Simm = Cat(io.inst(31, 25), io.inst(11, 7)).asSInt  // store
  val Bimm = Cat(io.inst(31), io.inst(7), io.inst(30, 25), io.inst(11, 8), 0.U(1.W)).asSInt // branch
  val Uimm = Cat(io.inst(31, 12), 0.U(12.W)).asSInt // lui, auipc
  val Jimm = Cat(io.inst(31), io.inst(19, 12), io.inst(20), io.inst(30, 21), 0.U(1.W)).asSInt // jal
  val Zimm = Cat(0.U(27.W),io.inst(19, 15)).asSInt // CSR I
  val Imm2 = io.inst(24,20).asSInt
  val Vimm = io.inst(19,15).asSInt

  val out = WireInit(0.S(32.W))

  out := MuxLookup(io.sel, Iimm & -2.S, Seq(IMM_I -> Iimm,IMM_J->Jimm, IMM_S -> Simm, IMM_B -> Bimm, IMM_U -> Uimm, IMM_2 -> Imm2,IMM_Z -> Zimm,IMM_V->Vimm))
  io.out:=out.asUInt
}