package fudian

import chisel3.stage.{ChiselGeneratorAnnotation, ChiselStage}

object FCMAMain extends App {
  (new ChiselStage).execute(args, Seq(
    ChiselGeneratorAnnotation(() => new FCMA(11, 53))
  ))
}
