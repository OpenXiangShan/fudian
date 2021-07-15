package fudian

import chisel3._
import chisel3.util._

class RoundingUnit(val width: Int) extends Module {
  val io = IO(new Bundle() {
    val in = Input(UInt(width.W))
    val roundIn = Input(Bool())
    val stickyIn = Input(Bool())
    val signIn = Input(Bool())
    val rm = Input(UInt(3.W))
    val out = Output(UInt(width.W))
    val inexact = Output(Bool())
    val cout = Output(Bool())
  })

  val (g, r, s) = (io.in(0).asBool(), io.roundIn, io.stickyIn)
  val inexact = r | s
  val r_up = MuxLookup(
    io.rm,
    false.B,
    Seq(
      RNE -> ((r && s) || (r && !s && g)),
      RTZ -> false.B,
      RUP -> (inexact & !io.signIn),
      RDN -> (inexact & io.signIn),
      RMM -> r
    )
  )
  val out_r_up = io.in + 1.U
  io.out := Mux(r_up, out_r_up, io.in)
  io.inexact := inexact
  // r_up && io.in === 111...1
  io.cout := r_up && io.in.andR()
}
