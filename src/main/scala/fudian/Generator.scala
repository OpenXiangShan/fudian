package fudian

import chisel3.RawModule
import chisel3.stage.{ChiselGeneratorAnnotation, ChiselStage}

object Generator extends App {

  def getModuleGen(
    name:      String,
    expWidth:  Int,
    precision: Int
  ): () => RawModule = {
    val pkg = this.getClass.getPackageName
    val c =
      Class.forName(pkg + "." + name).getConstructor(Integer.TYPE, Integer.TYPE)
    () =>
      c.newInstance(
        expWidth.asInstanceOf[Object],
        precision.asInstanceOf[Object]
      ).asInstanceOf[RawModule]
  }

  override def main(args: Array[String]): Unit = {
    val (module, expWidth, precision, firrtlOpts) = ArgParser.parse(args)
    (new ChiselStage).execute(
      firrtlOpts,
      Seq(
        ChiselGeneratorAnnotation(getModuleGen(module, expWidth, precision))
      )
    )
  }
}
