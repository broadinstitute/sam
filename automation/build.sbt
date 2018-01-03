import Settings._

lazy val samIntegration = project.in(file("."))
  .settings(rootSettings:_*)

version := "1.0"
