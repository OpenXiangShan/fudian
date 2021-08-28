package fudian

import chisel3._
import chisel3.util._
import fudian.utils.CLZ

class FMULToFADD_fflags extends Bundle {
  val isNaN = Bool()
  val isInf = Bool()
  val isInv = Bool()
  val overflow = Bool()
}

class FMULToFADD(val expWidth: Int, val precision: Int) extends Bundle {
  val fp_prod = new FloatPoint(expWidth, 2 * precision)
  val inter_flags = new FMULToFADD_fflags
}

class FMUL(val expWidth: Int, val precision: Int) extends Module {

  val io = IO(new Bundle() {
    val a, b = Input(UInt((expWidth + precision).W))
    val rm = Input(UInt(3.W))
    val result = Output(UInt((expWidth + precision).W))
    val fflags = Output(UInt(5.W))
    val to_fadd = Output(new FMULToFADD(expWidth, precision))
  })

  val fp_a = FloatPoint.fromUInt(io.a, expWidth, precision)
  val fp_b = FloatPoint.fromUInt(io.b, expWidth, precision)
  val (decode_a, decode_b) = (fp_a.decode, fp_b.decode)
  val raw_a = RawFloat.fromFP(fp_a, Some(decode_a.expNotZero))
  val raw_b = RawFloat.fromFP(fp_b, Some(decode_b.expNotZero))

  val prod_sign = fp_a.sign ^ fp_b.sign

  /*
      prod = xx.xxx...xxx
      sig_pre_shift = precision | g | s | prod
      padding = precision | g | s
      paddingBits = precision + 2
      if prod <- [2, 4):
        prod_exp = a.exp + b.exp - bias + paddingBits + 1
      if prod <- [1, 2):
        prod_exp = a.exp + b.exp - bias + paddingBits
      we assume product <- [2, 4) at first
   */
  val paddingBits = precision + 2
  val padding = 0.U(paddingBits.W)
  val biasInt = FloatPoint.expBias(expWidth)
  require(biasInt > paddingBits)
  val exp_sum = raw_a.exp +& raw_b.exp
  val prod_exp = exp_sum - (biasInt - (paddingBits + 1)).U

  val shift_lim_sub = Cat(0.U(1.W), exp_sum) - (biasInt - paddingBits).U
  val prod_exp_uf = shift_lim_sub.head(1).asBool()
  val shift_lim = shift_lim_sub.tail(1)
  // ov <=> exp_a + exp_b - bias > max_exp
  val prod_exp_ov = exp_sum >
    (FloatPoint.maxNormExp(expWidth)+ FloatPoint.expBias(expWidth)).U

  val subnormal_sig = Mux(decode_a.expIsZero, raw_a.sig, raw_b.sig)
  val lzc = CLZ(Cat(padding, subnormal_sig))
  val exceed_lim = shift_lim <= lzc
  val shift_amt = Mux(prod_exp_uf, 0.U, Mux(exceed_lim, shift_lim, lzc))

  val prod = raw_a.sig * raw_b.sig
  val sig_shifter_in = Cat(padding, prod)
  val sig_shifted_raw = (sig_shifter_in << shift_amt)(paddingBits + 2 * precision - 1, 0)
  val exp_shifted = prod_exp - shift_amt
  val exp_is_subnormal = (exceed_lim || prod_exp_uf) && !sig_shifted_raw.head(1).asBool()
  val no_extra_shift = sig_shifted_raw.head(1).asBool() || exp_is_subnormal

  val exp_pre_round = Mux(exp_is_subnormal, 0.U, Mux(no_extra_shift, exp_shifted, exp_shifted - 1.U))
  val sig_shifted = Mux(no_extra_shift, sig_shifted_raw, Cat(sig_shifted_raw.tail(1), 0.U(1.W)))

  val tininess_rounder = RoundingUnit(
    sig_shifted.tail(2),
    io.rm,
    prod_sign,
    precision - 1
  )

  val tininess = sig_shifted.head(2) === "b00".U(2.W) ||
    (sig_shifted.head(2) === "b01".U(2.W) && !tininess_rounder.io.cout)

  val rounder = RoundingUnit(
    sig_shifted.tail(1),
    io.rm,
    prod_sign,
    precision - 1
  )

  val exp_rounded = rounder.io.cout + exp_pre_round
  val sig_rounded = rounder.io.out

  val common_of = Mux(
    rounder.io.cout,
    exp_pre_round === ((BigInt(1) << expWidth) - 2).U,
    exp_pre_round === ((BigInt(1) << expWidth) - 1).U
  ) || prod_exp_ov
  val common_ix = rounder.io.inexact | common_of
  val common_uf = tininess & common_ix

  val rmin = RoundingUnit.is_rmin(io.rm, prod_sign)

  val of_exp = Mux(rmin,
    ((BigInt(1) << expWidth) - 2).U(expWidth.W),
    ((BigInt(1) << expWidth) - 1).U(expWidth.W)
  )
  val common_exp = Mux(
    common_of,
    of_exp,
    exp_rounded(expWidth - 1, 0)
  )
  val common_sig = Mux(
    common_of,
    Mux(rmin, Fill(precision - 1, 1.U(1.W)), 0.U((precision - 1).W)),
    sig_rounded
  )
  val common_result =
    Cat(prod_sign, common_exp, common_sig)

  val common_fflags = Cat(false.B, false.B, common_of, common_uf, common_ix)

  /*
      Special cases
   */
  val hasZero = decode_a.isZero || decode_b.isZero
  val hasNaN = decode_a.isNaN || decode_b.isNaN
  val hasSNaN = decode_a.isSNaN || decode_b.isSNaN
  val hasInf = decode_a.isInf || decode_b.isInf
  val special_case_happen = hasZero || hasNaN || hasInf

  val zero_mul_inf = hasZero && hasInf
  val nan_result = hasNaN || zero_mul_inf

  val inf_of = !prod_sign && hasInf

  val special_iv = hasSNaN || zero_mul_inf
  val special_of = !hasNaN && inf_of

  val special_result = Mux(nan_result,
    FloatPoint.defaultNaNUInt(expWidth, precision), // default NaN
    Mux(hasInf,
      Cat(prod_sign, ((BigInt(1) << expWidth) - 1).U(expWidth.W), 0.U((precision - 1).W)), // inf
      Cat(prod_sign, 0.U((expWidth + precision - 1).W)) // zero
    )
  )
  val special_fflags = Cat(special_iv, false.B, false.B, false.B, false.B)

  io.result := Mux(special_case_happen, special_result, common_result)
  io.fflags := Mux(special_case_happen, special_fflags, common_fflags)

  io.to_fadd.fp_prod.sign := prod_sign
  io.to_fadd.fp_prod.exp := Mux(hasZero, 0.U, exp_pre_round)
  io.to_fadd.fp_prod.sig := Mux(hasZero,
    0.U,
    sig_shifted.tail(1).head(2 * precision - 1) | sig_shifted.tail(2 * precision).orR()
  )
  io.to_fadd.inter_flags.isInv := special_iv
  io.to_fadd.inter_flags.isInf := hasInf && !nan_result
  io.to_fadd.inter_flags.isNaN := nan_result
  io.to_fadd.inter_flags.overflow := exp_pre_round > Fill(expWidth, 1.U(1.W))
}
