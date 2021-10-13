package fudian

import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode._
import fudian.utils._

class FarPath(val expWidth: Int, val precision: Int, val outPc: Int)
    extends Module {
  val io = IO(new Bundle() {
    val in = Input(new Bundle() {
      val a, b = new RawFloat(expWidth, precision)
      val expDiff = UInt(expWidth.W)
      val effSub = Bool()
      val smallAdd = Bool()
      val rm = UInt(3.W)
    })
    val out = Output(new Bundle() {
      val result = new RawFloat(expWidth, precision + 2)
      val tininess = Bool()
    })
  })

  val in = io.in
  val (a, b, expDiff, effSub, smallAdd) =
    (in.a, in.b, in.expDiff, in.effSub, in.smallAdd)

  // shamt <- [2, precision + 2]
  val (sig_b_main, sig_b_sticky) = ShiftRightJam(Cat(b.sig, 0.U(2.W)), expDiff)

  val adder_in_sig_b = Cat(0.U(1.W), sig_b_main, sig_b_sticky)
  val adder_in_sig_a = Cat(0.U(1.W), a.sig, 0.U(3.W))
  val adder_result =
    adder_in_sig_a +
      Mux(effSub, ~adder_in_sig_b, adder_in_sig_b).asUInt() + effSub

  val exp_a_plus_1 = a.exp + 1.U
  val exp_a_minus_1 = a.exp - 1.U

  val cout = adder_result.head(1).asBool()
  val keep = adder_result.head(2) === 1.U
  val cancellation = adder_result.head(2) === 0.U

  val far_path_sig = Mux1H(
    Seq(cout, keep || smallAdd, cancellation && !smallAdd),
    Seq(
      adder_result.head(precision + 1),
      Cat(adder_result.tail(1).head(precision + 1)),
      Cat(adder_result.tail(2).head(precision + 1))
    )
  )

  val far_path_sticky = Mux1H(
    Seq(cout, keep || smallAdd, cancellation && !smallAdd),
    Seq(
      adder_result.tail(precision + 1).orR(),
      adder_result.tail(precision + 2).orR(),
      adder_result.tail(precision + 3).orR()
    )
  )

  val far_path_exp = Mux1H(
    Seq(cout, keep, cancellation),
    Seq(exp_a_plus_1, a.exp, exp_a_minus_1)
  )

  val tininess_rounder = RoundingUnit(
    adder_result.tail(1).tail(2),
    io.in.rm,
    a.sign,
    outPc - 1
  )

  val tininess = smallAdd && (
    adder_result.tail(1).head(2) === "b00".U ||
      adder_result.tail(1).head(2) === "b01".U && !tininess_rounder.io.cout
  )

  io.out.tininess := tininess

  val result = Wire(new RawFloat(expWidth, precision + 2))
  result.sign := a.sign
  result.exp := far_path_exp
  result.sig := Cat(far_path_sig, far_path_sticky)
  io.out.result := result
}

