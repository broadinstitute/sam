package org.broadinstitute.dsde.workbench.sam.service

import akka.actor.ActorSystem
import cats.effect.unsafe.implicits.{global => globalEc}
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchException}
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.TestSupport.appConfig
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, MockDirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.db.{DatabaseNames, TestDbReference}
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.broadinstitute.dsde.workbench.util.health.Subsystems.{Database, GoogleGroups}
import org.broadinstitute.dsde.workbench.util.health.{StatusCheckResponse, SubsystemStatus, Subsystems}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import scalikejdbc.config.DBs

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

class StatusServiceSpec extends AnyFreeSpec with Matchers with BeforeAndAfterAll with TestSupport with Eventually {
  implicit val system = ActorSystem("StatusServiceSpec")
  val allUsersEmail = WorkbenchEmail("allusers@example.com")
  val dbReference = TestSupport.dbRef

  override def afterAll(): Unit =
    system.terminate()

  private def newStatusService(directoryDAO: DirectoryDAO) =
    new StatusService(
      directoryDAO,
      new NoServicesTrait {
        override def checkStatus: Map[Subsystems.Subsystem, Future[SubsystemStatus]] =
          Map(Subsystems.GoogleGroups -> Future.successful(SubsystemStatus(true, None)))
      },
      dbReference,
      pollInterval = 10 milliseconds
    )

  private def directoryDAOWithAllUsersGroup(response: Boolean = true) = {
    val directoryDAO = new MockDirectoryDAO {
      override def checkStatus(samRequestContext: SamRequestContext): Boolean = response
    }
    directoryDAO
      .createGroup(BasicWorkbenchGroup(CloudServices.allUsersGroupName, Set.empty, allUsersEmail), samRequestContext = samRequestContext)
      .unsafeRunSync()
    directoryDAO
  }

  private def ok = newStatusService(directoryDAOWithAllUsersGroup())

  private def failingExtension = {
    val service = new StatusService(
      directoryDAOWithAllUsersGroup(),
      new NoServicesTrait {
        override def checkStatus: Map[Subsystems.Subsystem, Future[SubsystemStatus]] =
          Map(Subsystems.GoogleGroups -> Future.failed(new WorkbenchException("bad google")))
      },
      dbReference,
      pollInterval = 10 milliseconds
    )
    service
  }

  private def failingDatabase = {
    // background database configured to connect to non existent database
    val dbReferenceOverride = new TestDbReference(appConfig.samDatabaseConfig.samBackground.dbName, TestSupport.blockingEc)
    val service = new StatusService(directoryDAOWithAllUsersGroup(false), NoServices, dbReferenceOverride, pollInterval = 10 milliseconds)
    service
  }

  val cases =
    Seq(
      ("ok", ok, StatusCheckResponse(true, Map(GoogleGroups -> SubsystemStatus(true, None), Database -> SubsystemStatus(true, None)))),
      (
        "failingExtension",
        failingExtension,
        StatusCheckResponse(false, Map(GoogleGroups -> SubsystemStatus(false, Option(List(s"bad google"))), Database -> SubsystemStatus(true, None)))
      ),
      (
        "failingDatabase",
        failingDatabase,
        StatusCheckResponse(false, Map(Database -> SubsystemStatus(false, Option(List("Postgres database connection invalid or timed out checking")))))
      )
    )

  "StatusService" - {
    cases.foreach { case (name, service, expected) =>
      s"should have correct status for $name" in {
        if (name == "failingDatabase") DBs.setup(DatabaseNames.Background.name)
        implicit val patienceConfig = PatienceConfig(timeout = 1 second)
        eventually {
          assertResult(expected) {
            runAndWait(service.getStatus())
          }
        }
      }
    }
  }

}
