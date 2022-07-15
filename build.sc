// import Mill dependency
import mill._
import scalalib._
import scalafmt._
import coursier.maven.MavenRepository

val defaultVersions = Map(
  "chisel3" -> "3.5.0",
  "chisel3-plugin" -> "3.5.0",
  "scala" -> "2.12.13",
  "chiseltest" -> "latest.integration",
  "scalatest" -> "3.2.7"
)

def getVersion(dep: String, org: String = "edu.berkeley.cs", cross: Boolean = false) = {
  val version = sys.env.getOrElse(dep + "Version", defaultVersions(dep))
  if (cross)
    ivy"$org:::$dep:$version"
  else
    ivy"$org::$dep:$version"
}

object fudian extends SbtModule with ScalaModule with ScalafmtModule {

  override def millSourcePath = millOuterCtx.millSourcePath

  override def repositoriesTask = T.task {
    super.repositoriesTask() ++ Seq(
      MavenRepository("https://oss.sonatype.org/content/repositories/snapshots")
    )
  }

  def scalaVersion = defaultVersions("scala")


  override def scalacPluginIvyDeps = super.scalacPluginIvyDeps() ++ Agg(getVersion("chisel3-plugin", cross = true))

  override def ivyDeps = super.ivyDeps() ++ Agg(
    getVersion("chisel3"),
    getVersion("chiseltest")
  )

  object tests extends Tests {
    override def ivyDeps = super.ivyDeps() ++ Agg(
      getVersion("scalatest","org.scalatest")
    )
    override def testFramework: T[String] = T("org.scalatest.tools.testFramework")
  }

}
