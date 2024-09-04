import scala.sys.process._

object Version {

  def createVersion(baseVersion: String) = {
    def getLastCommitFromGit = s"""git rev-parse --short HEAD""" !!

    // either specify git hash as an env var or derive it
    // if building from the hseeberger/scala-sbt docker image use env var
    // (scala-sbt doesn't have git in it)
    val lastCommit = sys.env.getOrElse("GIT_HASH", getLastCommitFromGit).trim()
    val version = baseVersion + "-" + lastCommit

    val semVer = sys.props.get("project.semVer").getOrElse(version)

    // The project isSnapshot string passed in via command line settings, if desired.
    val isSnapshot = sys.props.getOrElse("project.isSnapshot", "true").toBoolean
    if (isSnapshot) s"$semVer-snapshot" else semVer
  }
}
