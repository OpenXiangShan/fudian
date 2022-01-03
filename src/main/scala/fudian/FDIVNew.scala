/*
    This file is originally written by Yifei He(hyf_sysu@qq.com)
    https://github.com/OpenXiangShan/XS-Verilog-Library/blob/main/fpdiv_scalar/rtl/radix_2_csa.sv
 */


package fudian

import chisel3._
import chisel3.experimental.{IntParam, Param}
import chisel3.util._
import fudian.utils.CLZ

class radix_2_qds extends BlackBox with HasBlackBoxResource {
  val rem_sum_msb_i = IO(Input(UInt(3.W)))
  val rem_carry_msb_i = IO(Input(UInt(3.W)))
  val quo_dig_o = IO(Output(UInt(2.W)))
  addResource("/radix_2_qds.sv")
}

class radix_2_csa(params: Map[String, IntParam]) extends BlackBox(params) with HasBlackBoxResource {
  val WIDTH = params("WIDTH").value.toInt
  val csa_plus_i = IO(Input(UInt((WIDTH - 4).W)))
  val csa_minus_i = IO(Input(UInt((WIDTH - 4).W)))
  val rem_sum_i = IO(Input(UInt((WIDTH - 2).W)))
  val rem_carry_i = IO(Input(UInt((WIDTH - 2).W)))
  val rem_sum_zero_o = IO(Input(UInt((WIDTH - 2).W)))
  val rem_carry_zero_o = IO(Input(UInt((WIDTH - 2).W)))
  val rem_sum_minus_o = IO(Input(UInt((WIDTH - 2).W)))
  val rem_carry_minus_o = IO(Input(UInt((WIDTH - 2).W)))
  val rem_sum_plus_o = IO(Input(UInt((WIDTH - 2).W)))
  val rem_carry_plus_o = IO(Input(UInt((WIDTH - 2).W)))
  addResource("/radix_2_csa.sv")
}

case class FPFmt(expWidth: Int, sigWidth: Int){
  val precision = sigWidth + 1
  val width = precision + expWidth
}

class FDIVNew extends Module {

  val io = IO(new Bundle {
    val start_valid = Input(Bool())
    val start_ready = Output(Bool())
    val flush = Input(Bool())
    val fmt = Input(UInt(2.W))
    val a = Input(UInt(64.W))
    val b = Input(UInt(64.W))
    val rm = Input(UInt(3.W))
    val finish_valid = Output(Bool())
    val finish_ready = Input(Bool())
    val result = Output(UInt(64.W))
    val fflags = Output(UInt(5.W))
  })

  val f16 = FPFmt(5, 10)
  val f32 = FPFmt(8, 23)
  val f64 = FPFmt(11, 52)

  val supported_types = Seq(f16, f32, f64)
  val max_t = supported_types.last

  // internal width
  // sign | sig | 0
  val REM_W = 1 + supported_types.map(_.precision).max + 1

  val sig_rem_sum_reg = Reg(UInt(REM_W.W))
  val sig_rem_carry_reg = Reg(UInt(REM_W.W))
  // Also use this reg to remember "sig_rem" generated in post_0, so we can get sticky_bit in post_1
  // Since sig_rem must be positive, so [REM_W-1] bit is enough.
  val sig_divisor_reg = Reg(UInt((REM_W - 1).W))


  /*********************************************************************************************************************
   Div state machine
   ********************************************************************************************************************/

  val FSM_W = 5
  def def_fsm_one_hot(i: Int) = {
    require(i < FSM_W)
    (1 << i).U(FSM_W.W)
  }

  val FSM_PRE_0 = def_fsm_one_hot(0)
  val FSM_PRE_1 = def_fsm_one_hot(1)
  val FSM_ITER = def_fsm_one_hot(2)
  val FSM_POST_0 = def_fsm_one_hot(3)
  val FSM_POST_1 = def_fsm_one_hot(4)

  def is_state(s: UInt): Bool = {
    // `s` is a one-hot lit val, dc will optimize this
    (state & s).orR
  }

