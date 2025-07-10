import sbt.Keys._
import sbt._
import Artifactory._

/** NOTE: This was lifted wholesale from Cromwell.
  */

object Publishing {

  private val garBase = "artifactregistry://us-central1-maven.pkg.dev/dsp-artifact-registry/"

  private def garResolver(): Resolver = {
    val isSnapshot = sys.props.getOrElse("project.isSnapshot", "false").toBoolean
    val repoType = if (isSnapshot) "snapshot" else "release"
    val repoUrl = s"${garBase}libs-$repoType-standard"
    val repoName = "gar-publish"
    repoName at repoUrl
  }

  val publishSettings: Seq[Setting[_]] = Seq(
    publishTo := Some(garResolver()), // Use release repo
    Compile / publishArtifact := true,
    Test / publishArtifact := true
  )
  
  val noPublishSettings: Seq[Setting[_]] =
    Seq(
      publish := {},
      publishLocal := {}
    )
}