class NearPath(val expWidth: Int, val precision: Int, val outPc: Int)
    extends Module {

  val io = IO(new Bundle() {
    val in = Input(new Bundle() {
      val a, b = new RawFloat(expWidth, precision)
      val need_shift_b = Bool()
      val rm = Input(UInt(3.W))
    })
    val out = Output(new Bundle() {
      val result = new RawFloat(expWidth, precision + 2)
      val sig_is_zero = Bool()
      val a_lt_b = Bool()
      val tininess = Bool()
    })
  })
  val (a, b) = (io.in.a, io.in.b)
  val need_shift = io.in.need_shift_b
  val a_sig = Cat(a.sig, 0.U(1.W))
  val b_sig = (Cat(b.sig, 0.U(1.W)) >> need_shift).asUInt()
  val b_neg = (~b_sig).asUInt()
  // extend 1 bit to get 'a_lt_b'
  val a_minus_b = Cat(0.U(1.W), a_sig) + Cat(1.U(1.W), b_neg) + 1.U
  val a_lt_b = a_minus_b.head(1).asBool()
  val sig_raw = a_minus_b.tail(1)
  val lza_ab = Module(new LZA(precision + 1))
  lza_ab.io.a := a_sig
  lza_ab.io.b := b_neg
  val lza_str = lza_ab.io.f
  val lza_str_zero = !Cat(lza_str).orR()

  // need to limit the shamt? (if a.exp is not large enough, a.exp-lzc may < 1)
  val need_shift_lim = a.exp < (precision + 1).U
  val mask_table_k_width = log2Up(precision + 1)
  val shift_lim_mask_raw = decoder(
    QMCMinimizer,
    a.exp(mask_table_k_width - 1, 0),
    TruthTable(
      (1 to precision + 1).map { i =>
        BitPat(i.U(mask_table_k_width.W)) -> BitPat(
          (BigInt(1) << (precision + 1 - i)).U((precision + 1).W)
        )
      },
      BitPat.dontCare(precision + 1)
    )
  )
  val shift_lim_mask = Mux(need_shift_lim, shift_lim_mask_raw, 0.U)
  val shift_lim_bit = (shift_lim_mask_raw & sig_raw).orR()

  val lzc_str = shift_lim_mask | lza_str
  val lzc = CLZ(lzc_str)

  val int_bit_mask = Cat((0 until precision + 1).reverseMap {
    case i @ `precision` => lzc_str(i)
    case i               => lzc_str(i) & !lzc_str.head(precision + 1 - i - 1).orR()
  })

  val int_bit_predicted =
    ((int_bit_mask | lza_str_zero) & sig_raw).orR()
  val int_bit_rshift_1 =
    ((int_bit_mask >> 1.U).asUInt() & sig_raw).orR()

  val exceed_lim_mask = Cat((0 until precision + 1).reverseMap {
    case `precision` => false.B
    case i           => lza_str.head(precision + 1 - i - 1).orR()
  })
  val exceed_lim =
    need_shift_lim && !(exceed_lim_mask & shift_lim_mask_raw).orR()

  val int_bit =
    Mux(exceed_lim, shift_lim_bit, int_bit_rshift_1 || int_bit_predicted)

  val lza_error = !int_bit_predicted && !exceed_lim
  val exp_s1 = a.exp - lzc
  val exp_s2 = exp_s1 - lza_error
  val sig_s1 = (sig_raw << lzc)(precision, 0)
  val sig_s2 = Mux(lza_error, Cat(sig_s1.tail(1), 0.U(1.W)), sig_s1)
  val near_path_sig = sig_s2
  val near_path_exp = Mux(int_bit, exp_s2, 0.U)
  val near_path_sign = Mux(a_lt_b, b.sign, a.sign)

  val tininess_rounder = RoundingUnit(
    sig_s2.tail(2),
    io.in.rm,
    near_path_sign,
    outPc - 1
  )
  val tininess = near_path_sig.head(2) === "b00".U ||
    near_path_sig.head(2) === "b01".U && !tininess_rounder.io.cout

  io.out.tininess := tininess

  val result = Wire(new RawFloat(expWidth, precision + 2))
  result.sign := near_path_sign
  result.exp := near_path_exp
  result.sig := Cat(near_path_sig, false.B) // 'sticky' always 0
  io.out.result := result
  io.out.sig_is_zero := lza_str_zero && !sig_raw(0)
  io.out.a_lt_b := a_lt_b
}