  val state = RegInit(FSM_PRE_0)

  val early_finish = Wire(Bool())
  val skip_iter = Wire(Bool())
  val final_iter = Wire(Bool())

  state := Mux1H(Seq(
    FSM_PRE_0 -> Mux(io.start_valid, Mux(early_finish, FSM_POST_1, FSM_PRE_1), FSM_PRE_0),
    FSM_PRE_1 -> Mux(skip_iter, FSM_POST_0, FSM_ITER),
    FSM_ITER -> Mux(final_iter, FSM_POST_0, FSM_ITER),
    FSM_POST_0 -> FSM_POST_1,
    FSM_POST_1 -> Mux(io.finish_ready, FSM_PRE_0, FSM_POST_1)
  ).map{
    case (k, v) => is_state(k) -> v
  })
  when(io.flush){
    state := FSM_PRE_0
  }

  io.start_ready := is_state(FSM_PRE_0)
  val start_fire = io.start_valid && io.start_ready
  io.finish_valid := is_state(FSM_POST_1)

  val fmt_w = io.fmt
  val fmt_r = RegEnable(io.fmt, start_fire)
  implicit class FmtHelper[T <: Data](seq: Seq[T]) {
    // assume elts in `seq` are given in same order with `supported_types`
    // if inputs' width are not equal, the max width will be returned
    def select(fmt: UInt): T = {
      require(seq.size == supported_types.size)
      val sel = supported_types.indices.map(i => i.U === fmt)
      Mux1H(sel, seq)
    }
    def select_w = select(fmt_w)
    def select_r = select(fmt_r)
  }

  /*********************************************************************************************************************
   Pre Process (PRE0)
   ********************************************************************************************************************/
  class PreProcessResult extends Bundle {
    val out_sign = Bool()
    val res_is_nan = Bool()
    val res_is_inf = Bool()
    val res_is_exact_zero = Bool()
    val b_is_pow2 = Bool()
    val invalid_div = Bool()
    val divided_by_zero = Bool()
    val a_l_shift_num, b_l_shift_num = UInt(log2Ceil(supported_types.map(_.precision).max).W)
    val rm = UInt(3.W)
  }

  val pre_0 = Wire(new PreProcessResult)
  pre_0.rm := io.rm

  val fp_a_seq = supported_types.map(t => FloatPoint.fromUInt(
    io.a(t.width - 1, 0), t.expWidth, t.precision
  ))
  val fp_b_seq = supported_types.map(t => FloatPoint.fromUInt(
    io.b(t.width - 1, 0), t.expWidth, t.precision
  ))

  val a_decode = fp_a_seq.map(_.decode).select_w
  val b_decode = fp_b_seq.map(_.decode).select_w

  val raw_a_seq = fp_a_seq.map(fp => RawFloat.fromFP(fp, Some(a_decode.expNotZero)))
  val raw_b_seq = fp_b_seq.map(fp => RawFloat.fromFP(fp, Some(b_decode.expNotZero)))

  val a_sign = raw_a_seq.map(_.sign).select_w
  val a_exp_biased = raw_a_seq.map(_.exp).select_w
  val a_sig = raw_a_seq.map(_.sig).select_w

  val b_sign = raw_b_seq.map(_.sign).select_w
  val b_exp_biased = raw_b_seq.map(_.exp).select_w
  val b_sig = raw_b_seq.map(_.sig).select_w

  pre_0.invalid_div := (a_decode.isInf & b_decode.isInf) |
    (a_decode.isZero & b_decode.isZero) |
    a_decode.isNaN |
    b_decode.isNaN

  pre_0.res_is_nan := a_decode.isNaN | b_decode.isNaN | pre_0.invalid_div
  pre_0.res_is_inf := a_decode.isInf | b_decode.isZero
  pre_0.res_is_exact_zero := a_decode.isZero | b_decode.isInf

  pre_0.b_is_pow2 := b_decode.sigIsZero & !pre_0.res_is_nan
  pre_0.divided_by_zero := !pre_0.res_is_nan && !a_decode.isInf && b_decode.isZero

