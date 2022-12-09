import Settings._
import Testing._

lazy val root = project
  .in(file("."))
  .settings(rootSettings: _*)
  .withTestSettings

Revolver.settings
Global / excludeLintKeys += debugSettings // To avoid lint warning

reStart / javaOptions += "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5050"

// When JAVA_OPTS are specified in the environment, they are usually meant for the application
// itself rather than sbt, but they are not passed by default to the application, which is a forked
// process. This passes them through to the "re-start" command, which is probably what a developer
// would normally expect.
// for some reason using ++= causes revolver not to find the main class so do the stupid map below
//javaOptions in reStart ++= sys.env.getOrElse("JAVA_OPTS", "").split(" ").toSeq
sys.env.getOrElse("JAVA_OPTS", "").split(" ").toSeq.map { opt =>
  reStart / javaOptions += opt
}

addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")

lazy val pact4s = project
  .in(file("pact4s"))
  .settings(pact4sSettings)
  .dependsOn(root % "test->test;compile->compile")