class FCMA_ADD(val expWidth: Int, val precision: Int, val outPc: Int)
    extends Module {

  val io = IO(new Bundle() {
    val a, b = Input(UInt((expWidth + precision).W))
    val b_inter_valid = Input(Bool())
    val b_inter_flags = Input(new FMULToFADD_fflags)
    val rm = Input(UInt(3.W))
    val result = Output(UInt((expWidth + outPc).W))
    val fflags = Output(UInt(5.W))
  })

  val fp_a = FloatPoint.fromUInt(io.a, expWidth, precision)
  val fp_b = FloatPoint.fromUInt(io.b, expWidth, precision)
  val decode_a = fp_a.decode
  val decode_b = fp_b.decode
  val raw_a = RawFloat.fromFP(fp_a, Some(decode_a.expNotZero))
  val raw_b = RawFloat.fromFP(fp_b, Some(decode_b.expNotZero))
  val eff_sub = raw_a.sign ^ raw_b.sign

  val small_add = decode_a.expIsZero && decode_b.expIsZero

  // deal with special cases
  val b_is_inter = io.b_inter_valid
  val b_flags = io.b_inter_flags
  val b_isNaN = Mux(b_is_inter, b_flags.isNaN, decode_b.isNaN)
  val b_isSNaN = Mux(b_is_inter, b_flags.isInv, decode_b.isSNaN)
  val b_isInf = Mux(b_is_inter, b_flags.isInf, decode_b.isInf)

  val special_path_hasNaN = decode_a.isNaN || b_isNaN
  val special_path_hasSNaN = decode_a.isSNaN || b_isSNaN
  val special_path_hasInf = decode_a.isInf || b_isInf
  val special_path_inf_iv = decode_a.isInf && b_isInf && eff_sub

  val special_case_happen = special_path_hasNaN || special_path_hasInf
  val special_path_result = Mux(
    special_path_hasNaN || special_path_inf_iv,
    FloatPoint.defaultNaNUInt(expWidth, outPc),
    Cat(
      Mux(decode_a.isInf, fp_a.sign, fp_b.sign),
      ~0.U(expWidth.W),
      0.U((outPc - 1).W)
    )
  )
  val special_path_iv = special_path_hasSNaN || special_path_inf_iv
  val special_path_fflags = Cat(special_path_iv, 0.U(4.W))

  val exp_diff_a_b = Cat(0.U(1.W), raw_a.exp) - Cat(0.U(1.W), raw_b.exp)
  val exp_diff_b_a = Cat(0.U(1.W), raw_b.exp) - Cat(0.U(1.W), raw_a.exp)
  // `b_overflow` means mul result is much bigger than a
  val need_swap = exp_diff_a_b.head(1).asBool() || b_flags.overflow

  val ea_minus_eb = Mux(need_swap, exp_diff_b_a.tail(1), exp_diff_a_b.tail(1))
  val sel_far_path = !eff_sub || ea_minus_eb > 1.U || b_flags.overflow

  /*
        Far path
   */

  val far_path_inputs = Seq(
    (
      Mux(!need_swap, raw_a, raw_b),
      Mux(!need_swap, raw_b, raw_a),
      Mux(!need_swap, exp_diff_a_b, exp_diff_b_a)
    )
  )

  val far_path_mods = far_path_inputs.map { in =>
    val far_path = Module(new FarPath(expWidth, precision, outPc))
    far_path.io.in.a := in._1
    far_path.io.in.b := in._2
    far_path.io.in.expDiff := in._3
    far_path.io.in.effSub := eff_sub
    far_path.io.in.smallAdd := small_add
    far_path.io.in.rm := io.rm
    far_path
  }

  val far_path_out = far_path_mods.head.io.out
  val far_path_res = far_path_out.result

  val far_path_exp = far_path_res.exp
  val far_path_sig = far_path_res.sig

  val far_path_rounder = RoundingUnit(
    in = far_path_sig.tail(1),
    rm = io.rm,
    sign = far_path_res.sign,
    width = outPc - 1
  )

  val far_path_exp_rounded = far_path_rounder.io.cout + far_path_exp
  val far_path_sig_rounded = far_path_rounder.io.out

  val far_path_mul_of = b_flags.overflow || (decode_b.expIsOnes && !eff_sub)
  val far_path_may_uf = far_path_out.tininess && !far_path_mul_of

  val far_path_of_before_round =
    far_path_exp === ((BigInt(1) << expWidth) - 1).U
  val far_path_of_after_round = far_path_rounder.io.cout &&
    far_path_exp === ((BigInt(1) << expWidth) - 2).U

  val far_path_of =
    far_path_of_before_round || far_path_of_after_round || far_path_mul_of
  val far_path_ix = far_path_rounder.io.inexact | far_path_of
  val far_path_uf = far_path_may_uf & far_path_ix

  val far_path_result =
    Cat(far_path_res.sign, far_path_exp_rounded, far_path_sig_rounded)

  /*
        Near path
   */

  val near_path_exp_neq = raw_a.exp(1, 0) =/= raw_b.exp(1, 0)

  val near_path_inputs = Seq(
    (raw_a, raw_b, near_path_exp_neq),
    (raw_b, raw_a, near_path_exp_neq)
  )
  val near_path_mods = near_path_inputs.map { in =>
    val near_path = Module(new NearPath(expWidth, precision, outPc))
    near_path.io.in.a := in._1
    near_path.io.in.b := in._2
    near_path.io.in.need_shift_b := in._3
    near_path.io.in.rm := io.rm
    near_path
  }

  val near_path_a_lt_b = near_path_mods.head.io.out.a_lt_b
  val near_path_out = Mux(
    need_swap || (!near_path_exp_neq && near_path_a_lt_b),
    near_path_mods.last.io.out,
    near_path_mods.head.io.out
  )

  val near_path_res = near_path_out.result
  val near_path_sign = near_path_res.sign
  val near_path_exp = near_path_res.exp
  val near_path_sig = near_path_res.sig
  val near_path_sig_zero = near_path_out.sig_is_zero
  val near_path_is_zero = near_path_exp === 0.U && near_path_sig_zero

  val near_path_rounder = RoundingUnit(
    in = near_path_sig.tail(1),
    rm = io.rm,
    sign = near_path_res.sign,
    width = outPc - 1
  )

  val near_path_exp_rounded = near_path_rounder.io.cout + near_path_exp
  val near_path_sig_rounded = near_path_rounder.io.out
  val near_path_zero_sign = io.rm === RDN
  val near_path_result = Cat(
    (near_path_sign && !near_path_is_zero) || (near_path_zero_sign && near_path_is_zero),
    near_path_exp_rounded,
    near_path_sig_rounded
  )

  val near_path_of = near_path_exp_rounded === (~0.U(expWidth.W)).asUInt()
  val near_path_ix = near_path_rounder.io.inexact || near_path_of
  val near_path_uf = near_path_out.tininess && near_path_ix

  /*
      Final result <- [special, far, near]
   */

  val common_overflow =
    sel_far_path && far_path_of || !sel_far_path && near_path_of
  val common_overflow_sign =
    Mux(sel_far_path, far_path_res.sign, near_path_res.sign)
  val rmin = RoundingUnit.is_rmin(io.rm, far_path_res.sign)
  val common_overflow_exp = Mux(
    rmin,
    ((BigInt(1) << expWidth) - 2).U(expWidth.W),
    ((BigInt(1) << expWidth) - 1).U(expWidth.W)
  )
  val common_overflow_sig =
    Mux(rmin, Fill(outPc - 1, 1.U(1.W)), 0.U((outPc - 1).W))
  val common_underflow =
    sel_far_path && far_path_uf || !sel_far_path && near_path_uf
  val common_inexact =
    sel_far_path && far_path_ix || !sel_far_path && near_path_ix
  val common_fflags = Cat(
    false.B,
    false.B,
    common_overflow,
    common_underflow,
    common_inexact
  )

  io.result := Mux(
    special_case_happen,
    special_path_result,
    Mux(
      common_overflow,
      Cat(common_overflow_sign, common_overflow_exp, common_overflow_sig),
      Mux(sel_far_path, far_path_result, near_path_result)
    )
  )
  io.fflags := Mux(special_case_happen, special_path_fflags, common_fflags)

}

class FADD(val expWidth: Int, val precision: Int) extends Module {
  val io = IO(new Bundle() {
    val a, b = Input(UInt((expWidth + precision).W))
    val rm = Input(UInt(3.W))
    val result = Output(UInt((expWidth + precision).W))
    val fflags = Output(UInt(5.W))
  })

  val module = Module(new FCMA_ADD(expWidth, precision, precision))

  module.io.a := io.a
  module.io.b := io.b
  module.io.rm := io.rm
  module.io.b_inter_valid := false.B
  module.io.b_inter_flags := DontCare
  io.result := module.io.result
  io.fflags := module.io.fflags
}
