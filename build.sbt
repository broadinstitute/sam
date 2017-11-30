import Settings._
import Testing._

val compileAndTest = "compile->compile;test->test"

lazy val samModel = project.in(file("model"))
  .settings(modelSettings:_*)
  .withTestSettings

lazy val samCore = project.in(file("core"))
  .settings(samCoreSettings:_*)
  .dependsOn(samModel)
  .withTestSettings

lazy val sam = project.in(file("."))
  .settings(rootSettings:_*)
  .aggregate(samModel)
  .aggregate(samCore)
  .dependsOn(samCore)
  .withTestSettings

Revolver.settings

mainClass in (Compile,run) := Some("org.broadinstitute.dsde.workbench.sam.Boot")

Revolver.enableDebugging(port = 5050, suspend = false)

// When JAVA_OPTS are specified in the environment, they are usually meant for the application
// itself rather than sbt, but they are not passed by default to the application, which is a forked
// process. This passes them through to the "re-start" command, which is probably what a developer
// would normally expect.
// for some reason using ++= causes revolver not to find the main class so do the stupid map below
//javaOptions in reStart ++= sys.env.getOrElse("JAVA_OPTS", "").split(" ").toSeq
//
sys.env.getOrElse("JAVA_OPTS", "").split(" ").toSeq.map { opt =>
  javaOptions in reStart += opt
}

mainClass in reStart := Some("org.broadinstitute.dsde.workbench.sam.Boot")