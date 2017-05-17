import sbt._

object Dependencies {
  val akkaV = "2.5.1"
  val akkaHttpV = "10.0.6"

  val rootDependencies = Seq(
    "ch.qos.logback" % "logback-classic" % "1.1.7",
    "com.getsentry.raven" % "raven-logback" % "7.8.6",
    "com.typesafe.scala-logging" %% "scala-logging-slf4j" % "3.5.0",
    "org.broadinstitute.dsde.vault" %%  "vault-common"  % "0.1-19-ca8b927",
    "org.webjars"          %  "swagger-ui"    % "2.2.5",
    "com.typesafe.akka"   %%  "akka-actor"    % akkaV,
    "com.typesafe.akka"   %%  "akka-contrib"  % akkaV,
    "com.typesafe.akka"   %%  "akka-testkit"  % akkaV     % "test",
    "com.typesafe.akka"   %%  "akka-slf4j"    % akkaV,
    "com.typesafe.akka"   %%  "akka-http" % akkaHttpV,
    "com.typesafe.akka"   %%  "akka-http-testkit" % akkaHttpV % "test",
    "com.typesafe.akka"   %%  "akka-http-spray-json" % akkaHttpV,
    "com.typesafe.akka"   %%  "akka-http-jackson" % akkaHttpV,
    "org.scalatest"       %%  "scalatest"     % "3.0.1"   % "test"
  )
}
