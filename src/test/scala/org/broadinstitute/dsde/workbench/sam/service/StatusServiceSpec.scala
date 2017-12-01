package org.broadinstitute.dsde.workbench.sam.service

import akka.actor.ActorSystem
import com.google.api.services.admin.directory.model.Group
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.directory.MockDirectoryDAO
import org.scalatest.{BeforeAndAfterAll, FlatSpec, FreeSpec, Matchers}
import org.broadinstitute.dsde.workbench.google.mock.MockGoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.model.{WorkbenchException, WorkbenchGroup, WorkbenchEmail, WorkbenchGroupName}
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup
import org.broadinstitute.dsde.workbench.util.health.{StatusCheckResponse, SubsystemStatus, Subsystems}
import org.broadinstitute.dsde.workbench.util.health.Subsystems.{GoogleGroups, OpenDJ}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import org.scalatest.concurrent.Eventually

class StatusServiceSpec extends FreeSpec with Matchers with BeforeAndAfterAll with TestSupport with Eventually {
  implicit val system = ActorSystem("StatusServiceSpec")
  val allUsersEmail = WorkbenchEmail("allusers@example.com")

  override def afterAll(): Unit = {
    system.terminate()
  }

  def noOpenDJGroups = {
    new StatusService(new MockDirectoryDAO, new NoExtensions {
      override def checkStatus: Map[Subsystems.Subsystem, Future[SubsystemStatus]] = Map(Subsystems.GoogleGroups -> Future.successful(SubsystemStatus(true, None)))
    }, pollInterval = 10 milliseconds)
  }

  def ok = {
    val service = noOpenDJGroups
    runAndWait(service.directoryDAO.createGroup(BasicWorkbenchGroup(NoExtensions.allUsersGroupName, Set.empty, allUsersEmail)))
    service
  }

  def failingExtension = {
    val service = new StatusService(new MockDirectoryDAO, new NoExtensions {
      override def checkStatus: Map[Subsystems.Subsystem, Future[SubsystemStatus]] = Map(Subsystems.GoogleGroups -> Future.failed(new WorkbenchException("bad google")))
    })
    runAndWait(service.directoryDAO.createGroup(BasicWorkbenchGroup(NoExtensions.allUsersGroupName, Set.empty, allUsersEmail)))
    service
  }

  def failingOpenDJ = {
    val service = new StatusService(new MockDirectoryDAO {
      override def loadGroupEmail(groupName: WorkbenchGroupName): Future[Option[WorkbenchEmail]] = Future.failed(new WorkbenchException("bad opendj"))
    }, NoExtensions)
    service
  }

  val cases = {
    Seq(
      ("ok", ok, StatusCheckResponse(true, Map(OpenDJ -> SubsystemStatus(true, None), GoogleGroups -> SubsystemStatus(true, None)))),
      ("noOpenDJGroups", noOpenDJGroups, StatusCheckResponse(false, Map(OpenDJ -> SubsystemStatus(false, Option(List(s"could not find group ${NoExtensions.allUsersGroupName} in opendj"))), GoogleGroups -> SubsystemStatus(true, None)))),
      ("failingExtension", failingExtension, StatusCheckResponse(false, Map(OpenDJ -> SubsystemStatus(true, None), GoogleGroups -> SubsystemStatus(false, Option(List(s"bad google")))))),
      ("failingOpenDJ", failingOpenDJ, StatusCheckResponse(false, Map(OpenDJ -> SubsystemStatus(false, Option(List("bad opendj"))))))
    )
  }

  "StatusService" - {
    cases.foreach { case (name, service, expected) =>
      s"should have correct status for $name" in {
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
