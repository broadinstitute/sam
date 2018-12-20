import Settings._
import Testing._

lazy val root = project
  .in(file("."))
  .settings(rootSettings: _*)
  .withTestSettings

Revolver.settings

Revolver.enableDebugging(port = 5050, suspend = false)

// When JAVA_OPTS are specified in the environment, they are usually meant for the application
// itself rather than sbt, but they are not passed by default to the application, which is a forked
// process. This passes them through to the "re-start" command, which is probably what a developer
// would normally expect.
// for some reason using ++= causes revolver not to find the main class so do the stupid map below
//javaOptions in reStart ++= sys.env.getOrElse("JAVA_OPTS", "").split(" ").toSeq
sys.env.getOrElse("JAVA_OPTS", "").split(" ").toSeq.map { opt =>
  javaOptions in reStart += opt
}

addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.2.4")
addCompilerPlugin("org.scalamacros" %% "paradise" % "2.1.0" cross CrossVersion.full)

enablePlugins(JavaAppPackaging)
enablePlugins(DockerPlugin)
enablePlugins(GraalVMNativeImagePlugin)

// For docker setting
mainClass in Compile := Some("org.broadinstitute.dsde.workbench.sam.Boot")
dockerBaseImage := "oracle/graalvm-ce:1.0.0-rc10"
graalVMNativeImageOptions := List(
  "--no-server",
  "-H:+ReportUnsupportedElementsAtRuntime",
  "--allow-incomplete-classpath",
  "--delay-class-initialization-to-runtime=org.ehcache.core.EhcacheManager,com.getsentry.raven.config.Lookup,com.getsentry.raven.dsn.Dsn,org.ehcache.core.internal.service.ServiceLocator,com.getsentry.raven.event.EventBuilder,com.getsentry.raven.config.JndiLookup,com.getsentry.raven.RavenFactory,com.getsentry.raven.environment.RavenEnvironment,io.grpc.netty.shaded.io.netty.handler.ssl.ReferenceCountedOpenSslServerContext,io.grpc.netty.shaded.io.netty.handler.ssl.ReferenceCountedOpenSslContext,com.codahale.metrics.health.HealthCheckRegistry",
  "--report-unsupported-elements-at-runtime"
//  "--rerun-class-initialization-at-runtime=org.httpkit.client.SslContextFactory"
)

bloopAggregateSourceDependencies in Global := true