  def left_align(seq: Seq[UInt]): Seq[UInt] = {
    val maxW = seq.map(_.getWidth).max
    seq.map(elt =>{
      val delta = maxW - elt.getWidth
      if(delta > 0) elt ## 0.U(delta.W) else elt
    })
  }

  // assume input is subnormal because only subnormal need left shift
  val a_sig_pre_shifted = 0.U(1.W) ## left_align(fp_a_seq.map(_.sig)).select_w
  val b_sig_pre_shifted = 0.U(1.W) ## left_align(fp_b_seq.map(_.sig)).select_w
  val a_l_shift_num_pre = CLZ(a_sig_pre_shifted)
  val b_l_shift_num_pre = CLZ(b_sig_pre_shifted)

  pre_0.a_l_shift_num := Mux(a_decode.expIsZero, a_l_shift_num_pre, 0.U)
  pre_0.b_l_shift_num := Mux(b_decode.expIsZero, b_l_shift_num_pre, 0.U)

  val a_exp_plus_biased = Cat(0.U(1.W), a_exp_biased) +
    supported_types.map(t => FloatPoint.expBias(t.expWidth).U).select_w
  val exp_diff = Cat(0.U(1.W), a_exp_plus_biased) -
    pre_0.a_l_shift_num -
    b_exp_biased +
    pre_0.b_l_shift_num

  pre_0.out_sign := Mux(pre_0.res_is_nan, false.B, a_sign ^ b_sign)

  // Pre-process finish, save result in regs
  val pre_0_reg = RegEnable(pre_0, start_fire)
  when(start_fire){
    sig_rem_sum_reg := Cat(0.U(2.W), 1.U(1.W), a_sig_pre_shifted(max_t.precision - 2, 0))
    sig_rem_carry_reg := Cat(0.U(2.W), 1.U(1.W), b_sig_pre_shifted(max_t.precision - 2, 0))
  }


  /*********************************************************************************************************************
   PRE1
   ********************************************************************************************************************/

  // Shift sig to handle subnormal number(if exist)
  // The MSB must be 1
  val a_sig_l_shifted = Cat(
    true.B,
    (sig_rem_sum_reg(max_t.precision - 2, 0) << pre_0_reg.a_l_shift_num)(max_t.precision - 2, 0)
  )
  val b_sig_l_shifted = Cat(
    true.B,
    (sig_rem_carry_reg(max_t.precision - 2, 0) << pre_0_reg.b_l_shift_num)(max_t.precision - 2, 0)
  )
  val sig_rem_sum_iter_init = Cat(false.B, a_sig_l_shifted, true.B)
  val sig_rem_carry_iter_init = Cat(true.B, ~b_sig_l_shifted, true.B)
  val sig_divisor_iter_init = Cat(0.U(2.W), b_sig_l_shifted(max_t.precision - 2, 0))

  when(is_state(FSM_PRE_1)){

    sig_rem_sum_reg := sig_rem_sum_iter_init
    sig_rem_carry_reg := sig_rem_carry_iter_init
    sig_divisor_reg := sig_divisor_iter_init
  }

  /*********************************************************************************************************************
   ITER
   ********************************************************************************************************************/

  // 3 stages are overlapped during iteration
  val OVERLAPS = 3
  val iter_num_needed = supported_types.map(t => (t.precision + 1) / OVERLAPS)
  val iter_num_reg = Reg(UInt(log2Ceil(iter_num_needed.max).W))

  when(is_state(FSM_PRE_1) || is_state(FSM_ITER)){
    iter_num_reg := Mux1H(Seq(
      is_state(FSM_PRE_1) -> iter_num_needed.map(_.U).select_r,
      is_state(FSM_ITER) -> (iter_num_reg - 1.U)
    ))
  }

  final_iter := iter_num_reg === 0.U

  val div_csa_val = Wire(Vec(OVERLAPS, UInt(REM_W.W)))
  val quo_dig = Wire(Vec(OVERLAPS, UInt(2.W)))
  val quo_dig_zero = Wire(Vec(OVERLAPS, UInt(2.W)))
  val quo_dig_plus = Wire(Vec(OVERLAPS, UInt(2.W)))
  val quo_dig_minus = Wire(Vec(OVERLAPS, UInt(2.W)))

