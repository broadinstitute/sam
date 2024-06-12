import sbt._

object Dependencies {
  val akkaV = "2.6.19"
  val akkaHttpV = "10.2.9"
  val jacksonV = "2.9.5"
  val scalaLoggingV = "3.9.2"
  val scalaTestV = "3.2.12"
  val scalaCheckV = "1.14.3"
  val scalikejdbcVersion = "3.4.2"
  val postgresDriverVersion = "42.7.2"
  val sentryVersion = "6.15.0"

  val workbenchLibV = "a6ad7dc" // If updating this, make sure googleStorageLocal in test dependencies is up-to-date
  val workbenchUtilV = s"0.10-$workbenchLibV"
  val workbenchUtil2V = s"0.9-$workbenchLibV"
  val workbenchModelV = s"0.19-$workbenchLibV"
  val workbenchGoogleV = s"0.32-$workbenchLibV"
  val workbenchGoogle2V = s"0.36-$workbenchLibV"
  val workbenchNotificationsV = s"0.6-$workbenchLibV"
  val workbenchOauth2V = s"0.7-$workbenchLibV"
  val monocleVersion = "2.0.5"
  val crlVersion = "1.2.30-SNAPSHOT"
  val tclVersion = "1.0.5-SNAPSHOT"
  val slf4jVersion = "2.0.6"

  val excludeAkkaActor = ExclusionRule(organization = "com.typesafe.akka", name = "akka-actor_2.12")
  val excludeAkkaProtobufV3 = ExclusionRule(organization = "com.typesafe.akka", name = "akka-protobuf-v3_2.12")
  val excludeAkkaStream = ExclusionRule(organization = "com.typesafe.akka", name = "akka-stream_2.12")
  val excludeWorkbenchUtil = ExclusionRule(organization = "org.broadinstitute.dsde.workbench", name = "workbench-util_2.12")
  val excludeWorkbenchUtil2 = ExclusionRule(organization = "org.broadinstitute.dsde.workbench", name = "workbench-util2_2.12")
  val excludeWorkbenchModel = ExclusionRule(organization = "org.broadinstitute.dsde.workbench", name = "workbench-model_2.12")
  val excludeWorkbenchMetrics = ExclusionRule(organization = "org.broadinstitute.dsde.workbench", name = "workbench-metrics_2.12")
  val excludeWorkbenchGoogle = ExclusionRule(organization = "org.broadinstitute.dsde.workbench", name = "workbench-google_2.12")
  val excludeGoogleCloudResourceManager = ExclusionRule(organization = "com.google.apis", name = "google-api-services-cloudresourcemanager")
  val excludeGoogleServiceUsage = ExclusionRule(organization = "com.google.apis", name = "google-api-services-serviceusage")
  val excludeSLF4J = ExclusionRule(organization = "org.slf4j")
  val excludeJerseyCore = ExclusionRule(organization = "org.glassfish.jersey.core", name = "*")
  val excludeJerseyMedia = ExclusionRule(organization = "org.glassfish.jersey.media", name = "*")
  val excludeAwsSdk = ExclusionRule(organization = "software.amazon.awssdk", name = "*")

  val sentry: ModuleID = "io.sentry" % "sentry" % sentryVersion
  val sentryLogback: ModuleID = "io.sentry" % "sentry-logback" % sentryVersion

  val jacksonAnnotations: ModuleID = "com.fasterxml.jackson.core" % "jackson-annotations" % jacksonV
  val jacksonDatabind: ModuleID = "com.fasterxml.jackson.core" % "jackson-databind" % jacksonV
  val jacksonCore: ModuleID = "com.fasterxml.jackson.core" % "jackson-core" % jacksonV

  val logstashLogback: ModuleID = "net.logstash.logback" % "logstash-logback-encoder" % "6.6"
  val logbackClassic: ModuleID = "ch.qos.logback" % "logback-classic" % "1.4.14"
  val ravenLogback: ModuleID = "com.getsentry.raven" % "raven-logback" % "7.8.6"
  val scalaLogging: ModuleID = "com.typesafe.scala-logging" %% "scala-logging" % scalaLoggingV
  val ficus: ModuleID = "com.iheart" %% "ficus" % "1.5.2"
//  val stackdriverLogging: ModuleID = "org.springframework.cloud" % "spring-cloud-gcp-logging" % "1.2.8.RELEASE" excludeAll(excludeSpring, excludeSpringBoot)
  val stackdriverLogging: ModuleID = "com.google.cloud" % "google-cloud-logging-logback" % "0.127.11-alpha"
  val janino: ModuleID = "org.codehaus.janino" % "janino" % "3.1.7" // For if-else logic in logging config

