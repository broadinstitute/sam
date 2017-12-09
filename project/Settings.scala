import Dependencies._
import Merging._
import Publishing._
import Testing._
import Version._
import sbt.Keys._
import sbt._
import sbtassembly.AssemblyPlugin.autoImport._

object Settings {

  val artifactory = "https://artifactory.broadinstitute.org/artifactory/"

  val commonResolvers = List(
    "artifactory-releases" at artifactory + "libs-release",
    "artifactory-snapshots" at artifactory + "libs-snapshot"
  )

  //coreDefaultSettings + defaultConfigs = the now deprecated defaultSettings
  val commonBuildSettings = Defaults.coreDefaultSettings ++ Defaults.defaultConfigs ++ Seq(
    javaOptions += "-Xmx2G",
    javacOptions ++= Seq("-source", "1.8", "-target", "1.8")
  )

  val commonCompilerSettings = Seq(
    "-unchecked",
    "-deprecation",
    "-feature",
    "-encoding", "utf8",
    "-target:jvm-1.8"
  )

  //sbt assembly settings
  val commonAssemblySettings = Seq(
    assemblyMergeStrategy in assembly := customMergeStrategy((assemblyMergeStrategy in assembly).value),
    test in assembly := {}
  )

  //common settings for all sbt subprojects
  val commonSettings =
    commonBuildSettings ++ commonAssemblySettings ++ commonTestSettings ++ List(
    organization  := "org.broadinstitute.dsde.workbench",
    scalaVersion  := "2.12.2",
    resolvers ++= commonResolvers,
    scalacOptions ++= commonCompilerSettings
  )

  val swaggerCodegenSettings = sourceGenerators in Compile += Def.task {
    SwaggerCodegen.genSwaggerClientCode((sourceManaged in Compile).value)
  }.taskValue

  //the full list of settings for the root project that's ultimately the one we build into a fat JAR and run
  //coreDefaultSettings (inside commonSettings) sets the project name, which we want to override, so ordering is important.
  //thus commonSettings needs to be added first.
  val samCoreSettings = commonSettings ++ List(
    name := "samCore",
    libraryDependencies ++= samCoreDependencies
  ) ++ noPublishSettings

  val samClientSettings = commonSettings ++ List(
    name := "samClient",
    libraryDependencies ++= samClientDependencies,
    swaggerCodegenSettings
  ) ++ publishSettings

  val rootSettings = commonSettings ++ noPublishSettings ++ noTestSettings
}
