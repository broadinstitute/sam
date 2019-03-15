package org.broadinstitute.dsde.workbench.test.api


import java.util.UUID

import akka.actor.ActorSystem
import akka.testkit.TestKitBase
import org.broadinstitute.dsde.workbench.auth.{AuthToken, ServiceAccountAuthTokenFromJson, ServiceAccountAuthTokenFromPem}
import org.broadinstitute.dsde.workbench.config.{Credentials, UserPool}
import org.broadinstitute.dsde.workbench.dao.Google.{googleDirectoryDAO, googleIamDAO}
import org.broadinstitute.dsde.workbench.fixture.BillingFixtures
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccountName}
import org.broadinstitute.dsde.workbench.service.SamModel._
import org.broadinstitute.dsde.workbench.service.test.CleanUp
import org.broadinstitute.dsde.workbench.service.{Orchestration, Sam, Thurloe, _}
import org.broadinstitute.dsde.workbench.test.SamConfig
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.{Seconds, Span}
import org.scalatest.{FreeSpec, Matchers}
import org.broadinstitute.dsde.workbench.service.util.Tags

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, _}

class SamApiSpec extends FreeSpec with BillingFixtures with Matchers with ScalaFutures with CleanUp with Eventually with TestKitBase {
  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = scaled(Span(5, Seconds)))
  implicit lazy val system = ActorSystem()

  val gcsConfig = SamConfig.GCS

  def registerAsNewUser(email: WorkbenchEmail)(implicit authToken: AuthToken): Unit = {
    val newUserProfile = Orchestration.profile.BasicProfile (
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

  "Sam test utilities" - {
    "should be idempotent for user registration and removal" in {

      // use a temp user because they should not be registered.  Remove them after!

      val tempUser: Credentials = UserPool.chooseTemp
      val tempAuthToken: AuthToken = tempUser.makeAuthToken()

      //It's possible that some other bad test leaves this user regsistered.
      //Clean it up if it exists already...
      Sam.user.status()(tempAuthToken) match {
        case Some(user) => {
          logger.info(s"User ${user.userInfo.userEmail} was already registered. Removing before test starts...")
          removeUser(user.userInfo.userSubjectId)
        }
        case None => logger.info(s"User ${tempUser.email} does not yet exist! Proceeding...")
      }

      //Now assert that it's gone for real
      Sam.user.status()(tempAuthToken) shouldBe None

      registerAsNewUser(WorkbenchEmail(tempUser.email))(tempAuthToken)

      val tempUserInfo = Sam.user.status()(tempAuthToken).get.userInfo
      tempUserInfo.userEmail shouldBe tempUser.email

      // OK to re-register

      registerAsNewUser(WorkbenchEmail(tempUser.email))(tempAuthToken)
      Sam.user.status()(tempAuthToken).get.userInfo.userEmail shouldBe tempUser.email

      removeUser(tempUserInfo.userSubjectId)
      Sam.user.status()(tempAuthToken) shouldBe None

      // OK to re-remove

      removeUser(tempUserInfo.userSubjectId)
      Sam.user.status()(tempAuthToken) shouldBe None
    }
  }

  "Sam" - {
    "should give pets the same access as their owners" in {
      val anyUser: Credentials = UserPool.chooseAnyUser
      val userAuthToken: AuthToken = anyUser.makeAuthToken()

      val owner: Credentials = UserPool.chooseProjectOwner

      // set auth tokens explicitly to control which credentials are used

      val userStatus = Sam.user.status()(userAuthToken).get

      withCleanBillingProject(owner) { projectName =>
        // ensure known state for pet (not present)
        // since projects get reused in tests it is possible that the pet SA is in google but not in ldap
        // and if it is not in ldap sam won't try to remove it from google
        // in order to remove it we need to create it in sam first (and thus ldap) then remove it
        val petAccountEmail = Sam.user.petServiceAccountEmail(projectName)(userAuthToken)
        assert(petAccountEmail.value.contains(userStatus.userInfo.userSubjectId))
        Sam.removePet(projectName, userStatus.userInfo)
        eventually(googleIamDAO.findServiceAccount(GoogleProject(projectName), petAccountEmail).futureValue shouldBe None)

        Sam.user.petServiceAccountEmail(projectName)(userAuthToken)
        petAccountEmail.value should not be userStatus.userInfo.userEmail
        googleIamDAO.findServiceAccount(GoogleProject(projectName), petAccountEmail).futureValue.map(_.email) shouldBe Some(petAccountEmail)

        // first call should create pet.  confirm that a second call to create/retrieve gives the same results
        Sam.user.petServiceAccountEmail(projectName)(userAuthToken) shouldBe petAccountEmail

        val petAuthToken = ServiceAccountAuthTokenFromJson(Sam.user.petServiceAccountKey(projectName)(userAuthToken))

        Sam.user.status()(petAuthToken) shouldBe Some(userStatus)

        // who is my pet -> who is my user's pet -> it's me
        Sam.user.petServiceAccountEmail(projectName)(petAuthToken) shouldBe petAccountEmail

        // clean up

        Sam.removePet(projectName, userStatus.userInfo)
        eventually(googleIamDAO.findServiceAccount(GoogleProject(projectName), petAccountEmail).futureValue shouldBe None)
      }
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

      val info1 = Sam.user.status()(authToken1).get.userInfo
      val info2 = Sam.user.status()(authToken2).get.userInfo
      val email1 = WorkbenchEmail(Sam.user.status()(authToken1).get.userInfo.userEmail)
      val email2 = WorkbenchEmail(Sam.user.status()(authToken2).get.userInfo.userEmail)
      val username1 = email1.value.split("@").head
      val username2 = email2.value.split("@").head
      val userId1 = Sam.user.status()(authToken1).get.userInfo.userSubjectId
      val userId2 = Sam.user.status()(authToken2).get.userInfo.userSubjectId

      val proxyGroup1_1 = Sam.user.proxyGroup(email1.value)(authToken1)
      val proxyGroup1_2 = Sam.user.proxyGroup(email1.value)(authToken2)
      val proxyGroup2_1 = Sam.user.proxyGroup(email2.value)(authToken1)
      val proxyGroup2_2 = Sam.user.proxyGroup(email2.value)(authToken2)

/* Re-enable this code and remove the temporary code below after fixing rawls for GAWB-2933
      val expectedProxyEmail1 = s"${username1}_$userId1@${gcsConfig.appsDomain}"
*/
      val expectedProxyEmail1 = s"$userId1@${gcsConfig.appsDomain}"

      proxyGroup1_1.value should endWith (expectedProxyEmail1)
      proxyGroup1_2.value should endWith (expectedProxyEmail1)

/* Re-enable this code and remove the temporary code below after fixing rawls for GAWB-2933
      val expectedProxyEmail2 = s"${username2}_$userId2@${gcsConfig.appsDomain}"
*/
      val expectedProxyEmail2 = s"$userId2@${gcsConfig.appsDomain}"

      proxyGroup2_1.value should endWith (expectedProxyEmail2)
      proxyGroup2_2.value should endWith (expectedProxyEmail2)
    }

    "should retrieve a user's proxy group from a pet service account email as any user" in {
      val Seq(user1: Credentials, user2: Credentials) = UserPool.chooseStudents(2)
      val authToken1: AuthToken = user1.makeAuthToken()
      val authToken2: AuthToken = user2.makeAuthToken()

      withCleanBillingProject(UserPool.chooseProjectOwner, List(user1.email, user2.email)) { project =>
        val email = WorkbenchEmail(Sam.user.status()(authToken1).get.userInfo.userEmail)
        val username = email.value.split("@").head
        val userId = Sam.user.status()(authToken1).get.userInfo.userSubjectId

        val petSAEmail = Sam.user.petServiceAccountEmail(project)(authToken1)

        val proxyGroup_1 = Sam.user.proxyGroup(petSAEmail.value)(authToken1)
        val proxyGroup_2 = Sam.user.proxyGroup(petSAEmail.value)(authToken2)

        /* Re-enable this code and remove the temporary code below after fixing rawls for GAWB-2933
      val expectedProxyEmail = s"${username}_$userId@${gcsConfig.appsDomain}"
*/
        val expectedProxyEmail = s"$userId@${gcsConfig.appsDomain}"

        proxyGroup_1.value should endWith(expectedProxyEmail)
        proxyGroup_2.value should endWith(expectedProxyEmail)
      }
    }


    "should furnish a new service account key and cache it for further retrievals" in {
      val user = UserPool.chooseStudent

      withCleanBillingProject(UserPool.chooseProjectOwner, List(user.email)) { project =>
        withCleanUp {
          val key1 = Sam.user.petServiceAccountKey(project)(user.makeAuthToken)
          val key2 = Sam.user.petServiceAccountKey(project)(user.makeAuthToken)

          key1 shouldBe key2

          register cleanUp Sam.user.deletePetServiceAccountKey(project, getFieldFromJson(key1, "private_key_id"))(user.makeAuthToken)
        }
      }
    }

    "should furnish a new service account key after deleting a cached key" in {
      val user = UserPool.chooseStudent

      withCleanBillingProject(UserPool.chooseProjectOwner, List(user.email)) { project =>
        withCleanUp {

          val key1 = Sam.user.petServiceAccountKey(project)(user.makeAuthToken)
          Sam.user.deletePetServiceAccountKey(project, getFieldFromJson(key1, "private_key_id"))(user.makeAuthToken)

          val key2 = Sam.user.petServiceAccountKey(project)(user.makeAuthToken)
          register cleanUp Sam.user.deletePetServiceAccountKey(project, getFieldFromJson(key2, "private_key_id"))(user.makeAuthToken)

          key1 shouldNot be(key2)
        }
      }
    }

    //this is ignored because there is a permission error with GPAlloc that needs to be looked into.
    //in a GPAlloc'd project, the firecloud service account does not have permission to remove the pet SA
    // @mbemis
    "should re-create a pet SA in google even if it still exists in sam" ignore {
      val user = UserPool.chooseStudent

      //this must use a GPAlloc'd project to avoid deleting the pet for a shared project, which
      //may have unexpected side effects
      withCleanBillingProject(user) { projectName =>
        withCleanUp {
          val petSaKeyOriginal = Sam.user.petServiceAccountKey(projectName)(user.makeAuthToken)
          val petSaEmailOriginal = getFieldFromJson(petSaKeyOriginal, "client_email")
          val petSaKeyIdOriginal = getFieldFromJson(petSaKeyOriginal, "private_key_id")
          val petSaName = petSaEmailOriginal.split('@').head

          register cleanUp Sam.user.deletePetServiceAccountKey(projectName, petSaKeyIdOriginal)(user.makeAuthToken)

          //act as a rogue process and delete the pet SA without telling sam
          Await.result(googleIamDAO.removeServiceAccount(GoogleProject(projectName), ServiceAccountName(petSaName)), Duration.Inf)

          val petSaKeyNew = Sam.user.petServiceAccountKey(projectName)(user.makeAuthToken)
          val petSaEmailNew = getFieldFromJson(petSaKeyNew, "client_email")
          val petSaKeyIdNew = getFieldFromJson(petSaKeyNew, "private_key_id")

          register cleanUp Sam.user.deletePetServiceAccountKey(projectName, petSaKeyIdNew)(user.makeAuthToken)

          petSaEmailOriginal should equal(petSaEmailNew) //sanity check to make sure the SA is the same
          petSaKeyIdOriginal should not equal petSaKeyIdNew //make sure we were able to generate a new key and that a new one was returned
        }
      }
    }

    "should get an access token for a user's own pet service account" in {
      val user = UserPool.chooseStudent

      withCleanBillingProject(UserPool.chooseProjectOwner, List(user.email)) { project =>
        // get my pet's email
        val petEmail1 =  Sam.user.petServiceAccountEmail(project)(user.makeAuthToken)

        val scopes = Set("https://www.googleapis.com/auth/userinfo.email", "https://www.googleapis.com/auth/userinfo.profile")

        // get my pet's token
        val petToken = Sam.user.petServiceAccountToken(project, scopes)(user.makeAuthToken)

        // convert string token to an AuthToken
        val petAuthToken = new AuthToken {
          override def buildCredential() = ???
          override lazy val value = petToken
        }

        // get my pet's email using my pet's token
        val petEmail2 = Sam.user.petServiceAccountEmail(project)(petAuthToken)

        // result should be the same
        petEmail2 shouldBe petEmail1
      }
    }

    "should arbitrarily choose a project to return a pet key for when the user has existing pets" in {
      val user = UserPool.chooseStudent
      val userToken = user.makeAuthToken

      withCleanBillingProject(UserPool.chooseProjectOwner, List(user.email)) { project1 =>
        withCleanBillingProject(UserPool.chooseProjectOwner, List(user.email)) { project2 =>
          // get my pet's email thru the arbitrary key endpoint
          val petEmailArbitrary = getFieldFromJson(Sam.user.arbitraryPetServiceAccountKey()(userToken), "client_email")

          //get the user's subject id for comparison below
          val userSubjectId = Sam.user.status()(userToken).get.userInfo.userSubjectId

          // result should be a pet associated with the user
          assert(petEmailArbitrary.contains(userSubjectId))
        }
      }
    }

    "should arbitrarily choose a project to return a pet key for when the user has no existing pets" in {
      val user = UserPool.chooseStudent
      val userToken = user.makeAuthToken()

      val userSubjectId = Sam.user.status()(userToken).get.userInfo.userSubjectId

      // get my pet's email thru the arbitrary key endpoint
      val petEmailArbitrary = getFieldFromJson(Sam.user.arbitraryPetServiceAccountKey()(userToken), "client_email")

      assert(petEmailArbitrary.contains(userSubjectId))
    }

    "should arbitrarily choose a project to return a pet token for when the user has existing pets" in {
      val user = UserPool.chooseStudent

      withCleanBillingProject(UserPool.chooseProjectOwner, List(user.email)) { project =>
        // get my pet's email
        val petEmail1 =  Sam.user.petServiceAccountEmail(project)(user.makeAuthToken)

        val scopes = Set("https://www.googleapis.com/auth/userinfo.email", "https://www.googleapis.com/auth/userinfo.profile")

        // get my pet's token
        val petToken = Sam.user.arbitraryPetServiceAccountToken(scopes)(user.makeAuthToken)

        // convert string token to an AuthToken
        val petAuthToken = new AuthToken {
          override def buildCredential() = ???
          override lazy val value = petToken
        }

        // get my pet's email using my pet's token
        val petEmail2 = Sam.user.petServiceAccountEmail(project)(petAuthToken)

        // result should be the same
        petEmail2 shouldBe petEmail1
      }
    }

    "should arbitrarily choose a project to return a pet token for when the user has no existing pets" in {
      val user = UserPool.chooseStudent

      val scopes = Set("https://www.googleapis.com/auth/userinfo.email", "https://www.googleapis.com/auth/userinfo.profile")

      // get my pet's token
      val petToken = Sam.user.arbitraryPetServiceAccountToken(scopes)(user.makeAuthToken)

      // convert string token to an AuthToken
      val petAuthToken = new AuthToken {
        override def buildCredential() = ???
        override lazy val value = petToken
      }

      // get my pet's email using my pet's token
      val petEmail1 = getFieldFromJson(Sam.user.arbitraryPetServiceAccountKey()(user.makeAuthToken), "client_email")
      val petEmail2 = getFieldFromJson(Sam.user.arbitraryPetServiceAccountKey()(petAuthToken), "client_email")

      // result should be the same
      petEmail2 shouldBe petEmail1
    }

    "should synchronize groups with Google" taggedAs Tags.ExcludeInAlpha in {
      val managedGroupId = UUID.randomUUID.toString
      val adminPolicyName = "admin"
      val Seq(user1: Credentials, user2: Credentials, user3: Credentials) = UserPool.chooseStudents(3)
      val user1AuthToken = user1.makeAuthToken()
      val Seq(user1Proxy: WorkbenchEmail, user2Proxy: WorkbenchEmail, user3Proxy: WorkbenchEmail) = Seq(user1, user2, user3).map(user => Sam.user.proxyGroup(user.email)(user1AuthToken))

      Sam.user.createGroup(managedGroupId)(user1AuthToken)
      register cleanUp Sam.user.deleteGroup(managedGroupId)(user1AuthToken)

      val policies = Sam.user.listResourcePolicies("managed-group", managedGroupId)(user1AuthToken)
      val policyEmail = policies.collect {
        case SamModel.AccessPolicyResponseEntry(_, policy, email) if policy.memberEmails.nonEmpty => email
      }
      assert(policyEmail.size == 1) // Only the admin policy should be non-empty after creation

      // The admin policy should contain only the user that created the group
      awaitAssert(
        Await.result(googleDirectoryDAO.listGroupMembers(policyEmail.head), 5.minutes)
          .getOrElse(Set.empty) should contain theSameElementsAs Set(user1Proxy.value),
        5.minutes, 5.seconds)

      // Change the membership of the admin policy to include users 1 and 2
      Sam.user.setPolicyMembers(managedGroupId, adminPolicyName, Set(user1.email, user2.email))(user1AuthToken)
      awaitAssert(
        Await.result(googleDirectoryDAO.listGroupMembers(policyEmail.head), 5.minutes)
          .getOrElse(Set.empty) should contain theSameElementsAs Set(user1Proxy.value, user2Proxy.value),
        5.minutes, 5.seconds)

      // Add user 3 to the admin policy
      Sam.user.addUserToPolicy(managedGroupId, adminPolicyName, user3.email)(user1AuthToken)
      awaitAssert(
        Await.result(googleDirectoryDAO.listGroupMembers(policyEmail.head), 5.minutes)
          .getOrElse(Set.empty) should contain theSameElementsAs Set(user1Proxy.value, user2Proxy.value, user3Proxy.value),
        5.minutes, 5.seconds)

      // Remove user 2 from the admin policy
      Sam.user.removeUserFromPolicy(managedGroupId, adminPolicyName, user2.email)(user1AuthToken)
      awaitAssert(
        Await.result(googleDirectoryDAO.listGroupMembers(policyEmail.head), 5.minutes)
          .getOrElse(Set.empty) should contain theSameElementsAs Set(user1Proxy.value, user3Proxy.value),
        5.minutes, 5.seconds)
    }

    "should only synchronize the intersection group for policies constrained by auth domains" taggedAs Tags.ExcludeInAlpha in {
      val authDomainId = UUID.randomUUID.toString
      val Seq(inPolicyUser: Credentials, inAuthDomainUser: Credentials, inBothUser: Credentials) = UserPool.chooseStudents(3)
      val inBothUserAuthToken = inBothUser.makeAuthToken()
      val Seq(inAuthDomainUserProxy: WorkbenchEmail, inBothUserProxy: WorkbenchEmail) = Seq(inAuthDomainUser, inBothUser).map {
        user => Sam.user.proxyGroup(user.email)(inBothUserAuthToken)
      }

      // Create group that will act as auth domain
      Sam.user.createGroup(authDomainId)(inBothUserAuthToken)
      register cleanUp Sam.user.deleteGroup(authDomainId)(inBothUserAuthToken)

      val authDomainPolicies = Sam.user.listResourcePolicies("managed-group", authDomainId)(inBothUserAuthToken)
      val authDomainAdminEmail = for {
        policy <- authDomainPolicies if policy.policy.memberEmails.nonEmpty
      } yield {
        policy.email
      }
      assert(authDomainAdminEmail.size == 1)

      awaitAssert(
        Await.result(googleDirectoryDAO.listGroupMembers(authDomainAdminEmail.head), 5.minutes)
          .getOrElse(Set.empty) should contain theSameElementsAs Set(inBothUserProxy.value),
        5.minutes, 5.seconds)

      Sam.user.setPolicyMembers(authDomainId, "admin", Set(inAuthDomainUser.email, inBothUser.email))(inBothUserAuthToken)
      awaitAssert(
        Await.result(googleDirectoryDAO.listGroupMembers(authDomainAdminEmail.head), 5.minutes)
          .getOrElse(Set.empty) should contain theSameElementsAs Set(inBothUserProxy.value, inAuthDomainUserProxy.value),
        5.minutes, 5.seconds)

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
        Await.result(googleDirectoryDAO.listGroupMembers(resourceOwnerEmail.head), 5.minutes)
          .getOrElse(Set.empty) should contain theSameElementsAs Set(inBothUserProxy.value),
        5.minutes, 5.seconds)
    }

    "should only synchronize all policy members for constrainable policies without auth domains" taggedAs Tags.ExcludeInAlpha in {
      val Seq(policyUser: Credentials, policyUser1: Credentials, policyUser2: Credentials) = UserPool.chooseStudents(3)
      val policyUser2Token = policyUser2.makeAuthToken()
      val Seq(policyUser1Proxy: WorkbenchEmail, policyUser2Proxy: WorkbenchEmail) = Seq(policyUser1, policyUser2).map {
        user => Sam.user.proxyGroup(user.email)(policyUser2Token)
      }

      val resourceTypeName = "workspace"
      val resourceId = UUID.randomUUID.toString
      val ownerPolicyName = "owner"
      val policies = Map(ownerPolicyName -> AccessPolicyMembership(Set(policyUser2.email), Set.empty, Set(ownerPolicyName)))
      val resourceRequest = CreateResourceRequest(resourceId, policies, Set.empty) //create constrainable resource but not actually constrained

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
        Await.result(googleDirectoryDAO.listGroupMembers(resourceOwnerEmail.head), 5.minutes)
          .getOrElse(Set.empty) should contain theSameElementsAs Set(policyUser1Proxy.value, policyUser2Proxy.value),
        5.minutes, 5.seconds)
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
        Await.result(googleDirectoryDAO.listGroupMembers(adminPolicy.email), 5.minutes)
          .getOrElse(Set.empty) should contain theSameElementsAs Set(user1Proxy.value),
        5.minutes, 5.seconds)

      Sam.user.syncResourcePolicy(resourceTypeName, resourceId, adminNotifierPolicyName)(user1AuthToken)
      Sam.user.makeResourcePolicyPublic(resourceTypeName, resourceId, adminNotifierPolicyName, true)(user1AuthToken)

      awaitAssert(
        Await.result(googleDirectoryDAO.listGroupMembers(adminNotifierPolicy.email), 5.minutes)
          .getOrElse(Set.empty) should contain theSameElementsAs Set(allUsersGroupEmail.value),
        5.minutes, 5.seconds)
    }
  }

  private def getFieldFromJson(jsonKey: String, field: String): String = {
    import spray.json._
    jsonKey.parseJson.asJsObject.getFields(field).head.asInstanceOf[JsString].value
  }

}