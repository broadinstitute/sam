import sbt.Keys._
import sbt._
import complete.DefaultParsers._
import sbt.util.Logger

import scala.sys.process._

object Testing {
  val minnieKenny = inputKey[Unit]("Run minnie-kenny.")

  def isIntegrationTest(name: String) = name contains "integrationtest"

  lazy val IntegrationTest = config("it") extend Test

  /** Run minnie-kenny only once per sbt invocation. */
  class MinnieKennySingleRunner() {
    private val mutex = new Object
    private var resultOption: Option[Int] = None

    /** Run using the logger, throwing an exception only on the first failure. */
    def runOnce(log: Logger, args: Seq[String]): Unit = {
      mutex synchronized {
        if (resultOption.isEmpty) {
          log.debug(s"Running minnie-kenny.sh${args.mkString(" ", " ", "")}")
          val result = ("./minnie-kenny.sh" +: args) ! log
          resultOption = Option(result)
          if (result == 0)
            log.debug("Successfully ran minnie-kenny.sh")
          else
            sys.error("Running minnie-kenny.sh failed. Please double check for errors above.")
        }
      }
    }
  }

  // Only run one minnie-kenny.sh at a time!
  private lazy val minnieKennySingleRunner = new MinnieKennySingleRunner
  
  val commonTestSettings: Seq[Setting[_]] = List(

    // SLF4J initializes itself upon the first logging call.  Because sbt
    // runs tests in parallel it is likely that a second thread will
    // invoke a second logging call before SLF4J has completed
    // initialization from the first thread's logging call, leading to
    // these messages:
    //   SLF4J: The following loggers will not work because they were created
    //   SLF4J: during the default configuration phase of the underlying logging system.
    //   SLF4J: See also http://www.slf4j.org/codes.html#substituteLogger
    //   SLF4J: com.imageworks.common.concurrent.SingleThreadInfiniteLoopRunner
    //
    // As a workaround, load SLF4J's root logger before starting the unit
    // tests

    // Source: https://github.com/typesafehub/scalalogging/issues/23#issuecomment-17359537
    // References:
    //   http://stackoverflow.com/a/12095245
    //   http://jira.qos.ch/browse/SLF4J-167
    //   http://jira.qos.ch/browse/SLF4J-97
    testOptions in Test += Tests.Setup(classLoader =>
      classLoader
        .loadClass("org.slf4j.LoggerFactory")
        .getMethod("getLogger", classLoader.loadClass("java.lang.String"))
        .invoke(null, "ROOT")
    ),
    testOptions in Test ++= Seq(Tests.Filter(s => !isIntegrationTest(s))),
    testOptions in IntegrationTest := Seq(Tests.Filter(s => isIntegrationTest(s))),

    minnieKenny := {
      val log = streams.value.log
      val args = spaceDelimited("<arg>").parsed
      minnieKennySingleRunner.runOnce(log, args)
    },

    parallelExecution in Test := false,
	
    (test in Test) := {
      minnieKenny.toTask("").value
    },
  )

  implicit class ProjectTestSettings(val project: Project) extends AnyVal {
    def withTestSettings: Project = project
      .configs(IntegrationTest).settings(inConfig(IntegrationTest)(Defaults.testTasks): _*)
  }
}