  for((v, quo) <- div_csa_val.zip(quo_dig)){
    val divisor = Cat(false.B, true.B, sig_divisor_reg(max_t.precision - 2, 0), false.B)
    val minus = Fill(REM_W, quo(0)) & (~divisor).asUInt
    val plus = Fill(REM_W, quo(1)) & divisor
    v := minus | plus
  }

  val SPECULATIVE_HIGH_W = OVERLAPS * 2
  val SPECULATIVE_LOW_W = REM_W - SPECULATIVE_LOW_W

  val sig_rem_sum_in = Wire(Vec(OVERLAPS, UInt(3.W)))
  val sig_rem_carry_in = Wire(Vec(OVERLAPS, UInt(3.W)))
  val sig_rem_sum_out = Wire(Vec(OVERLAPS, UInt(3.W)))
  val sig_rem_carry_out = Wire(Vec(OVERLAPS, UInt(3.W)))

  val sig_rem_sum_out_low = Wire(Vec(OVERLAPS, UInt(SPECULATIVE_LOW_W.W)))
  val sig_rem_carry_out_low = Wire(Vec(OVERLAPS, UInt(SPECULATIVE_LOW_W.W)))

  val sig_rem_sum_out_high = Wire(Vec(OVERLAPS, UInt(SPECULATIVE_HIGH_W.W)))
  val sig_rem_carry_out_high = Wire(Vec(OVERLAPS, UInt(SPECULATIVE_HIGH_W.W)))
  val sig_rem_sum_out_high_zero = Wire(Vec(OVERLAPS, UInt(SPECULATIVE_HIGH_W.W)))
  val sig_rem_carry_out_high_zero = Wire(Vec(OVERLAPS, UInt(SPECULATIVE_HIGH_W.W)))
  val sig_rem_sum_out_high_minus = Wire(Vec(OVERLAPS, UInt(SPECULATIVE_HIGH_W.W)))
  val sig_rem_carry_out_high_minus = Wire(Vec(OVERLAPS, UInt(SPECULATIVE_HIGH_W.W)))
  val sig_rem_sum_out_high_plus = Wire(Vec(OVERLAPS, UInt(SPECULATIVE_HIGH_W.W)))
  val sig_rem_carry_out_high_plus = Wire(Vec(OVERLAPS, UInt(SPECULATIVE_HIGH_W.W)))

  for(i <- 0 until OVERLAPS){
    if(i == 0){
      val qds = Module(new radix_2_qds)
      qds.rem_sum_msb_i := sig_rem_sum_reg.head(3)
      qds.rem_carry_msb_i := sig_rem_carry_reg.head(3)
      quo_dig(i) := qds.quo_dig_o
      quo_dig_zero(i) := DontCare
      quo_dig_plus(i) := DontCare
      quo_dig_minus(i) := DontCare
    } else {
      // start 0, -1, +1 speculatively
      val qds_zero, qds_minus, qds_plus = Module(new radix_2_qds)
      val qds_seq = Seq(qds_zero, qds_minus, qds_plus)
      val sum_msb_seq = Seq(
        sig_rem_sum_out_high_zero, sig_rem_sum_out_high_minus, sig_rem_sum_out_high_plus
      ).map(_(i - 1))
      val carry_msb_seq = Seq(
        sig_rem_carry_out_high_zero, sig_rem_carry_out_high_minus, sig_rem_carry_out_high_plus
      ).map(_(i - 1))
      val quo_seq = Seq(quo_dig_zero, quo_dig_minus, quo_dig_plus).map(_(i))
      for((((qds, sum), carry), quo) <- qds_seq.zip(sum_msb_seq).zip(carry_msb_seq).zip(quo_seq)){
        qds.rem_sum_msb_i := sum.head(3)
        qds.rem_carry_msb_i := carry.head(3)
        quo := qds.quo_dig_o
      }
      quo_dig(i) := Mux(quo_dig(i - 1)(0),
        quo_dig_minus(i),
        Mux(quo_dig(i - 1)(1), quo_dig_plus(i), quo_dig_zero(i))
      )
    }
  }

