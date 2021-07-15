package fudian.utils

import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode._

class CLZ(len: Int, zero: Boolean) extends Module {

  val inWidth = len
  val outWidth = (inWidth - 1).U.getWidth

  val io = IO(new Bundle() {
    val in = Input(UInt(inWidth.W))
    val out = Output(UInt(outWidth.W))
  })

  val normalTerms = Seq.tabulate(inWidth) { i =>
    BitPat("b" + ("0" * i) + "1" + ("?" * (inWidth - i - 1))) -> BitPat(
      i.U(outWidth.W)
    )
  }
  val zeroTerm = BitPat(0.U(inWidth.W)) -> BitPat((inWidth - 1).U(outWidth.W))
  val terms = if (zero) normalTerms :+ zeroTerm else normalTerms
  val table = TruthTable(terms, BitPat.dontCare(outWidth))
  io.out := decoder(QMCMinimizer, io.in, table)
}

object CLZ {
  def apply(value: UInt): UInt = {
    val clz = Module(new CLZ(value.getWidth, true))
    clz.io.in := value
    clz.io.out
  }
  def apply(xs: Seq[Bool]): UInt = {
    apply(Cat(xs.reverse))
  }
}
