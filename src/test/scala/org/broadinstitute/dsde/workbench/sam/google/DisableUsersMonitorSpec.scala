package org.broadinstitute.dsde.workbench.sam.google

import akka.actor.ActorSystem
import akka.testkit.TestKit
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

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

class DisableUsersMonitorSpec(_system: ActorSystem) extends TestKit(_system) with AnyFlatSpecLike with Matchers with PropertyBasedTesting
  with TestSupport with MockitoSugar with BeforeAndAfter with BeforeAndAfterAll with Eventually {
  implicit override val generatorDrivenConfig: PropertyCheckConfiguration = PropertyCheckConfiguration(minSuccessful = 100)
  def this() = this(ActorSystem("DisableUsersMonitorSpec"))

  val topicName = "testtopic"
  val subscriptionName = "testsub"

  override def beforeAll(): Unit = {
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  it should "handle disable users message" in {
    implicit val patienceConfig: PatienceConfig = PatienceConfig(timeout = 1 second)

    val userService: UserService = mock[UserService](RETURNS_SMART_NULLS)
    val mockGooglePubSubDAO: MockGooglePubSubDAO = new MockGooglePubSubDAO

    val userId: WorkbenchUserId = WorkbenchUserId("1")
    val userEmail: WorkbenchEmail = WorkbenchEmail("blah@blah.com")

    mockGooglePubSubDAO.createTopic(topicName)

    system.actorOf(DisableUsersMonitorSupervisor.props(10 milliseconds, 0 seconds, mockGooglePubSubDAO, topicName, subscriptionName, 1, userService))
    eventually {
      assert(mockGooglePubSubDAO.subscriptionsByName.contains(subscriptionName))
    }
    when(userService.disableUser(mockitoEq(userId), any[SamRequestContext]))
      .thenReturn(Future.successful(
        Some(UserStatus(
          UserStatusDetails(userId, userEmail),
          Map(userEmail.value -> false)
        ))
      ))

    Await.result(mockGooglePubSubDAO.publishMessages(topicName, Seq(userId.value)), Duration.Inf)

    eventually {
      verify(userService, atLeastOnce).disableUser(mockitoEq(userId), any[SamRequestContext])
      assertResult(1) { mockGooglePubSubDAO.acks.size() }
    }
  }

  it should "handle missing user" in {
    implicit val patienceConfig: PatienceConfig = PatienceConfig(timeout = 1 second)

    val userService: UserService = mock[UserService](RETURNS_SMART_NULLS)
    val mockGooglePubSubDAO: MockGooglePubSubDAO = new MockGooglePubSubDAO

    val userId: WorkbenchUserId = WorkbenchUserId("1")

    mockGooglePubSubDAO.createTopic(topicName)

    system.actorOf(DisableUsersMonitorSupervisor.props(10 milliseconds, 0 seconds, mockGooglePubSubDAO, topicName, subscriptionName, 1, userService))
    eventually {
      assert(mockGooglePubSubDAO.subscriptionsByName.contains(subscriptionName))
    }
    when(userService.disableUser(mockitoEq(userId), any[SamRequestContext])).thenReturn(Future.successful(None))

    mockGooglePubSubDAO.publishMessages(topicName, Seq(userId.value))

    eventually {
      assertResult(1) { mockGooglePubSubDAO.acks.size() }
    }
  }
}
