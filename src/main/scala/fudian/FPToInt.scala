package fudian

import chisel3._
import chisel3.util._
import fudian.utils.ShiftRightJam

/**
  *  op: 00 => f -> wu
  *      01 => f -> w
  *      10 => f -> lu
  *      11 => f -> l
  */
class FPToInt(val expWidth: Int, val precision: Int) extends Module {
  val io = IO(new Bundle() {
    val a = Input(UInt((expWidth + precision).W))
    val rm = Input(UInt(3.W))
    val op = Input(UInt(2.W))
    val result = Output(UInt((expWidth + precision).W))
    val fflags = Output(UInt(5.W))
  })

}
