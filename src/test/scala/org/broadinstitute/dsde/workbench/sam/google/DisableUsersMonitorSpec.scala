package org.broadinstitute.dsde.workbench.sam.google

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.testkit.TestKit
import org.broadinstitute.dsde.workbench.google.errorReportSource
import org.broadinstitute.dsde.workbench.google.mock.MockGooglePubSubDAO
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.model.{UserStatus, UserStatusDetails}
import org.broadinstitute.dsde.workbench.sam.service.UserService
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.broadinstitute.dsde.workbench.sam.{PropertyBasedTesting, TestSupport}
import org.mockito.ArgumentMatchers.{any, eq => mockitoEq}
import org.mockito.Mockito._
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class DisableUsersMonitorSpec(_system: ActorSystem) extends TestKit(_system) with AnyFlatSpecLike with Matchers with PropertyBasedTesting
  with TestSupport with MockitoSugar with BeforeAndAfter with BeforeAndAfterAll with Eventually {
  override implicit val patienceConfig: PatienceConfig = PatienceConfig(timeout = 1 second)
  implicit override val generatorDrivenConfig: PropertyCheckConfiguration = PropertyCheckConfiguration(minSuccessful = 100)

  def this() = this(ActorSystem("DisableUsersMonitorSpec"))

  val blockedDomain = "blocked.domain.com"
  val topicName = "testtopic"
  val subscriptionName = "testsub"

  var googleExtensions: GoogleExtensions = _
  var userService: UserService = mock[UserService](RETURNS_SMART_NULLS)
  var mockGooglePubSubDAO: MockGooglePubSubDAO = _

  val userId: WorkbenchUserId = WorkbenchUserId("1")
  val userEmail: WorkbenchEmail = WorkbenchEmail("blah@blah.com")

  override def beforeAll(): Unit = {
    super.beforeAll()
    googleExtensions = mock[GoogleExtensions](RETURNS_SMART_NULLS)
    mockGooglePubSubDAO = new MockGooglePubSubDAO
    mockGooglePubSubDAO.createTopic(topicName)

    eventually {
      assert(runAndWait(mockGooglePubSubDAO.getTopic(topicName)).isDefined)
    }
  }

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  it should "handle disable users message" in {
    system.actorOf(DisableUsersMonitorSupervisor.props(10 milliseconds, 0 seconds, mockGooglePubSubDAO, topicName, subscriptionName, 1, userService))
    when(userService.disableUser(mockitoEq(userId), any[SamRequestContext]))
      .thenReturn(Future.successful(
        Some(UserStatus(
          UserStatusDetails(userId, userEmail),
          Map(userEmail.value -> false)
        ))
      ))

    mockGooglePubSubDAO.publishMessages(topicName, Seq(userId.value))

    // `eventually` now requires an implicit `Retrying` instance. When the statement inside returns future, it'll

    // try to use `Retrying[Future[T]]`, which gets weird when we're using mockito together with it.
    // Hence adding ascribing [Unit] explicitly here so that `eventually` will use `Retrying[Unit]`
    eventually[Unit] {
      assertResult(1) { mockGooglePubSubDAO.acks.size() }
      verify(userService, atLeastOnce).disableUser(mockitoEq(userId), any[SamRequestContext])
      ()
    }
  }

  it should "handle missing user" in {
    system.actorOf(DisableUsersMonitorSupervisor.props(10 milliseconds, 0 seconds, mockGooglePubSubDAO, topicName, subscriptionName, 1, userService))
    when(userService.disableUser(mockitoEq(userId), any[SamRequestContext])).thenReturn(Future.failed(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "not found"))))

    mockGooglePubSubDAO.publishMessages(topicName, Seq(userId.value))

    // `eventually` now requires an implicit `Retrying` instance. When the statement inside returns future, it'll

    // try to use `Retrying[Future[T]]`, which gets weird when we're using mockito together with it.
    // Hence adding ascribing [Unit] explicitly here so that `eventually` will use `Retrying[Unit]`
    eventually {
      assertResult(1) { mockGooglePubSubDAO.acks.size() }
    }
  }
}
