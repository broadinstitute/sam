import sbt._

object Dependencies {
  val akkaV = "2.6.19"
  val akkaHttpV = "10.2.9"
  val jacksonV = "2.9.5"
  val scalaLoggingV = "3.9.2"
  val scalaTestV = "3.2.12"
  val scalaCheckV = "1.14.3"
  val scalikejdbcVersion = "3.4.2"
  val postgresDriverVersion = "42.5.0"
  val http4sVersion = "0.21.13"
  val sentryVersion = "6.6.0"

  val workbenchUtilV = "0.6-74c9fc2"
  val workbenchUtil2V = "0.2-447afa5"
  val workbenchModelV = "0.15-f9f0d4c"
  val workbenchGoogleV = "0.21-8ce5b9b"
  val workbenchGoogle2V = "0.24-447afa5"
  val workbenchNotificationsV = "0.3-d74ff96"
  val workbenchOauth2V = "0.2-20f9225"
  val workbenchOpenTelemetryV = "0.3-0096bac"
  val monocleVersion = "2.0.5"
  val crlVersion = "1.2.3-SNAPSHOT"

  val excludeAkkaActor = ExclusionRule(organization = "com.typesafe.akka", name = "akka-actor_2.12")
  val excludeAkkaProtobufV3 = ExclusionRule(organization = "com.typesafe.akka", name = "akka-protobuf-v3_2.12")
  val excludeAkkaStream = ExclusionRule(organization = "com.typesafe.akka", name = "akka-stream_2.12")
  val excludeWorkbenchUtil = ExclusionRule(organization = "org.broadinstitute.dsde.workbench", name = "workbench-util_2.12")
  val excludeWorkbenchModel = ExclusionRule(organization = "org.broadinstitute.dsde.workbench", name = "workbench-model_2.12")
  val excludeWorkbenchMetrics = ExclusionRule(organization = "org.broadinstitute.dsde.workbench", name = "workbench-metrics_2.12")
  val excludeWorkbenchGoogle = ExclusionRule(organization = "org.broadinstitute.dsde.workbench", name = "workbench-google_2.12")
  val excludeGoogleCloudResourceManager = ExclusionRule(organization = "com.google.apis", name = "google-api-services-cloudresourcemanager")
  val excludeJerseyCore = ExclusionRule(organization = "org.glassfish.jersey.core", name = "*")
  val excludeJerseyMedia = ExclusionRule(organization = "org.glassfish.jersey.media", name = "*")

  val sentry: ModuleID = "io.sentry" % "sentry" % sentryVersion

  val jacksonAnnotations: ModuleID = "com.fasterxml.jackson.core" % "jackson-annotations" % jacksonV
  val jacksonDatabind: ModuleID = "com.fasterxml.jackson.core" % "jackson-databind" % jacksonV
  val jacksonCore: ModuleID = "com.fasterxml.jackson.core" % "jackson-core" % jacksonV

  val logstashLogback: ModuleID = "net.logstash.logback" % "logstash-logback-encoder" % "6.6"
  val logbackClassic: ModuleID = "ch.qos.logback" % "logback-classic" % "1.2.11"
  val ravenLogback: ModuleID = "com.getsentry.raven" % "raven-logback" % "7.8.6"
  val scalaLogging: ModuleID = "com.typesafe.scala-logging" %% "scala-logging" % scalaLoggingV
  val ficus: ModuleID = "com.iheart" %% "ficus" % "1.5.2"
//  val stackdriverLogging: ModuleID = "org.springframework.cloud" % "spring-cloud-gcp-logging" % "1.2.8.RELEASE" excludeAll(excludeSpring, excludeSpringBoot)
  val stackdriverLogging: ModuleID = "com.google.cloud" % "google-cloud-logging-logback" % "0.127.17-alpha"
  val janino: ModuleID = "org.codehaus.janino" % "janino" % "3.1.7" // For if-else logic in logging config

