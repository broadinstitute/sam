import sbt._

object Dependencies {
  val scalaV = "2.13"

  val jacksonV = "2.17.0"
  val akkaV = "2.6.19"
  val akkaHttpV = "10.2.2"

  val workbenchLibV = "9138393"

  val workbenchGoogleV = s"0.32-$workbenchLibV"
  val workbenchGoogle2V = s"0.36-$workbenchLibV"
  val workbenchServiceTestV = s"5.0-$workbenchLibV"

  val excludeWorkbenchModel = ExclusionRule(organization = "org.broadinstitute.dsde.workbench", name = "workbench-model_" + scalaV)

  val workbenchGoogle: ModuleID = "org.broadinstitute.dsde.workbench" %% "workbench-google" % workbenchGoogleV excludeAll excludeWorkbenchModel
  val workbenchGoogle2: ModuleID = "org.broadinstitute.dsde.workbench" %% "workbench-google2" % workbenchGoogle2V excludeAll excludeWorkbenchModel

  val workbenchServiceTest: ModuleID = "org.broadinstitute.dsde.workbench" %% "workbench-service-test" % workbenchServiceTestV % "test" classifier "tests"

  val rootDependencies = Seq(
    // proactively pull in latest versions of Jackson libs, instead of relying on the versions
    // specified as transitive dependencies, due to OWASP DependencyCheck warnings for earlier versions.
    "com.fasterxml.jackson.core" % "jackson-annotations" % jacksonV,
    "com.fasterxml.jackson.core" % "jackson-databind" % jacksonV,
    "com.fasterxml.jackson.core" % "jackson-core" % jacksonV,
    "com.fasterxml.jackson.module" % ("jackson-module-scala_" + scalaV) % jacksonV,
    "ch.qos.logback" % "logback-classic" % "1.4.5",
    "org.slf4j" % "slf4j-api" % "2.0.3",
    "net.logstash.logback" % "logstash-logback-encoder" % "6.6",
    "com.google.apis" % "google-api-services-oauth2" % "v1-rev112-1.20.0" excludeAll (
      ExclusionRule("com.google.guava", "guava-jdk5"),
      ExclusionRule("org.apache.httpcomponents", "httpclient")
    ),
    "com.google.api-client" % "google-api-client" % "1.22.0" excludeAll (ExclusionRule("com.google.guava", "guava-jdk5"),
    ExclusionRule("org.apache.httpcomponents", "httpclient")),
    "com.typesafe.akka" %% "akka-http-core" % akkaHttpV,
    "com.typesafe.akka" %% "akka-stream-testkit" % akkaV,
    "com.typesafe.akka" %% "akka-http" % akkaHttpV,
    "com.typesafe.akka" %% "akka-testkit" % akkaV % "test",
    "com.typesafe.akka" %% "akka-slf4j" % akkaV,
    "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2",
    "org.scalatest" %% "scalatest" % "3.2.19" % Test,
    "org.scalatestplus" %% "scalacheck-1-18" % "3.2.19.0" % Test,
    "com.typesafe.scala-logging" %% "scala-logging" % "3.9.4",
    workbenchServiceTest,
    workbenchGoogle,
    workbenchGoogle2,

    // required by workbenchGoogle
    "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpV % "provided"
  )
}
