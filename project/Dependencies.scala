import sbt._

object Dependencies {
  val akkaV = "2.6.10"
  val akkaHttpV = "10.2.6"
  val jacksonV = "2.9.5"
  val scalaLoggingV = "3.9.2"
  val scalaTestV    = "3.2.3"
  val scalaCheckV    = "1.14.0"
  val scalikejdbcVersion    = "3.4.1"
  val postgresDriverVersion = "42.3.3"
  val http4sVersion = "0.21.13"

  val workbenchUtilV   = "0.6-74c9fc2"
  val workbenchUtil2V   = "0.1-74c9fc2"
  val workbenchModelV  = "0.15-f9f0d4c"
  val workbenchGoogleV = "0.21-51d7fff"
  val workbenchGoogle2V = "0.21-9d25534"
  val workbenchNotificationsV = "0.3-d74ff96"
  val monocleVersion = "2.0.3"

  val excludeAkkaActor =        ExclusionRule(organization = "com.typesafe.akka", name = "akka-actor_2.12")
  val excludeAkkaProtobufV3 =   ExclusionRule(organization = "com.typesafe.akka", name = "akka-protobuf-v3_2.12")
  val excludeAkkaStream =       ExclusionRule(organization = "com.typesafe.akka", name = "akka-stream_2.12")
  val excludeWorkbenchUtil =    ExclusionRule(organization = "org.broadinstitute.dsde.workbench", name = "workbench-util_2.12")
  val excludeWorkbenchModel =   ExclusionRule(organization = "org.broadinstitute.dsde.workbench", name = "workbench-model_2.12")
  val excludeWorkbenchMetrics = ExclusionRule(organization = "org.broadinstitute.dsde.workbench", name = "workbench-metrics_2.12")
  val excludeWorkbenchGoogle =  ExclusionRule(organization = "org.broadinstitute.dsde.workbench", name = "workbench-google_2.12")

  val jacksonAnnotations: ModuleID = "com.fasterxml.jackson.core" % "jackson-annotations" % jacksonV
  val jacksonDatabind: ModuleID =    "com.fasterxml.jackson.core" % "jackson-databind"    % jacksonV
  val jacksonCore: ModuleID =        "com.fasterxml.jackson.core" % "jackson-core"        % jacksonV

  val logstashLogback: ModuleID = "net.logstash.logback"      % "logstash-logback-encoder" % "6.6"
  val logbackClassic: ModuleID = "ch.qos.logback"             %  "logback-classic" % "1.2.10"
  val ravenLogback: ModuleID =   "com.getsentry.raven"        %  "raven-logback"   % "7.8.6"
  val scalaLogging: ModuleID =   "com.typesafe.scala-logging" %% "scala-logging"   % scalaLoggingV
  val swaggerUi: ModuleID =      "org.webjars"                %  "swagger-ui"      % "4.1.2"
  val ficus: ModuleID =          "com.iheart"                 %% "ficus"           % "1.5.0"

  val akkaActor: ModuleID =         "com.typesafe.akka"   %%  "akka-actor"           % akkaV
  val akkaSlf4j: ModuleID =         "com.typesafe.akka"   %%  "akka-slf4j"           % akkaV
  val akkaStream: ModuleID =        "com.typesafe.akka"   %%  "akka-stream"          % akkaV               excludeAll(excludeAkkaProtobufV3)
  val akkaHttp: ModuleID =          "com.typesafe.akka"   %%  "akka-http"            % akkaHttpV           excludeAll(excludeAkkaActor)
  val akkaHttpSprayJson: ModuleID = "com.typesafe.akka"   %%  "akka-http-spray-json" % akkaHttpV
  val akkaTestKit: ModuleID =       "com.typesafe.akka"   %%  "akka-testkit"         % akkaV     % "test"
  val akkaHttpTestKit: ModuleID =   "com.typesafe.akka"   %%  "akka-http-testkit"    % akkaHttpV % "test"
  val scalaCheck: ModuleID =        "org.scalacheck"      %%  "scalacheck"           % scalaCheckV % "test"

  val excludIoGrpc =  ExclusionRule(organization = "io.grpc", name = "grpc-core")
  val ioGrpc: ModuleID = "io.grpc" % "grpc-core" % "1.34.0"

  val googleOAuth2: ModuleID = "com.google.auth" % "google-auth-library-oauth2-http" % "0.18.0" excludeAll(excludIoGrpc)
  val googleStorage: ModuleID = "com.google.apis" % "google-api-services-storage" % "v1-rev20181013-1.27.0" excludeAll(excludIoGrpc) //force this version

  val monocle: ModuleID = "com.github.julien-truffaut" %%  "monocle-core"  % monocleVersion
  val monocleMacro: ModuleID = "com.github.julien-truffaut" %%  "monocle-macro" % monocleVersion

  val scalaTest: ModuleID =       "org.scalatest" %% "scalatest"    % scalaTestV % "test"
  val scalaTestScalaCheck = "org.scalatestplus" %% "scalacheck-1-15" % s"${scalaTestV}.0" % Test
  val scalaTestMockito = "org.scalatestplus" %% "mockito-3-4" % s"${scalaTestV}.0" % Test

