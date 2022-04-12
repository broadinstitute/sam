import Dependencies._
import sbt.Keys._
import sbt._

object Settings {

  // for org.broadinstitute.dsde.workbench modules
  val artifactory = "https://broadinstitute.jfrog.io/broadinstitute/"
  val commonResolvers = List(
    "artifactory-releases" at artifactory + "libs-release",
    "artifactory-snapshots" at artifactory + "libs-snapshot"
  )

  //coreDefaultSettings + defaultConfigs = the now deprecated defaultSettings
  val commonBuildSettings = Defaults.coreDefaultSettings ++ Defaults.defaultConfigs ++ Seq(
    javaOptions += "-Xmx2G",
    javacOptions ++= Seq("--release", "11")
  )

  val commonCompilerSettings = Seq(
    "-unchecked",
    "-deprecation",
    "-feature",
    "-encoding", "utf8",
    "-language:postfixOps",
    "-target:jvm-1.11"
  )

  // test parameters explanation:
  // `-o` - causes test results to be written to the standard output
  //     `F` - Display full stack traces
  //     `D` - Display test duration after test name
  //     (removed on April 22, 2018) `G` - show reminder of failed and canceled tests with full stack traces at the end of log file
  // `-fWD` - causes test results to be written to the summary.log with test duration but without colored text
  val testSettings = List(
    testOptions in Test += Tests.Argument("-oFD", "-u", "test-reports", "-fWD", "test-reports/TEST-summary.log")
  )

  //common settings for all sbt subprojects
  val commonSettings =
    commonBuildSettings ++ testSettings ++ List(
    organization  := "org.broadinstitute.dsde.firecloud",
    scalaVersion  := "2.13.8",
    resolvers ++= commonResolvers,
    scalacOptions ++= commonCompilerSettings
  )

  //the full list of settings for the root project that's ultimately the one we build into a fat JAR and run
  //coreDefaultSettings (inside commonSettings) sets the project name, which we want to override, so ordering is important.
  //thus commonSettings needs to be added first.
  val rootSettings = commonSettings ++ List(
    name := "Sam-IntegrationTests",
    libraryDependencies ++= rootDependencies
  )
}