  val akkaActor: ModuleID = "com.typesafe.akka" %% "akka-actor" % akkaV
  val akkaSlf4j: ModuleID = "com.typesafe.akka" %% "akka-slf4j" % akkaV
  val akkaStream: ModuleID = "com.typesafe.akka" %% "akka-stream" % akkaV excludeAll excludeAkkaProtobufV3
  val akkaHttp: ModuleID = "com.typesafe.akka" %% "akka-http" % akkaHttpV excludeAll excludeAkkaActor
  val akkaHttpSprayJson: ModuleID = "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpV
  val akkaTestKit: ModuleID = "com.typesafe.akka" %% "akka-testkit" % akkaV % "test"
  val akkaHttpTestKit: ModuleID = "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpV % "test"
  val scalaCheck: ModuleID = "org.scalacheck" %% "scalacheck" % scalaCheckV % "test"

  val excludIoGrpc = ExclusionRule(organization = "io.grpc", name = "grpc-core")
  val ioGrpc: ModuleID = "io.grpc" % "grpc-core" % "1.34.1"

  val googleOAuth2: ModuleID = "com.google.auth" % "google-auth-library-oauth2-http" % "0.18.0" excludeAll excludIoGrpc
  val googleStorage: ModuleID = "com.google.apis" % "google-api-services-storage" % "v1-rev20220401-1.32.1" excludeAll excludIoGrpc // force this version

  val monocle: ModuleID = "com.github.julien-truffaut" %% "monocle-core" % monocleVersion
  val monocleMacro: ModuleID = "com.github.julien-truffaut" %% "monocle-macro" % monocleVersion

  val scalaTest: ModuleID = "org.scalatest" %% "scalatest" % scalaTestV % "test"
  val scalaTestScalaCheck = "org.scalatestplus" %% "scalacheck-1-15" % s"${scalaTestV}.0-RC2" % Test
  val scalaTestMockito = "org.scalatestplus" %% "mockito-4-5" % s"${scalaTestV}.0" % Test

  // All of workbench-libs pull in Akka; exclude it since we provide our own Akka dependency.
  // workbench-google pulls in workbench-{util, model, metrics}; exclude them so we can control the library versions individually.
  val workbenchUtil: ModuleID = "org.broadinstitute.dsde.workbench" %% "workbench-util" % workbenchUtilV excludeAll excludeWorkbenchModel
  val workbenchUtil2: ModuleID = "org.broadinstitute.dsde.workbench" %% "workbench-util2" % workbenchUtil2V excludeAll excludeWorkbenchModel
  val workbenchModel: ModuleID = "org.broadinstitute.dsde.workbench" %% "workbench-model" % workbenchModelV
  val workbenchGoogle: ModuleID =
    "org.broadinstitute.dsde.workbench" %% "workbench-google" % workbenchGoogleV excludeAll (excludeWorkbenchModel, excludeWorkbenchUtil)
  val workbenchOauth2: ModuleID = "org.broadinstitute.dsde.workbench" %% "workbench-oauth2" % workbenchOauth2V
  val workbenchOauth2Tests: ModuleID = "org.broadinstitute.dsde.workbench" %% "workbench-oauth2" % workbenchOauth2V % "test" classifier "tests"
  val workbenchOpenTelemetry: ModuleID = "org.broadinstitute.dsde.workbench" %% "workbench-opentelemetry" % workbenchOpenTelemetryV
  val workbenchOpenTelemetryTest: ModuleID =
    "org.broadinstitute.dsde.workbench" %% "workbench-opentelemetry" % workbenchOpenTelemetryV % "test" classifier "tests"
  // the name of the auto-value package changed from auto-value to auto-value-annotations so old libraries are not evicted
  // leading to merge errors during sbt assembly. At this time the old version of auto-value is pulled in through the google2
  // workbench-libs dependency so exclude auto-value from there
  val excludGoogleAutoValue = ExclusionRule(organization = "com.google.auto.value", name = "auto-value")
  val workbenchGoogle2: ModuleID =
    "org.broadinstitute.dsde.workbench" %% "workbench-google2" % workbenchGoogle2V excludeAll (excludeWorkbenchModel, excludeWorkbenchUtil, excludGoogleAutoValue)
  val workbenchNotifications: ModuleID =
    "org.broadinstitute.dsde.workbench" %% "workbench-notifications" % workbenchNotificationsV excludeAll (excludeWorkbenchGoogle, excludeWorkbenchModel)
  val workbenchGoogleTests: ModuleID =
    "org.broadinstitute.dsde.workbench" %% "workbench-google" % workbenchGoogleV % "test" classifier "tests" excludeAll (excludeWorkbenchUtil, excludeWorkbenchModel)
  val workbenchGoogle2Tests: ModuleID =
    "org.broadinstitute.dsde.workbench" %% "workbench-google2" % workbenchGoogle2V % "test" classifier "tests" excludeAll (excludeWorkbenchUtil, excludeWorkbenchModel)
  val googleStorageLocal: ModuleID = "com.google.cloud" % "google-cloud-nio" % "0.123.28" % "test" // needed for mocking google cloud storage