  for((next_in, prev_out) <- sig_rem_sum_in.zip(sig_rem_sum_reg +: sig_rem_sum_out.init)){
    next_in := prev_out
  }
  for((next_in, prev_out) <- sig_rem_carry_in.zip(sig_rem_carry_reg +: sig_rem_carry_out.init)){
    next_in := prev_out
  }

  for(i <- 0 until OVERLAPS){
    sig_rem_sum_out_low(i) :=
      (sig_rem_sum_in(i)(SPECULATIVE_LOW_W - 2, 0) ## false.B) ^
        (sig_rem_carry_in(i)(SPECULATIVE_LOW_W - 2, 0) ## false.B) ^
        div_csa_val(i)(SPECULATIVE_LOW_W - 1, 0)
    sig_rem_carry_out_low(i) := Cat(
      (sig_rem_sum_in(i)(SPECULATIVE_LOW_W - 2, 0) & sig_rem_carry_in(i)(SPECULATIVE_LOW_W - 2, 0)) |
        (sig_rem_sum_in(i)(SPECULATIVE_LOW_W - 2, 0) & div_csa_val(i)(SPECULATIVE_LOW_W - 2, 1)) |
        (sig_rem_carry_in(i)(SPECULATIVE_LOW_W - 2, 0) & div_csa_val(i)(SPECULATIVE_LOW_W - 2, 1)),
      false.B
    )
    val csa = Module(new radix_2_csa(Map("WIDTH" -> IntParam(SPECULATIVE_HIGH_W + 1))))
    val divisor = sig_divisor_reg(max_t.precision - 2, 0)
    val sum_in = sig_rem_sum_in(i)(REM_W - 2, 0)
    val carry_in = sig_rem_carry_in(i)(REM_W - 2, 0)
    csa.csa_plus_i := divisor.head(SPECULATIVE_HIGH_W - 2)
    csa.csa_minus_i := divisor.head(SPECULATIVE_HIGH_W - 2)
    csa.rem_sum_i := sum_in.head(SPECULATIVE_HIGH_W)
    csa.rem_carry_i := carry_in.head(SPECULATIVE_HIGH_W)
    sig_rem_sum_out_high_zero(i) := csa.rem_sum_zero_o(SPECULATIVE_HIGH_W - 1, 0)
    sig_rem_carry_out_high_zero(i) := csa.rem_carry_zero_o(SPECULATIVE_HIGH_W - 1, 0)
    sig_rem_sum_out_high_minus(i) := csa.rem_sum_minus_o(SPECULATIVE_HIGH_W - 1, 0)
    sig_rem_carry_out_high_minus(i) := csa.rem_carry_minus_o(SPECULATIVE_HIGH_W - 1, 0)
    sig_rem_sum_out_high_plus(i) := csa.rem_sum_plus_o(SPECULATIVE_HIGH_W - 1, 0)
    sig_rem_carry_out_high_plus(i) := csa.rem_carry_plus_o(SPECULATIVE_HIGH_W - 1, 0)

    sig_rem_sum_out_high(i) := Mux(quo_dig(i)(0),
      sig_rem_sum_out_high_minus(i),
      Mux(quo_dig(i)(1), sig_rem_sum_out_high_plus(i), sig_rem_sum_out_high_zero(i))
    )
    sig_rem_carry_out_high(i) := Mux(quo_dig(i)(0),
      sig_rem_carry_out_high_minus(i),
      Mux(quo_dig(i)(1), sig_rem_carry_out_high_plus(i), sig_rem_carry_out_high_zero(i))
    )

    sig_rem_sum_out(i) := Cat(sig_rem_sum_out_high(i), sig_rem_sum_out_low(i))
    sig_rem_carry_out(i) := Cat(sig_rem_carry_out_high(i), sig_rem_carry_out_low(i))
  }






}
