package org.broadinstitute.dsde.workbench.sam.google

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.testkit.TestKit
import org.broadinstitute.dsde.workbench.google.mock.MockGooglePubSubDAO
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchEmail, WorkbenchExceptionWithErrorReport, WorkbenchGroupIdentity, WorkbenchGroupName}
import org.broadinstitute.dsde.workbench.sam.{TestSupport, _}
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.scalatest.BeforeAndAfterAll
import org.scalatestplus.mockito.MockitoSugar
import org.mockito.ArgumentMatchers.{ eq => mockitoEq }

import org.mockito.Mockito._
import org.mockito.ArgumentMatchers.any
import org.scalatest.concurrent.Eventually

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps
import spray.json._
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

class GoogleGroupSyncMonitorSpec(_system: ActorSystem) extends TestKit(_system) with AnyFlatSpecLike with Matchers with TestSupport with MockitoSugar with BeforeAndAfterAll with Eventually {
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
    val mockGoogleExtensions = mock[GoogleGroupSynchronizer](RETURNS_SMART_NULLS)

    val groupToSyncEmail = WorkbenchEmail("testgroup@example.com")
    val groupToSyncId = WorkbenchGroupName("testgroup")
    when(mockGoogleExtensions.synchronizeGroupMembers(mockitoEq(groupToSyncId), any[Set[WorkbenchGroupIdentity]], any[SamRequestContext])).thenReturn(Future.successful(Map(groupToSyncEmail -> Seq.empty[SyncReportItem])))

    val policyToSyncEmail = WorkbenchEmail("testpolicy@example.com")
    val policyToSyncId = FullyQualifiedPolicyId(FullyQualifiedResourceId(ResourceTypeName("rt"), ResourceId("rid")), AccessPolicyName("pname"))
    when(mockGoogleExtensions.synchronizeGroupMembers(mockitoEq(policyToSyncId), any[Set[WorkbenchGroupIdentity]], any[SamRequestContext])).thenReturn(Future.successful(Map(policyToSyncEmail -> Seq.empty[SyncReportItem])))

    val topicName = "testtopic"
    val subscriptionName = "testsub"
    system.actorOf(GoogleGroupSyncMonitorSupervisor.props(10 milliseconds, 0 seconds, mockGooglePubSubDAO, topicName, subscriptionName, 1, mockGoogleExtensions))

    eventually {
      assert(runAndWait(mockGooglePubSubDAO.getTopic(topicName)).isDefined)
    }

    import SamJsonSupport.PolicyIdentityFormat
    import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport.WorkbenchGroupNameFormat
    mockGooglePubSubDAO.publishMessages(topicName, Seq(groupToSyncId.toJson.compactPrint, policyToSyncId.toJson.compactPrint))

    eventually {
      assertResult(2) { mockGooglePubSubDAO.acks.size() }
      verify(mockGoogleExtensions, atLeastOnce).synchronizeGroupMembers(mockitoEq(groupToSyncId), any[Set[WorkbenchGroupIdentity]], any[SamRequestContext])
      verify(mockGoogleExtensions, atLeastOnce).synchronizeGroupMembers(mockitoEq(policyToSyncId), any[Set[WorkbenchGroupIdentity]], any[SamRequestContext])
    }
  }

  it should "handle missing target group" in {
    implicit val patienceConfig = PatienceConfig(timeout = 1 second)
    val mockGooglePubSubDAO = new MockGooglePubSubDAO
    val mockGoogleExtensions = mock[GoogleGroupSynchronizer](RETURNS_SMART_NULLS)

    val groupToSyncEmail = WorkbenchEmail("testgroup@example.com")
    val groupToSyncId = WorkbenchGroupName("testgroup")
    when(mockGoogleExtensions.synchronizeGroupMembers(mockitoEq(groupToSyncId), any[Set[WorkbenchGroupIdentity]], any[SamRequestContext])).thenReturn(Future.failed(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "not found"))))

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