  val liquibaseCore: ModuleID = "org.liquibase" % "liquibase-core" % "4.2.2"

  val circeYAML: ModuleID = "io.circe" %% "circe-yaml" % "0.14.1"

  val scalikeCore = "org.scalikejdbc" %% "scalikejdbc" % scalikejdbcVersion
  val scalikeCoreConfig = "org.scalikejdbc" %% "scalikejdbc-config" % scalikejdbcVersion
  val scalikeCoreTest = "org.scalikejdbc" %% "scalikejdbc-test" % scalikejdbcVersion % "test"
  val postgres = "org.postgresql" % "postgresql" % postgresDriverVersion

  val excludeScalaCllectionCompat = ExclusionRule(organization = "org.scala-lang.modules", name = "scala-collection-compat_2.12")
  val opencensusScalaCode: ModuleID = "com.github.sebruck" %% "opencensus-scala-core" % "0.7.2" // excludeAll(excludIoGrpc, excludeCatsEffect )
  val opencensusAkkaHttp: ModuleID =
    "com.github.sebruck" %% "opencensus-scala-akka-http" % "0.7.2" excludeAll (excludeAkkaProtobufV3, excludeAkkaStream) // excludeAll(excludIoGrpc, excludeCatsEffect)
  val opencensusStackDriverExporter: ModuleID =
    "io.opencensus" % "opencensus-exporter-trace-stackdriver" % "0.31.1" // excludeAll(excludIoGrpc, excludeCatsEffect)
  val opencensusLoggingExporter: ModuleID = "io.opencensus" % "opencensus-exporter-trace-logging" % "0.31.1" // excludeAll(excludIoGrpc, excludeCatsEffect)

  val openCensusDependencies = Seq(
    opencensusScalaCode,
    opencensusAkkaHttp,
    opencensusStackDriverExporter,
    opencensusLoggingExporter
  )

  val cloudResourceLib: ModuleID =
    "bio.terra" % "terra-cloud-resource-lib" % crlVersion excludeAll (excludeGoogleCloudResourceManager, excludeJerseyCore, excludeJerseyMedia)

  // was included transitively before, now explicit
  val commonsCodec: ModuleID = "commons-codec" % "commons-codec" % "1.15"

  val rootDependencies = Seq(
    // proactively pull in latest versions of Jackson libs, instead of relying on the versions
    // specified as transitive dependencies, due to OWASP DependencyCheck warnings for earlier versions.
    sentry,
    ioGrpc,
    logbackClassic,
    logstashLogback,
    ravenLogback,
    scalaLogging,
    ficus,
    stackdriverLogging,
    janino,
    akkaActor,
    akkaSlf4j,
    akkaStream,
    akkaHttp,
    akkaHttpSprayJson,
    akkaTestKit,
    akkaHttpTestKit,
    googleOAuth2,
    googleStorage,
    monocle,
    monocleMacro,
    scalaTest,
    scalaTestScalaCheck,
    scalaCheck,
    scalaTestMockito,
    workbenchUtil,
    workbenchModel,
    workbenchGoogle,
    workbenchGoogle2,
    workbenchNotifications,
    workbenchGoogleTests,
    workbenchGoogle2Tests,
    googleStorageLocal,
    workbenchOauth2,
    workbenchOauth2Tests,
    workbenchOpenTelemetry,
    workbenchOpenTelemetryTest,
    commonsCodec,
    liquibaseCore,
    circeYAML,
    scalikeCore,
    scalikeCoreConfig,
    scalikeCoreTest,
    postgres,
    cloudResourceLib
  ) ++ openCensusDependencies
}
