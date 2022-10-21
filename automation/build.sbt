import Settings._

lazy val samIntegration = project.in(file("."))
  .settings(rootSettings:_*)

version := "1.0"

/**
  * Print the output of tests immediately instead of buffering
  */
logBuffered in Test := false