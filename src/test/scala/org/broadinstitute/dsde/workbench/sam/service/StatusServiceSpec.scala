package org.broadinstitute.dsde.workbench.sam.service

import akka.actor.ActorSystem
import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchException, WorkbenchGroupName}
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.db.{DatabaseNames, DbReference}
import org.broadinstitute.dsde.workbench.sam.directory.{DirectoryDAO, MockDirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.broadinstitute.dsde.workbench.util.health.Subsystems.{Database, GoogleGroups, OpenDJ}
import org.broadinstitute.dsde.workbench.util.health.{StatusCheckResponse, SubsystemStatus, Subsystems}
import org.scalatest.concurrent.Eventually
import org.scalatest.{BeforeAndAfterAll, FreeSpec, Matchers}
import scalikejdbc.config.DBs

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

class StatusServiceSpec extends FreeSpec with Matchers with BeforeAndAfterAll with TestSupport with Eventually {
  implicit val system = ActorSystem("StatusServiceSpec")
  val allUsersEmail = WorkbenchEmail("allusers@example.com")
  val dbReference = TestSupport.dbRef

  override def afterAll(): Unit = {
    system.terminate()
  }

  private def newStatusService(directoryDAO: DirectoryDAO) = {
    new StatusService(directoryDAO, new NoExtensions {
      override def checkStatus: Map[Subsystems.Subsystem, Future[SubsystemStatus]] = Map(Subsystems.GoogleGroups -> Future.successful(SubsystemStatus(true, None)))
    }, dbReference, pollInterval = 10 milliseconds)
  }

  private def directoryDAOWithAllUsersGroup = {
    val directoryDAO = new MockDirectoryDAO
    directoryDAO.createGroup(BasicWorkbenchGroup(NoExtensions.allUsersGroupName, Set.empty, allUsersEmail)).unsafeRunSync()
    directoryDAO
  }

  private def noOpenDJGroups = newStatusService(new MockDirectoryDAO)

  private def ok = newStatusService(directoryDAOWithAllUsersGroup)

  private def failingExtension = {
    val service = new StatusService(directoryDAOWithAllUsersGroup, new NoExtensions {
      override def checkStatus: Map[Subsystems.Subsystem, Future[SubsystemStatus]] = Map(Subsystems.GoogleGroups -> Future.failed(new WorkbenchException("bad google")))
    }, dbReference, pollInterval = 10 milliseconds)
    service
  }

  private def failingOpenDJ = {
    val service = new StatusService(new MockDirectoryDAO {
      override def loadGroupEmail(groupName: WorkbenchGroupName, samRequestContext: SamRequestContext): IO[Option[WorkbenchEmail]] = IO.raiseError(new WorkbenchException("bad opendj"))
    }, NoExtensions, dbReference, pollInterval = 10 milliseconds)
    service
  }

  private def failingDatabase = {
    // background database configured to connect to non existent database
    val service = new StatusService(directoryDAOWithAllUsersGroup, NoExtensions, DbReference(DatabaseNames.Background), pollInterval = 10 milliseconds)
    service
  }

  val cases = {
    Seq(
      ("ok", ok, StatusCheckResponse(true, Map(OpenDJ -> SubsystemStatus(true, None), GoogleGroups -> SubsystemStatus(true, None), Database -> SubsystemStatus(true, None)))),
      ("noOpenDJGroups", noOpenDJGroups, StatusCheckResponse(false, Map(OpenDJ -> SubsystemStatus(false, Option(List(s"could not find group ${NoExtensions.allUsersGroupName} in opendj"))), GoogleGroups -> SubsystemStatus(true, None), Database -> SubsystemStatus(true, None)))),
      ("failingExtension", failingExtension, StatusCheckResponse(false, Map(OpenDJ -> SubsystemStatus(true, None), GoogleGroups -> SubsystemStatus(false, Option(List(s"bad google"))), Database -> SubsystemStatus(true, None)))),
      ("failingOpenDJ", failingOpenDJ, StatusCheckResponse(false, Map(OpenDJ -> SubsystemStatus(false, Option(List("bad opendj"))), Database -> SubsystemStatus(true, None)))),
      ("failingDatabase", failingDatabase, StatusCheckResponse(false, Map(OpenDJ -> SubsystemStatus(true, None), Database -> SubsystemStatus(false, Option(List("The connection attempt failed."))))))
    )
  }

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
