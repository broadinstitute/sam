import sbt._

object Dependencies {
  val scalaV = "2.12"

  val jacksonV = "2.8.4"
  val akkaV = "2.5.7"
  val akkaHttpV = "10.0.10"

  val workbenchModelV  = "0.12-db13bb0"
  val workbenchGoogleV = "0.16-847c3ff"
  val workbenchServiceTestV = "0.13-dc0998e"

  val workbenchModel: ModuleID = "org.broadinstitute.dsde.workbench" %% "workbench-model" % workbenchModelV
  val excludeWorkbenchModel = ExclusionRule(organization = "org.broadinstitute.dsde.workbench", name = "workbench-model_" + scalaV)

  val workbenchGoogle: ModuleID = "org.broadinstitute.dsde.workbench" %% "workbench-google" % workbenchGoogleV excludeAll excludeWorkbenchModel
  val excludeWorkbenchGoogle = ExclusionRule(organization = "org.broadinstitute.dsde.workbench", name = "workbench-google_" + scalaV)

  val workbenchServiceTest: ModuleID = "org.broadinstitute.dsde.workbench" %% "workbench-service-test" % workbenchServiceTestV % "test" classifier "tests" excludeAll (excludeWorkbenchGoogle, excludeWorkbenchModel)

  val rootDependencies = Seq(
    // proactively pull in latest versions of Jackson libs, instead of relying on the versions
    // specified as transitive dependencies, due to OWASP DependencyCheck warnings for earlier versions.
    "com.fasterxml.jackson.core" % "jackson-annotations" % jacksonV,
    "com.fasterxml.jackson.core" % "jackson-databind" % jacksonV,
    "com.fasterxml.jackson.core" % "jackson-core" % jacksonV,
    "com.fasterxml.jackson.module" % ("jackson-module-scala_" + scalaV) % jacksonV,
    "ch.qos.logback" % "logback-classic" % "1.2.3",
    "com.google.apis" % "google-api-services-oauth2" % "v1-rev112-1.20.0" excludeAll (
      ExclusionRule("com.google.guava", "guava-jdk5"),
      ExclusionRule("org.apache.httpcomponents", "httpclient")
    ),
    "com.google.api-client" % "google-api-client" % "1.22.0" excludeAll (
      ExclusionRule("com.google.guava", "guava-jdk5"),
      ExclusionRule("org.apache.httpcomponents", "httpclient")),
    "org.webjars"           %  "swagger-ui"    % "2.2.5",
    "com.typesafe.akka"   %%  "akka-http-core"     % akkaHttpV,
    "com.typesafe.akka"   %%  "akka-stream-testkit" % akkaV,
    "com.typesafe.akka"   %%  "akka-http"           % akkaHttpV,
    "com.typesafe.akka"   %%  "akka-testkit"        % akkaV     % "test",
    "com.typesafe.akka"   %%  "akka-slf4j"          % akkaV,
    "org.specs2"          %%  "specs2-core"   % "3.8.6"  % "test",
    "org.scalatest"       %%  "scalatest"     % "3.0.1"   % "test",
    "org.seleniumhq.selenium" % "selenium-java" % "3.8.1" % "test",
    "com.typesafe.scala-logging" %% "scala-logging" % "3.5.0",

    workbenchServiceTest,
    workbenchModel,
    workbenchGoogle,


    // required by workbenchGoogle
    "com.typesafe.akka" %% "akka-http-spray-json" % "10.0.6" % "provided"
  )
}
