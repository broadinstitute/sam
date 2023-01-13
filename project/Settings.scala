import Dependencies._
import Merging._
import Testing._
import Version._
import org.scalafmt.sbt.ScalafmtPlugin.autoImport.{scalafmtAll, scalafmtSbt}
import sbt.Keys._
import sbt.{Compile, Test, _}
import sbtassembly.AssemblyPlugin.autoImport._

object Settings {
  lazy val artifactory = "https://artifactory.broadinstitute.org/artifactory/"

  lazy val commonResolvers = List(
    "artifactory-releases" at artifactory + "libs-release",
    "artifactory-snapshots" at artifactory + "libs-snapshot"
  )

  // coreDefaultSettings + defaultConfigs = the now deprecated defaultSettings
  lazy val commonBuildSettings = Defaults.coreDefaultSettings ++ Defaults.defaultConfigs ++ Seq(
    javaOptions += "-Xmx2G",
    javacOptions ++= Seq("--release", "17"),
    scalacOptions in (Compile, console) --= Seq("-Ywarn-unused:imports", "-Xfatal-warnings"),
    scalacOptions in Test -= "-Ywarn-dead-code" // due to https://github.com/mockito/mockito-scala#notes
  )

  // recommended scalac options by https://tpolecat.github.io/2017/04/25/scalac-flags.html
  lazy val commonCompilerSettings = Seq(
    "-release:11",
    "-deprecation", // Emit warning and location for usages of deprecated APIs.
    "-encoding",
    "utf-8", // Specify character encoding used by source files.
    "-feature", // Emit warning and location for usages of features that should be imported explicitly.
    "-language:existentials", // Existential types (besides wildcard types) can be written and inferred
    "-unchecked", // Enable additional warnings where generated code depends on assumptions.
    "-Xcheckinit", // Wrap field accessors to throw an exception on uninitialized access.
    "-Xfatal-warnings", // Fail the compilation if there are any warnings.
    "-Xlint:adapted-args", // Warn if an argument list is modified to match the receiver.
    "-Xlint:constant", // Evaluation of a constant arithmetic expression results in an error.
    "-Xlint:delayedinit-select", // Selecting member of DelayedInit.
    "-Xlint:doc-detached", // A Scaladoc comment appears to be detached from its element.
    "-Xlint:missing-interpolator", // A string literal appears to be missing an interpolator id.
    "-Xlint:option-implicit", // Option.apply used implicit view.
    "-Xlint:package-object-classes", // Class or object defined in package object.
    "-Xlint:poly-implicit-overload", // Parameterized overloaded implicit methods are not visible as view bounds.
    "-Xlint:private-shadow", // A private field (or class parameter) shadows a superclass field.
    "-Xlint:stars-align", // Pattern sequence wildcard must align with sequence component.
    "-Xlint:type-parameter-shadow", // A local type parameter shadows a type already in scope.
    "-Ywarn-dead-code", // Warn when dead code is identified.
    "-Ywarn-extra-implicit", // Warn when more than one implicit parameter section is defined.
    "-Ywarn-numeric-widen", // Warn when numerics are widened.
    "-Ywarn-unused:implicits", // Warn if an implicit parameter is unused.
    "-Ywarn-unused:imports", // Warn if an import selector is not referenced.
    "-language:postfixOps",
    "-Ymacro-annotations"
  )

  // sbt assembly settings
  lazy val commonAssemblySettings = Seq(
    assemblyMergeStrategy in assembly := customMergeStrategy((assemblyMergeStrategy in assembly).value),
    test in assembly := {}
  )

  // common settings for all sbt subprojects
  lazy val commonSettings =
    commonBuildSettings ++ commonAssemblySettings ++ commonTestSettings ++ List(
      organization := "org.broadinstitute.dsde.workbench",
      scalaVersion := "2.13.10",
      resolvers ++= commonResolvers,
      scalacOptions ++= commonCompilerSettings,
      Compile / compile := (Compile / compile).dependsOn(Compile / scalafmtAll).value,
      Compile / compile := (Compile / compile).dependsOn(Compile / scalafmtSbt).value
    )

  // the full list of settings for the root project that's ultimately the one we build into a fat JAR and run
  // coreDefaultSettings (inside commonSettings) sets the project name, which we want to override, so ordering is important.
  // thus commonSettings needs to be added first.
  lazy val rootSettings = commonSettings ++ List(
    name := "sam",
    libraryDependencies ++= rootDependencies
  ) ++ commonAssemblySettings ++ rootVersionSettings

  lazy val pact4sSettings = commonSettings ++ commonTestSettings ++ List(
    libraryDependencies ++= pact4sDependencies,

    /**
      * Invoking pact tests from root project (sbt "project pact" test)
      * will launch tests in a separate JVM context that ensures contracts
      * are written to the pact/target/pacts folder. Otherwise, contracts
      * will be written to the root folder.
      */
    Test / fork := true

  ) ++ commonAssemblySettings ++ rootVersionSettings
}
