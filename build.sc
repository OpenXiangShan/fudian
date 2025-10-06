// import Mill dependency
import mill._
import scalalib._
import scalafmt._

val defaultVersions = Map(
  "chisel" -> "7.1.1",
  "chisel-plugin" -> "7.1.1",
  "scala" -> "2.13.16",
  "scalatest" -> "3.2.17"
)

def getVersion(dep: String, org: String, cross: Boolean = false) = {
  val version = sys.env.getOrElse(dep + "Version", defaultVersions(dep))
  if (cross)
    ivy"$org:::$dep:$version"
  else
    ivy"$org::$dep:$version"
}

object fudian extends SbtModule with ScalaModule with ScalafmtModule {

  override def millSourcePath = millOuterCtx.millSourcePath

  def scalaVersion = defaultVersions("scala")

  override def scalacPluginIvyDeps = super.scalacPluginIvyDeps() ++ Agg(getVersion("chisel-plugin", "org.chipsalliance", cross = true))

  override def ivyDeps = super.ivyDeps() ++ Agg(
    getVersion("chisel", "org.chipsalliance"),
  )

  object tests extends SbtTests {
    override def ivyDeps = super.ivyDeps() ++ Agg(
      getVersion("scalatest", "org.scalatest")
    )
    override def testFramework: T[String] = T("org.scalatest.tools.testFramework")
  }

}
