package fudian

import chisel3.stage.ChiselGeneratorAnnotation
import circt.stage.ChiselStage

object FCMAMain extends App {
  (new ChiselStage).execute(
    args ++ Array(
      "--target",
      "systemverilog",
      // "--split-verilog"
    ),
    Seq(
      ChiselGeneratorAnnotation(() => new FCMA(11, 53))
    )
  )
}
