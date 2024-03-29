package org.broadinstitute.dsde.workbench.sam.api

import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpMethods.GET
import akka.http.scaladsl.model.{HttpRequest, StatusCodes}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.testkit.TestKitBase
import org.broadinstitute.dsde.workbench.auth.{AuthToken, ServiceAccountAuthTokenFromJson, ServiceAccountAuthTokenFromPem}
import org.broadinstitute.dsde.workbench.config.{Credentials, ServiceTestConfig, UserPool}
import org.broadinstitute.dsde.workbench.dao.Google.googleDirectoryDAO
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.SamConfig
import org.broadinstitute.dsde.workbench.service.SamModel._
import org.broadinstitute.dsde.workbench.service._
import org.broadinstitute.dsde.workbench.service.test.CleanUp
import org.broadinstitute.dsde.workbench.service.util.Tags
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Seconds, Span}

import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration._

class SamApiSpec extends AnyFreeSpec with Matchers with ScalaFutures with CleanUp with Eventually with TestKitBase {
  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = scaled(Span(5, Seconds)))
  val billingAccountId: String = ServiceTestConfig.Projects.billingAccountId
  implicit lazy val system = ActorSystem()

  val gcsConfig = SamConfig.GCS

  def registerAsNewUser(email: WorkbenchEmail)(implicit authToken: AuthToken): Unit = {
    val newUserProfile = Orchestration.profile.BasicProfile(
      firstName = "Generic",
      lastName = "Testerson",
      title = "User",
      contactEmail = Option(email.value),
      institute = "Broad",
      institutionalProgram = "DSP",
      programLocationCity = "Cambridge",
      programLocationState = "MA",
      programLocationCountry = "USA",
      pi = "Albus Dumbledore",
      nonProfitStatus = "true"
    )
    Orchestration.profile.registerUser(newUserProfile)
  }

  def removeUser(subjectId: String): Unit = {
    implicit val token: AuthToken = UserPool.chooseAdmin.makeAuthToken()
    if (Sam.admin.doesUserExist(subjectId).getOrElse(false)) {
      Sam.admin.deleteUser(subjectId)
    }
    Thurloe.keyValuePairs.deleteAll(subjectId)
  }

  "Sam" - {
    "should return terms of services with auth token" in {
      val anyUser: Credentials = UserPool.chooseAnyUser
      val userAuthToken: AuthToken = anyUser.makeAuthToken()

      val response = Sam.getRequest(Sam.url + s"tos/text")(userAuthToken)
      val textFuture = Unmarshal(response.entity).to[String]

      response.status shouldEqual StatusCodes.OK
      whenReady(textFuture) { text =>
        text.isEmpty shouldBe false
      }
    }

    "should return terms of services with no auth token" in {
      val req = HttpRequest(GET, Sam.url + s"tos/text")
      val response = Sam.sendRequest(req)

      val textFuture = Unmarshal(response.entity).to[String]

      response.status shouldEqual StatusCodes.OK
      whenReady(textFuture) { text =>
        text.isEmpty shouldBe false
      }
    }

    "should give pets the same access as their owners" in {
      val anyUser: Credentials = UserPool.chooseAnyUser
      val userAuthToken: AuthToken = anyUser.makeAuthToken()

      val userStatus = Sam.user.status()(userAuthToken).get
      val petAuthToken = ServiceAccountAuthTokenFromJson(Sam.user.arbitraryPetServiceAccountKey()(userAuthToken))
      val petCredential = petAuthToken.buildCredential()

      petCredential.getServiceAccountId should not be null
      petCredential.getServiceAccountId should not be userStatus.userInfo.userEmail

      Sam.user.status()(petAuthToken) shouldBe Some(userStatus)
    }

    "should not treat non-pet service accounts as pets" in {
      val saEmail = WorkbenchEmail(gcsConfig.qaEmail)

      implicit val saAuthToken = ServiceAccountAuthTokenFromPem(gcsConfig.qaEmail, gcsConfig.pathToQAPem)

      // I am no one's pet.  I am myself.
      Sam.user.status()(saAuthToken).map(_.userInfo.userEmail) shouldBe Some(saEmail.value)
    }

    "should retrieve a user's proxy group as any user" in {
      val Seq(user1: Credentials, user2: Credentials) = UserPool.chooseStudents(2)
      val authToken1: AuthToken = user1.makeAuthToken()
      val authToken2: AuthToken = user2.makeAuthToken()

      val email1 = WorkbenchEmail(Sam.user.status()(authToken1).get.userInfo.userEmail)
      val email2 = WorkbenchEmail(Sam.user.status()(authToken2).get.userInfo.userEmail)
      val userId1 = Sam.user.status()(authToken1).get.userInfo.userSubjectId
      val userId2 = Sam.user.status()(authToken2).get.userInfo.userSubjectId

      val proxyGroup1_1 = Sam.user.proxyGroup(email1.value)(authToken1)
      val proxyGroup1_2 = Sam.user.proxyGroup(email1.value)(authToken2)
      val proxyGroup2_1 = Sam.user.proxyGroup(email2.value)(authToken1)
      val proxyGroup2_2 = Sam.user.proxyGroup(email2.value)(authToken2)

      val expectedProxyEmail1 = s"$userId1@${gcsConfig.appsDomain}"

      proxyGroup1_1.value should endWith(expectedProxyEmail1)
      proxyGroup1_2.value should endWith(expectedProxyEmail1)

      val expectedProxyEmail2 = s"$userId2@${gcsConfig.appsDomain}"

      proxyGroup2_1.value should endWith(expectedProxyEmail2)
      proxyGroup2_2.value should endWith(expectedProxyEmail2)
    }

    "should retrieve a user's proxy group from a pet service account email as any user" in {
      val Seq(user1: Credentials, user2: Credentials) = UserPool.chooseStudents(2)
      val authToken1: AuthToken = user1.makeAuthToken()
      val authToken2: AuthToken = user2.makeAuthToken()

      val email = WorkbenchEmail(Sam.user.status()(authToken1).get.userInfo.userEmail)
      val username = email.value.split("@").head
      val userId = Sam.user.status()(authToken1).get.userInfo.userSubjectId

      val petSAEmail = ServiceAccountAuthTokenFromJson(Sam.user.arbitraryPetServiceAccountKey()(authToken1)).buildCredential().getServiceAccountId

      val proxyGroup_1 = Sam.user.proxyGroup(petSAEmail)(authToken1)
      val proxyGroup_2 = Sam.user.proxyGroup(petSAEmail)(authToken2)

      val expectedProxyEmail = s"$userId@${gcsConfig.appsDomain}"

      proxyGroup_1.value should endWith(expectedProxyEmail)
      proxyGroup_2.value should endWith(expectedProxyEmail)
    }

    "should generate a valid pet service account access token" in {
      val user = UserPool.chooseStudent

      val scopes = Set("https://www.googleapis.com/auth/userinfo.email", "https://www.googleapis.com/auth/userinfo.profile")

      // get my pet's token
      val petToken = Sam.user.arbitraryPetServiceAccountToken(scopes)(user.makeAuthToken())

      // convert string token to an AuthToken
      val petAuthToken = new AuthToken {
        override def buildCredential() = ???

        override lazy val value = petToken
      }

      // get my pet's email using my pet's token
      val petEmail1 = getFieldFromJson(Sam.user.arbitraryPetServiceAccountKey()(user.makeAuthToken()), "client_email")
      val petEmail2 = getFieldFromJson(Sam.user.arbitraryPetServiceAccountKey()(petAuthToken), "client_email")

      // result should be the same
      petEmail2 shouldBe petEmail1
    }

    "should only synchronize the intersection group for policies constrained by auth domains" taggedAs Tags.ExcludeInAlpha in {
      val waitTime = 10.minutes
      val authDomainId = UUID.randomUUID.toString
      val Seq(inPolicyUser: Credentials, inAuthDomainUser: Credentials, inBothUser: Credentials) = UserPool.chooseStudents(3)
      val inBothUserAuthToken = inBothUser.makeAuthToken()
      val Seq(inAuthDomainUserProxy: WorkbenchEmail, inBothUserProxy: WorkbenchEmail) = Seq(inAuthDomainUser, inBothUser).map { user =>
        Sam.user.proxyGroup(user.email)(inBothUserAuthToken)
      }

      // Create group that will act as auth domain
      Sam.user.createGroup(authDomainId)(inBothUserAuthToken)
      register cleanUp Sam.user.deleteGroup(authDomainId)(inBothUserAuthToken)

      val authDomainPolicies = Sam.user.listResourcePolicies("managed-group", authDomainId)(inBothUserAuthToken)
      val authDomainAdminEmail = for {
        policy <- authDomainPolicies if policy.policy.memberEmails.nonEmpty
      } yield policy.email
      assert(authDomainAdminEmail.size == 1)

      awaitAssert(
        Await
          .result(googleDirectoryDAO.listGroupMembers(authDomainAdminEmail.head), 5.minutes)
          .getOrElse(Set.empty) should contain theSameElementsAs Set(inBothUserProxy.value),
        waitTime,
        5.seconds
      )

      Sam.user.setPolicyMembers(authDomainId, "admin", Set(inAuthDomainUser.email, inBothUser.email))(inBothUserAuthToken)
      awaitAssert(
        Await
          .result(googleDirectoryDAO.listGroupMembers(authDomainAdminEmail.head), 5.minutes)
          .getOrElse(Set.empty) should contain theSameElementsAs Set(inBothUserProxy.value, inAuthDomainUserProxy.value),
        waitTime,
        5.seconds
      )

      val resourceTypeName = "workspace"
      val resourceId = UUID.randomUUID.toString
      val ownerPolicyName = "owner"
      val policies = Map(ownerPolicyName -> AccessPolicyMembership(Set(inBothUser.email), Set.empty, Set(ownerPolicyName)))
      val resourceRequest = CreateResourceRequest(resourceId, policies, Set(authDomainId))

      // Create constrained resource
      Sam.user.createResource(resourceTypeName, resourceRequest)(inBothUserAuthToken)
      register cleanUp Sam.user.deleteResource(resourceTypeName, resourceId)(inBothUserAuthToken)

      Sam.user.addUserToResourcePolicy(resourceTypeName, resourceId, ownerPolicyName, inPolicyUser.email)(inBothUserAuthToken)
      val resourcePolicies = Sam.user.listResourcePolicies(resourceTypeName, resourceId)(inBothUserAuthToken)
      val resourceOwnerEmail = resourcePolicies.collect {
        case SamModel.AccessPolicyResponseEntry(_, policy, email) if policy.memberEmails.nonEmpty => email
      }
      assert(resourceOwnerEmail.size == 1)
      Sam.user.syncResourcePolicy(resourceTypeName, resourceId, ownerPolicyName)(inBothUserAuthToken)

      // Google should only know about the user that is in both the auth domain group and the constrained policy
      awaitAssert(
        Await
          .result(googleDirectoryDAO.listGroupMembers(resourceOwnerEmail.head), 5.minutes)
          .getOrElse(Set.empty) should contain theSameElementsAs Set(inBothUserProxy.value),
        waitTime,
        5.seconds
      )
    }

    "should only synchronize all policy members for constrainable policies without auth domains" taggedAs Tags.ExcludeInAlpha in {
      val Seq(policyUser: Credentials, policyUser1: Credentials, policyUser2: Credentials) = UserPool.chooseStudents(3)
      val policyUser2Token = policyUser2.makeAuthToken()
      val Seq(policyUser1Proxy: WorkbenchEmail, policyUser2Proxy: WorkbenchEmail) = Seq(policyUser1, policyUser2).map { user =>
        Sam.user.proxyGroup(user.email)(policyUser2Token)
      }

      val resourceTypeName = "workspace"
      val resourceId = UUID.randomUUID.toString
      val ownerPolicyName = "owner"
      val policies = Map(ownerPolicyName -> AccessPolicyMembership(Set(policyUser2.email), Set.empty, Set(ownerPolicyName)))
      val resourceRequest = CreateResourceRequest(resourceId, policies, Set.empty) // create constrainable resource but not actually constrained

      // Create constrainable resource
      Sam.user.createResource(resourceTypeName, resourceRequest)(policyUser2Token)
      register cleanUp Sam.user.deleteResource(resourceTypeName, resourceId)(policyUser2Token)

      Sam.user.addUserToResourcePolicy(resourceTypeName, resourceId, ownerPolicyName, policyUser1.email)(policyUser2Token)
      val resourcePolicies = Sam.user.listResourcePolicies(resourceTypeName, resourceId)(policyUser2Token)
      val resourceOwnerEmail = resourcePolicies.collect {
        case SamModel.AccessPolicyResponseEntry(_, policy, email) if policy.memberEmails.nonEmpty => email
      }
      assert(resourceOwnerEmail.size == 1)
      Sam.user.syncResourcePolicy(resourceTypeName, resourceId, ownerPolicyName)(policyUser2Token)

      // Google should only know about the user that is in both the auth domain group and the constrained policy
      awaitAssert(
        Await
          .result(googleDirectoryDAO.listGroupMembers(resourceOwnerEmail.head), 5.minutes)
          .getOrElse(Set.empty) should contain theSameElementsAs Set(policyUser1Proxy.value, policyUser2Proxy.value),
        5.minutes,
        5.seconds
      )
    }

    "should synchronize the all users group for public policies" taggedAs Tags.ExcludeInAlpha in {
      val resourceId = UUID.randomUUID.toString
      val user1 = UserPool.chooseStudent
      val user1AuthToken = user1.makeAuthToken()
      val user1Proxy = Sam.user.proxyGroup(user1.email)(user1AuthToken)
      val allUsersGroupEmail = Sam.user.getGroupEmail("All_Users")(user1AuthToken)

      val resourceTypeName = "managed-group"
      val adminPolicyName = "admin"
      val adminNotifierPolicyName = "admin-notifier"

      Sam.user.createGroup(resourceId)(user1AuthToken)
      register cleanUp Sam.user.deleteGroup(resourceId)(user1AuthToken)

      val policies = Sam.user.listResourcePolicies(resourceTypeName, resourceId)(user1AuthToken)
      val adminPolicy = policies.filter(_.policyName equals adminPolicyName).last
      val adminNotifierPolicy = policies.filter(_.policyName equals adminNotifierPolicyName).last

      awaitAssert(
        Await
          .result(googleDirectoryDAO.listGroupMembers(adminPolicy.email), 5.minutes)
          .getOrElse(Set.empty) should contain theSameElementsAs Set(user1Proxy.value),
        5.minutes,
        5.seconds
      )

      Sam.user.syncResourcePolicy(resourceTypeName, resourceId, adminNotifierPolicyName)(user1AuthToken)
      Sam.user.makeResourcePolicyPublic(resourceTypeName, resourceId, adminNotifierPolicyName, true)(user1AuthToken)

      awaitAssert(
        Await
          .result(googleDirectoryDAO.listGroupMembers(adminNotifierPolicy.email), 5.minutes)
          .getOrElse(Set.empty) should contain theSameElementsAs Set(allUsersGroupEmail.value),
        5.minutes,
        5.seconds
      )
    }

    "should not allow pet creation in a project that belongs to an external org" in {
      val userAuthToken = UserPool.chooseAnyUser.makeAuthToken()
      val restException = intercept[RestException] {
        Sam.user.petServiceAccountEmail(gcsConfig.serviceProject)(userAuthToken)
      }
      import spray.json._
      restException.message.parseJson.asJsObject.fields("statusCode") shouldBe JsNumber(400)
    }
  }

  private def getFieldFromJson(jsonKey: String, field: String): String = {
    import spray.json._
    jsonKey.parseJson.asJsObject.getFields(field).head.asInstanceOf[JsString].value
  }

}
