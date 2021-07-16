package fudian

import chisel3._
import chisel3.stage.{ChiselGeneratorAnnotation, ChiselStage}
import chisel3.util._
import chisel3.util.experimental.decode._
import fudian.utils._

class NearPath(val expWidth: Int, val precision: Int) extends Module {
  val io = IO(new Bundle() {
    val in = Input(new Bundle() {
      val a, b = new RawFloat(expWidth, precision)
      val need_shift_b = Bool()
    })
    val out = Output(new Bundle() {
      val result = new RawFloat(expWidth, precision + 2)
      val sig_is_zero = Bool()
      val a_lt_b = Bool()
    })
  })
  // we assue a >= b
  val (a, b) = (io.in.a, io.in.b)
  val need_shift = io.in.need_shift_b
  val a_sig = Cat(a.sig, 0.U(1.W))
  val b_sig = (Cat(b.sig, 0.U(1.W)) >> need_shift).asUInt()
  val b_neg = (~b_sig).asUInt()
  // extend 1 bit to get 'a_lt_b'
  val a_minus_b = Cat(0.U(1.W), a_sig) + Cat(1.U(1.W), b_neg) + 1.U
  val a_lt_b = a_minus_b.head(1).asBool()
  // we do not need carry out here
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
  val lzc_str = shift_lim_mask | lza_str
  val lzc = CLZ(lzc_str)
  val int_bit_mask = Wire(Vec(precision + 1, Bool()))
  for (i <- int_bit_mask.indices) {
    int_bit_mask(i) := {
      if (i == int_bit_mask.size - 1) {
        lzc_str(i)
      } else {
        lzc_str(i) & !lzc_str.head(int_bit_mask.size - i - 1).orR()
      }
    }
  }
  val exceed_lim_mask = Wire(Vec(precision + 1, Bool()))
  for (i <- exceed_lim_mask.indices) {
    exceed_lim_mask(i) := {
      if (i == exceed_lim_mask.size - 1) {
        false.B
      } else {
        lza_str.head(exceed_lim_mask.size - i - 1).orR()
      }
    }
  }

  val exceed_lim =
    need_shift_lim && !(exceed_lim_mask.asUInt() & shift_lim_mask).orR()
  val int_bit_predicted =
    ((int_bit_mask.asUInt() | lza_str_zero) & sig_raw).orR()
  val lza_error = !int_bit_predicted && !exceed_lim
  val int_bit = Mux(
    lza_error,
    ((int_bit_mask.asUInt() >> 1.U).asUInt() & sig_raw).orR(),
    int_bit_predicted
  )

  val exp_s1 = a.exp - lzc
  val exp_s2 = exp_s1 - lza_error
  val sig_s1 = (sig_raw << lzc)(precision, 0)
  val sig_s2 = Mux(lza_error, Cat(sig_s1.tail(1), 0.U(1.W)), sig_s1)
  val near_path_sig = sig_s2
  val near_path_exp = Mux(int_bit, exp_s2, 0.U)
  val near_path_sign = Mux(a_lt_b, b.sign, a.sign)

  val result = Wire(new RawFloat(expWidth, precision + 2))
  result.sign := near_path_sign
  result.exp := near_path_exp
  result.sig := Cat(near_path_sig, false.B) // 'sticky' always 0
  io.out.result := result
  io.out.sig_is_zero := lza_str_zero && !sig_raw(0)
  io.out.a_lt_b := a_lt_b
}

