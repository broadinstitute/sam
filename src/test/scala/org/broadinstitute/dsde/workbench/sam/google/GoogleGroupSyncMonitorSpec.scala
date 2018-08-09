package org.broadinstitute.dsde.workbench.sam.google

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.testkit.TestKit
import org.broadinstitute.dsde.workbench.google.mock.MockGooglePubSubDAO
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchExceptionWithErrorReport, WorkbenchEmail, WorkbenchGroupName}
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.model._
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import org.scalatest.mockito.MockitoSugar
import org.mockito.Mockito._
import org.scalatest.concurrent.Eventually

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps
import spray.json._

class GoogleGroupSyncMonitorSpec(_system: ActorSystem) extends TestKit(_system) with FlatSpecLike with Matchers with TestSupport with MockitoSugar with BeforeAndAfterAll with Eventually {
  def this() = this(ActorSystem("GoogleGroupSyncMonitorSpec"))

  override def beforeAll(): Unit = {
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  "GoogleGroupSyncMonitor" should "handle sync message" in {
    implicit val patienceConfig = PatienceConfig(timeout = 1 second)
    val mockGooglePubSubDAO = new MockGooglePubSubDAO
    val mockGoogleExtensions = mock[GoogleExtensions]

    val groupToSyncEmail = WorkbenchEmail("testgroup@example.com")
    val groupToSyncId = WorkbenchGroupName("testgroup")
    when(mockGoogleExtensions.synchronizeGroupMembers(groupToSyncId)).thenReturn(Future.successful(Map(groupToSyncEmail -> Seq.empty[SyncReportItem])))

    val policyToSyncEmail = WorkbenchEmail("testpolicy@example.com")
    val policyToSyncId = ResourceAndPolicyName(Resource(ResourceTypeName("rt"), ResourceId("rid")), AccessPolicyName("pname"))
    when(mockGoogleExtensions.synchronizeGroupMembers(policyToSyncId)).thenReturn(Future.successful(Map(policyToSyncEmail -> Seq.empty[SyncReportItem])))

    val topicName = "testtopic"
    val subscriptionName = "testsub"
    system.actorOf(GoogleGroupSyncMonitorSupervisor.props(10 milliseconds, 0 seconds, mockGooglePubSubDAO, topicName, subscriptionName, 1, mockGoogleExtensions))

    eventually {
      assert(runAndWait(mockGooglePubSubDAO.getTopic(topicName)).isDefined)
    }

    import SamJsonSupport.ResourceAndPolicyNameFormat
    import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport.WorkbenchGroupNameFormat
    mockGooglePubSubDAO.publishMessages(topicName, Seq(groupToSyncId.toJson.compactPrint, policyToSyncId.toJson.compactPrint))

    eventually {
      assertResult(2) { mockGooglePubSubDAO.acks.size() }
      verify(mockGoogleExtensions, atLeastOnce).synchronizeGroupMembers(groupToSyncId)
      verify(mockGoogleExtensions, atLeastOnce).synchronizeGroupMembers(policyToSyncId)
    }
  }

  it should "handle missing target group" in {
    implicit val patienceConfig = PatienceConfig(timeout = 1 second)
    val mockGooglePubSubDAO = new MockGooglePubSubDAO
    val mockGoogleExtensions = mock[GoogleExtensions]

    val groupToSyncEmail = WorkbenchEmail("testgroup@example.com")
    val groupToSyncId = WorkbenchGroupName("testgroup")
    when(mockGoogleExtensions.synchronizeGroupMembers(groupToSyncId)).thenReturn(Future.failed(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "not found"))))

    val topicName = "testtopic"
    val subscriptionName = "testsub"
    system.actorOf(GoogleGroupSyncMonitorSupervisor.props(10 milliseconds, 0 seconds, mockGooglePubSubDAO, topicName, subscriptionName, 1, mockGoogleExtensions))

    eventually {
      assert(runAndWait(mockGooglePubSubDAO.getTopic(topicName)).isDefined)
    }

    import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport.WorkbenchGroupNameFormat
    mockGooglePubSubDAO.publishMessages(topicName, Seq(groupToSyncId.toJson.compactPrint))

    eventually {
      assertResult(1) { mockGooglePubSubDAO.acks.size() }
    }
  }
}
