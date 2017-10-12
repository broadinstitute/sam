package org.broadinstitute.dsde.workbench.sam.service

import akka.actor.ActorSystem
import com.google.api.services.admin.directory.model.Group
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.directory.MockDirectoryDAO
import org.scalatest.{FlatSpec, FreeSpec, Matchers}
import org.broadinstitute.dsde.workbench.google.mock.MockGoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.model.{WorkbenchException, WorkbenchGroup, WorkbenchGroupEmail, WorkbenchGroupName}
import org.broadinstitute.dsde.workbench.util.health.{StatusCheckResponse, SubsystemStatus}
import org.broadinstitute.dsde.workbench.util.health.Subsystems.{GoogleGroups, OpenDJ}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import org.scalatest.concurrent.Eventually

class StatusServiceSpec extends FreeSpec with Matchers with TestSupport with Eventually {
  implicit val system = ActorSystem("StatusServiceSpec")
  val allUsersEmail = WorkbenchGroupEmail("allusers@example.com")

  def noOpenDJGroups = {
    new StatusService(new MockDirectoryDAO, new MockGoogleDirectoryDAO, pollInterval = 10 milliseconds)
  }

  def noGoogleGroup = {
    val service = noOpenDJGroups
    runAndWait(service.directoryDAO.createGroup(WorkbenchGroup(UserService.allUsersGroupName, Set.empty, allUsersEmail)))
    service
  }

  def ok = {
    val service = noGoogleGroup
    runAndWait(service.googleDirectoryDAO.createGroup(UserService.allUsersGroupName, allUsersEmail))
    service
  }

  def failingGoogle = {
    val service = new StatusService(new MockDirectoryDAO, new MockGoogleDirectoryDAO {
      override def getGoogleGroup(groupEmail: WorkbenchGroupEmail): Future[Option[Group]] = Future.failed(new WorkbenchException("bad google"))
    })
    runAndWait(service.directoryDAO.createGroup(WorkbenchGroup(UserService.allUsersGroupName, Set.empty, allUsersEmail)))
    service
  }

  def failingOpenDJ = {
    val service = new StatusService(new MockDirectoryDAO {
      override def loadGroupEmail(groupName: WorkbenchGroupName): Future[Option[WorkbenchGroupEmail]] = Future.failed(new WorkbenchException("bad opendj"))
    }, new MockGoogleDirectoryDAO)
    service
  }

  val cases = {
    Seq(
      ("ok", ok, StatusCheckResponse(true, Map(OpenDJ -> SubsystemStatus(true, None), GoogleGroups -> SubsystemStatus(true, None)))),
      ("noOpenDJGroups", noOpenDJGroups, StatusCheckResponse(false, Map(OpenDJ -> SubsystemStatus(false, Option(List(s"could not find group ${UserService.allUsersGroupName} in opendj"))), GoogleGroups -> SubsystemStatus(false, Option(List("Unknown status")))))),
      ("noGoogleGroup", noGoogleGroup, StatusCheckResponse(false, Map(OpenDJ -> SubsystemStatus(true, None), GoogleGroups -> SubsystemStatus(false, Option(List(s"could not find group $allUsersEmail in google")))))),
      ("failingGoogle", failingGoogle, StatusCheckResponse(false, Map(OpenDJ -> SubsystemStatus(true, None), GoogleGroups -> SubsystemStatus(false, Option(List(s"bad google")))))),
      ("failingOpenDJ", failingOpenDJ, StatusCheckResponse(false, Map(OpenDJ -> SubsystemStatus(false, Option(List("bad opendj"))), GoogleGroups -> SubsystemStatus(false, Option(List(s"Unknown status"))))))
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