class FarPath(val expWidth: Int, val precision: Int) extends Module {
  val io = IO(new Bundle() {
    val in = Input(new Bundle() {
      val a, b = new RawFloat(expWidth, precision)
      val expDiff = UInt(expWidth.W)
      val effSub = Bool()
      val smallAdd = Bool()
    })
    val out = Output(new Bundle() {
      val result = new RawFloat(expWidth, precision + 2)
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

  val result = Wire(new RawFloat(expWidth, precision + 2))
  result.sign := a.sign
  result.exp := far_path_exp
  result.sig := Cat(far_path_sig, far_path_sticky)
  io.out.result := result
}

class FADD(val expWidth: Int, val precision: Int) extends Module {
  val io = IO(new Bundle() {
    val a, b = Input(UInt((expWidth + precision).W))
    val rm = Input(UInt(3.W))
    val do_sub = Input(Bool())
    val result = Output(UInt((expWidth + precision).W))
    val fflags = Output(UInt(5.W))
  })

  val fp_a = FloatPoint.fromUInt(io.a, expWidth, precision)
  val fp_b = FloatPoint.fromUInt(
    Cat(io.b.head(1) ^ io.do_sub, io.b.tail(1)),
    expWidth,
    precision
  )
  val decode_a = fp_a.decode
  val decode_b = fp_b.decode
  val raw_a = RawFloat.fromFP(fp_a, Some(decode_a.expNotZero))
  val raw_b = RawFloat.fromFP(fp_b, Some(decode_b.expNotZero))
  val eff_sub = raw_a.sign ^ raw_b.sign

  val small_add = decode_a.expIsZero && decode_b.expIsZero

  // deal with special cases
  val special_path_hasNaN = decode_a.isNaN || decode_b.isNaN
  val special_path_hasSNaN = decode_a.isSNaN || decode_b.isSNaN
  val special_path_hasInf = decode_a.isInf || decode_b.isInf
  val special_path_inf_iv = decode_a.isInf && decode_b.isInf && eff_sub

  val exp_diff_a_b = Cat(0.U(1.W), raw_a.exp) - Cat(0.U(1.W), raw_b.exp)
  val exp_diff_b_a = Cat(0.U(1.W), raw_b.exp) - Cat(0.U(1.W), raw_a.exp)
  val need_swap = exp_diff_a_b.head(1).asBool()

  val ea_minus_eb = Mux(need_swap, exp_diff_b_a.tail(1), exp_diff_a_b.tail(1))
  val sel_far_path = !eff_sub || ea_minus_eb > 1.U

  /*
        Far path
   */

  val far_path_inputs = Seq(
    (raw_a, raw_b, exp_diff_a_b),
    (raw_b, raw_a, exp_diff_b_a)
  )

  val far_path_mods = far_path_inputs.map { in =>
    val far_path = Module(new FarPath(expWidth, precision))
    far_path.io.in.a := in._1
    far_path.io.in.b := in._2
    far_path.io.in.expDiff := in._3
    far_path.io.in.effSub := eff_sub
    far_path.io.in.smallAdd := small_add
    far_path
  }

  val far_path_res = Mux1H(
    Seq(!need_swap, need_swap),
    far_path_mods.map(_.io.out.result)
  )
  val far_path_exp = far_path_res.exp
  val far_path_sig = far_path_res.sig

  val far_path_rounder = Module(new RoundingUnit(precision - 1))
  far_path_rounder.io.in := far_path_sig.tail(1).head(precision - 1)
  far_path_rounder.io.roundIn := far_path_sig(1)
  far_path_rounder.io.stickyIn := far_path_sig(0)
  far_path_rounder.io.signIn := far_path_res.sign
  far_path_rounder.io.rm := io.rm

  val far_path_exp_rounded = far_path_rounder.io.cout + far_path_exp
  val far_path_sig_rounded = far_path_rounder.io.out

  val far_path_may_uf = (far_path_exp === 0.U) && !far_path_rounder.io.cout
  val far_path_of = Mux(
    far_path_rounder.io.cout,
    far_path_exp === ((BigInt(1) << expWidth) - 2).U,
    far_path_exp === ((BigInt(1) << expWidth) - 1).U
  )
  val far_path_ix = far_path_rounder.io.inexact | far_path_of
  val far_path_uf = far_path_may_uf & far_path_ix

  val rmin =
    io.rm === RTZ || (io.rm === RDN && !far_path_res.sign) || (io.rm === RUP && far_path_res.sign)
  val far_path_result_exp = Mux(
    far_path_of && rmin,
    ((BigInt(1) << expWidth) - 2).U(expWidth.W),
    far_path_exp_rounded
  )
  val far_path_result_sig = Mux(
    far_path_of,
    Mux(rmin, Fill(precision - 1, 1.U(1.W)), 0.U((precision - 1).W)),
    far_path_sig_rounded
  )
  val far_path_result =
    Cat(far_path_res.sign, far_path_result_exp, far_path_result_sig)

  /*
        Near path
   */

  val near_path_inputs = Seq(
    (raw_a, raw_b, false.B),
    (raw_a, raw_b, true.B),
    (raw_b, raw_a, false.B),
    (raw_b, raw_a, true.B)
  )
  val near_path_mods = near_path_inputs.map { in =>
    val near_path = Module(new NearPath(expWidth, precision))
    near_path.io.in.a := in._1
    near_path.io.in.b := in._2
    near_path.io.in.need_shift_b := in._3
    near_path
  }
  val exp_eq = raw_a.exp === raw_b.exp
  /*
      exp_eq => (a - b, b - a)
      expa > expb => a - b_shift
      expb > expa => b - a_shift
   */
  val near_path_out = Mux1H(
    Seq(
      exp_eq && !near_path_mods.head.io.out.a_lt_b, // exp_eq && a_sig >= b_sig
      !exp_eq && !need_swap, // expa > expb
      exp_eq && near_path_mods.head.io.out.a_lt_b, // exp_eq && a_sig < b_sig
      need_swap // expb > expa
    ),
    near_path_mods.map(_.io.out)
  )

  val near_path_res = near_path_out.result
  val near_path_sign = near_path_res.sign
  val near_path_exp = near_path_res.exp
  val near_path_sig = near_path_res.sig
  val near_path_sig_zero = near_path_out.sig_is_zero
  val near_path_is_zero = near_path_exp === 0.U && near_path_sig_zero

  val near_path_rounder = Module(new RoundingUnit(precision - 1))
  near_path_rounder.io.in := near_path_sig.tail(1).head(precision - 1)
  near_path_rounder.io.signIn := near_path_res.sign
  near_path_rounder.io.roundIn := near_path_sig(1)
  near_path_rounder.io.stickyIn := false.B
  near_path_rounder.io.rm := io.rm

  val near_path_exp_rounded = near_path_rounder.io.cout + near_path_exp
  val near_path_sig_rounded = near_path_rounder.io.out
  val near_path_zero_sign = io.rm === RDN
  val near_path_result = Cat(
    (near_path_sign && !near_path_is_zero) || (near_path_zero_sign && near_path_is_zero),
    near_path_exp_rounded,
    near_path_sig_rounded
  )

  val near_path_ix = near_path_rounder.io.inexact
  val near_path_may_uf = (near_path_exp === 0.U) && !near_path_rounder.io.cout
  val near_path_uf = near_path_may_uf && near_path_ix

  /*
      Final result <- [special, far, near]
   */

  val iv = special_path_hasSNaN || special_path_inf_iv
  val dz = false.B
  val of = Mux(
    special_path_hasNaN || special_path_hasInf,
    false.B,
    sel_far_path && far_path_of
  )
  val uf = Mux(
    special_path_hasNaN || special_path_hasInf,
    false.B,
    (sel_far_path && far_path_uf) || (!sel_far_path && near_path_uf)
  )
  val ix = Mux(
    special_path_hasNaN || special_path_hasInf,
    false.B,
    (sel_far_path && far_path_ix) || (!sel_far_path && near_path_ix)
  )

  io.result := Mux(
    special_path_hasNaN || special_path_inf_iv,
    FloatPoint.defaultNaNUInt(expWidth, precision),
    Mux(
      special_path_hasInf,
      Mux1H(
        Seq(
          decode_a.isInf -> fp_a.asUInt(),
          decode_b.isInf -> fp_b.asUInt()
        )
      ),
      Mux1H(
        Seq(
          sel_far_path -> far_path_result,
          !sel_far_path -> near_path_result
        )
      )
    )
  )
  io.fflags := Cat(iv, dz, of, uf, ix)
}

object FADD extends App {
  override def main(args: Array[String]): Unit = {
    // arg fmt: -td ... 32 / -td ... 64
    val (expWidth, precision) = args.last match {
      case "32" =>
        (8, 24)
      case "64" =>
        (11, 53)
      case _ =>
        println("usage: runMain fudian.FADD -td <build dir> <ftype>")
        sys.exit(-1)
    }
    (new ChiselStage).execute(
      args,
      Seq(
        ChiselGeneratorAnnotation(() => new FADD(expWidth, precision))
      )
    )
  }
}