  val akkaActor: ModuleID = "com.typesafe.akka" %% "akka-actor" % akkaV
  val akkaSlf4j: ModuleID = "com.typesafe.akka" %% "akka-slf4j" % akkaV
  val akkaStream: ModuleID = "com.typesafe.akka" %% "akka-stream" % akkaV excludeAll excludeAkkaProtobufV3
  val akkaHttp: ModuleID = "com.typesafe.akka" %% "akka-http" % akkaHttpV excludeAll excludeAkkaActor
  val akkaHttpSprayJson: ModuleID = "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpV
  val akkaTestKit: ModuleID = "com.typesafe.akka" %% "akka-testkit" % akkaV % "test"
  val akkaHttpTestKit: ModuleID = "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpV % "test"
  val scalaCheck: ModuleID = "org.scalacheck" %% "scalacheck" % scalaCheckV % "test"

  val nettyAll: ModuleID = "io.netty" % "netty-all" % "4.1.100.Final"
  val reactorNetty: ModuleID = "io.projectreactor.netty" % "reactor-netty" % "1.0.39"

  val excludIoGrpc = ExclusionRule(organization = "io.grpc", name = "grpc-core")
  val ioGrpc: ModuleID = "io.grpc" % "grpc-core" % "1.34.1"

  val googleOAuth2: ModuleID = "com.google.auth" % "google-auth-library-oauth2-http" % "0.18.0" excludeAll excludIoGrpc
  val googleStorage: ModuleID = "com.google.apis" % "google-api-services-storage" % "v1-rev20220401-1.32.1" excludeAll excludIoGrpc // force this version

  val monocle: ModuleID = "com.github.julien-truffaut" %% "monocle-core" % monocleVersion
  val monocleMacro: ModuleID = "com.github.julien-truffaut" %% "monocle-macro" % monocleVersion

  val scalaTest: ModuleID = "org.scalatest" %% "scalatest" % scalaTestV % "test"
  val scalaTestScalaCheck = "org.scalatestplus" %% "scalacheck-1-15" % s"${scalaTestV}.0-RC2" % Test
  val mockitoScalaTest = "org.mockito" %% "mockito-scala-scalatest" % "1.17.12" % Test

  // All of workbench-libs pull in Akka; exclude it since we provide our own Akka dependency.
  // workbench-google pulls in workbench-{util, model, metrics}; exclude them so we can control the library versions individually.
  val workbenchUtil: ModuleID = "org.broadinstitute.dsde.workbench" %% "workbench-util" % workbenchUtilV excludeAll excludeWorkbenchModel
  val workbenchUtil2: ModuleID = "org.broadinstitute.dsde.workbench" %% "workbench-util2" % workbenchUtil2V excludeAll excludeWorkbenchModel
  val workbenchModel: ModuleID = "org.broadinstitute.dsde.workbench" %% "workbench-model" % workbenchModelV
  val workbenchGoogle: ModuleID =
    "org.broadinstitute.dsde.workbench" %% "workbench-google" % workbenchGoogleV excludeAll (excludeWorkbenchModel, excludeWorkbenchUtil)
  val workbenchOauth2: ModuleID = "org.broadinstitute.dsde.workbench" %% "workbench-oauth2" % workbenchOauth2V
  val workbenchOauth2Tests: ModuleID = "org.broadinstitute.dsde.workbench" %% "workbench-oauth2" % workbenchOauth2V % "test" classifier "tests"
  // the name of the auto-value package changed from auto-value to auto-value-annotations so old libraries are not evicted
  // leading to merge errors during sbt assembly. At this time the old version of auto-value is pulled in through the google2
  // workbench-libs dependency so exclude auto-value from there
  val excludeGoogleAutoValue = ExclusionRule(organization = "com.google.auto.value", name = "auto-value")
  val excludeBouncyCastle = ExclusionRule("org.bouncycastle")
  val workbenchGoogle2: ModuleID =
    "org.broadinstitute.dsde.workbench" %% "workbench-google2" % workbenchGoogle2V excludeAll (excludeWorkbenchModel, excludeWorkbenchUtil, excludeGoogleAutoValue, excludeBouncyCastle)
  val workbenchNotifications: ModuleID =
    "org.broadinstitute.dsde.workbench" %% "workbench-notifications" % workbenchNotificationsV excludeAll (excludeWorkbenchGoogle, excludeWorkbenchModel)
  val workbenchGoogleTests: ModuleID =
    "org.broadinstitute.dsde.workbench" %% "workbench-google" % workbenchGoogleV % "test" classifier "tests" excludeAll (excludeWorkbenchUtil, excludeWorkbenchModel)
  val workbenchGoogle2Tests: ModuleID =
    "org.broadinstitute.dsde.workbench" %% "workbench-google2" % workbenchGoogle2V % "test" classifier "tests" excludeAll (excludeWorkbenchUtil, excludeWorkbenchModel)
  val googleStorageLocal: ModuleID =
    "com.google.cloud" % "google-cloud-nio" % "0.127.19" % "test" // needed for mocking google cloud storage. Should use same version as wb-libs