  val unboundid: ModuleID = "com.unboundid" % "unboundid-ldapsdk" % "4.0.12"

  // All of workbench-libs pull in Akka; exclude it since we provide our own Akka dependency.
  // workbench-google pulls in workbench-{util, model, metrics}; exclude them so we can control the library versions individually.
  val workbenchUtil: ModuleID =      "org.broadinstitute.dsde.workbench" %% "workbench-util"   % workbenchUtilV excludeAll(excludeWorkbenchModel)
  val workbenchUtil2: ModuleID =      "org.broadinstitute.dsde.workbench" %% "workbench-util2"   % workbenchUtil2V excludeAll(excludeWorkbenchModel)
  val workbenchModel: ModuleID =     "org.broadinstitute.dsde.workbench" %% "workbench-model"  % workbenchModelV
  val workbenchGoogle: ModuleID =    "org.broadinstitute.dsde.workbench" %% "workbench-google" % workbenchGoogleV excludeAll(excludeWorkbenchModel, excludeWorkbenchUtil)
  // the name of the auto-value package changed from auto-value to auto-value-annotations so old libraries are not evicted
  // leading to merge errors during sbt assembly. At this time the old version of auto-value is pulled in through the google2
  // workbench-libs dependency so exclude auto-value from there
  val excludGoogleAutoValue =  ExclusionRule(organization = "com.google.auto.value", name = "auto-value")
  val workbenchGoogle2: ModuleID =    "org.broadinstitute.dsde.workbench" %% "workbench-google2" % workbenchGoogle2V excludeAll(excludeWorkbenchModel, excludeWorkbenchUtil, excludGoogleAutoValue)
  val workbenchNotifications: ModuleID =  "org.broadinstitute.dsde.workbench" %% "workbench-notifications" % workbenchNotificationsV excludeAll(excludeWorkbenchGoogle, excludeWorkbenchModel)
  val workbenchGoogleTests: ModuleID =    "org.broadinstitute.dsde.workbench" %% "workbench-google" % workbenchGoogleV % "test" classifier "tests" excludeAll(excludeWorkbenchUtil, excludeWorkbenchModel)
  val workbenchGoogle2Tests: ModuleID =    "org.broadinstitute.dsde.workbench" %% "workbench-google2" % workbenchGoogle2V % "test" classifier "tests" excludeAll(excludeWorkbenchUtil, excludeWorkbenchModel)
  val googleStorageLocal: ModuleID = "com.google.cloud" % "google-cloud-nio" % "0.122.3" % "test" //needed for mocking google cloud storage

  val liquibaseCore: ModuleID = "org.liquibase" % "liquibase-core" % "4.2.2"

  val circeYAML: ModuleID = "io.circe" %% "circe-yaml" % "0.13.1"

  val scalikeCore =       "org.scalikejdbc"                   %% "scalikejdbc"         % scalikejdbcVersion
  val scalikeCoreConfig = "org.scalikejdbc"                   %% "scalikejdbc-config"  % scalikejdbcVersion
  val scalikeCoreTest =   "org.scalikejdbc"                   %% "scalikejdbc-test"    % scalikejdbcVersion   % "test"
  val postgres = "org.postgresql"                    %  "postgresql"          % postgresDriverVersion

  val excludeScalaCllectionCompat =  ExclusionRule(organization = "org.scala-lang.modules", name = "scala-collection-compat_2.12")
  val opencensusScalaCode: ModuleID = "com.github.sebruck" %% "opencensus-scala-core" % "0.7.2" // excludeAll(excludIoGrpc, excludeCatsEffect )
  val opencensusAkkaHttp: ModuleID = "com.github.sebruck" %% "opencensus-scala-akka-http" % "0.7.2" excludeAll(excludeAkkaProtobufV3, excludeAkkaStream)// excludeAll(excludIoGrpc, excludeCatsEffect)
  val opencensusStackDriverExporter: ModuleID = "io.opencensus" % "opencensus-exporter-trace-stackdriver" % "0.25.0" // excludeAll(excludIoGrpc, excludeCatsEffect)
  val opencensusLoggingExporter: ModuleID = "io.opencensus" % "opencensus-exporter-trace-logging"     % "0.25.0" // excludeAll(excludIoGrpc, excludeCatsEffect)

  val openCensusDependencies = Seq(
    opencensusScalaCode,
    opencensusAkkaHttp,
    opencensusStackDriverExporter,
    opencensusLoggingExporter
  )

  // was included transitively before, now explicit
  val commonsCodec: ModuleID = "commons-codec" % "commons-codec" % "1.13"

  val rootDependencies = Seq(
    // proactively pull in latest versions of Jackson libs, instead of relying on the versions
    // specified as transitive dependencies, due to OWASP DependencyCheck warnings for earlier versions.
    ioGrpc,
    logbackClassic,
    logstashLogback,
    ravenLogback,
    scalaLogging,
    swaggerUi,
    ficus,

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

    unboundid,
    commonsCodec,

    liquibaseCore,

    circeYAML,

    scalikeCore,
    scalikeCoreConfig,
    scalikeCoreTest,
    postgres
  ) ++ openCensusDependencies
}