  val liquibaseCore: ModuleID = "org.liquibase" % "liquibase-core" % "4.2.2"

  val circeYAML: ModuleID = "io.circe" %% "circe-yaml" % "0.14.2"
  val snakeYAML: ModuleID = "org.yaml" % "snakeyaml" % "1.33"

  val scalikeCore = "org.scalikejdbc" %% "scalikejdbc" % scalikejdbcVersion
  val scalikeCoreConfig = "org.scalikejdbc" %% "scalikejdbc-config" % scalikejdbcVersion
  val scalikeCoreTest = "org.scalikejdbc" %% "scalikejdbc-test" % scalikejdbcVersion % "test"
  val postgres = "org.postgresql" % "postgresql" % postgresDriverVersion

  val slf4jApi: ModuleID = "org.slf4j" % "slf4j-api" % slf4jVersion
  val slf4jSimple: ModuleID = "org.slf4j" % "slf4j-simple" % slf4jVersion

  val okio: ModuleID = "com.squareup.okio" % "okio" % "3.4.0" excludeAll excludeWorkbenchUtil2

  // pact deps
  val pact4sV = "0.9.0"
  val pact4sScalaTest = "io.github.jbwheatley" %% "pact4s-scalatest" % pact4sV % Test
  val pact4sCirce = "io.github.jbwheatley" %% "pact4s-circe" % pact4sV
  val circeCore = "io.circe" %% "circe-core" % "0.14.4"

  val pact4sDependencies = Seq(
    pact4sScalaTest,
    pact4sCirce,
    circeCore,
    slf4jApi,
    slf4jSimple
  )

  val cloudResourceLib: ModuleID =
    "bio.terra" % "terra-cloud-resource-lib" % crlVersion excludeAll (excludeGoogleServiceUsage, excludeGoogleCloudResourceManager, excludeJerseyCore, excludeJerseyMedia, excludeSLF4J, excludeAwsSdk)
  val azureManagedApplications: ModuleID =
    "com.azure.resourcemanager" % "azure-resourcemanager-managedapplications" % "1.0.0-beta.1"

  def excludeSpringBoot = ExclusionRule("org.springframework.boot")
  def excludeSpringAop = ExclusionRule("org.springframework.spring-aop")
  def excludeSpringData = ExclusionRule("org.springframework.data")
  def excludeSpringFramework = ExclusionRule("org.springframework")
  def excludeOpenCensus = ExclusionRule("io.opencensus")
  def excludeGoogleFindBugs = ExclusionRule("com.google.code.findbugs")
  def excludeBroadWorkbench = ExclusionRule("org.broadinstitute.dsde.workbench")
  def excludeSlf4j = ExclusionRule("org.slf4j")
  def excludePostgresql = ExclusionRule("org.postgresql", "postgresql")
  def excludeSnakeyaml = ExclusionRule("org.yaml", "snakeyaml")
  def excludeLiquibase = ExclusionRule("org.liquibase")
  def excludeKubernetes = ExclusionRule("io.kubernetes", "client-java")
  def tclExclusions(m: ModuleID): ModuleID = m.excludeAll(
    excludeSpringBoot,
    excludeSpringAop,
    excludeSpringData,
    excludeSpringFramework,
    excludeOpenCensus,
    excludeGoogleFindBugs,
    excludeBroadWorkbench,
    excludePostgresql,
    excludeSnakeyaml,
    excludeSlf4j,
    excludeLiquibase,
    excludeKubernetes
  )

  val terraCommonLib = tclExclusions("bio.terra" % "terra-common-lib" % tclVersion classifier "plain")

  // was included transitively before, now explicit
  val commonsCodec: ModuleID = "commons-codec" % "commons-codec" % "1.15"

  val rootDependencies = Seq(
    // proactively pull in latest versions of Jackson libs, instead of relying on the versions
    // specified as transitive dependencies, due to OWASP DependencyCheck warnings for earlier versions.
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
    mockitoScalaTest,
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
    commonsCodec,
    liquibaseCore,
    circeYAML,
    snakeYAML,
    scalikeCore,
    scalikeCoreConfig,
    scalikeCoreTest,
    postgres,
    cloudResourceLib,
    nettyAll,
    reactorNetty,
    azureManagedApplications,
    sentry,
    sentryLogback,
    okio,
    terraCommonLib
  )

  // Needed because it looks like the dependency overrides of wb-libs doesn't propagate to the importing project...
  val rootDependencyOverrides = Seq(
    "org.apache.commons" % "commons-compress" % "1.26.0"
  )
}
