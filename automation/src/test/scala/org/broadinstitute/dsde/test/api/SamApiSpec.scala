package org.broadinstitute.dsde.test.api.sam

import org.broadinstitute.dsde.workbench.service.{Orchestration, Sam, Thurloe}
import org.broadinstitute.dsde.workbench.service.Sam.user.UserStatusDetails
import org.broadinstitute.dsde.workbench.auth.{AuthToken, ServiceAccountAuthToken}
import org.broadinstitute.dsde.workbench.config.{Config, Credentials, UserPool}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.dao.Google.googleIamDAO
import org.broadinstitute.dsde.workbench.fixture.BillingFixtures
import org.broadinstitute.dsde.workbench.service.test.CleanUp
import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccount, ServiceAccountName}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Seconds, Span}
import org.scalatest.{FreeSpec, Matchers}

class SamApiSpec extends FreeSpec with BillingFixtures with Matchers with ScalaFutures with CleanUp {
  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = scaled(Span(5, Seconds)))

  def findSaInGoogle(project: String, name: ServiceAccountName): Option[ServiceAccount] = {
    googleIamDAO.findServiceAccount(GoogleProject(project), name).futureValue
  }

  def findPetInGoogle(project: String, userInfo: UserStatusDetails): Option[ServiceAccount] = {
    findSaInGoogle(project, Sam.petName(userInfo))
  }

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
      val ownerAuthToken: AuthToken = owner.makeAuthToken()

      // set auth tokens explicitly to control which credentials are used

      val userStatus = Sam.user.status()(userAuthToken).get

      withBillingProject("auto-sam") { projectName =>
        // ensure known state for pet (not present)

        Sam.removePet(projectName, userStatus.userInfo)
        findPetInGoogle(projectName, userStatus.userInfo) shouldBe None

        val petAccountEmail = Sam.user.petServiceAccountEmail(projectName)(userAuthToken)
        petAccountEmail.value should not be userStatus.userInfo.userEmail
        findPetInGoogle(projectName, userStatus.userInfo).map(_.email) shouldBe Some(petAccountEmail)


        // first call should create pet.  confirm that a second call to create/retrieve gives the same results
        Sam.user.petServiceAccountEmail(projectName)(userAuthToken) shouldBe petAccountEmail


        val petAuthToken = ServiceAccountAuthToken(GoogleProject(projectName), petAccountEmail)
        register cleanUp petAuthToken.removePrivateKey()

        Sam.user.status()(petAuthToken) shouldBe Some(userStatus)

        // who is my pet -> who is my user's pet -> it's me
        Sam.user.petServiceAccountEmail(projectName)(petAuthToken) shouldBe petAccountEmail

        // clean up

        Sam.removePet(projectName, userStatus.userInfo)
        findPetInGoogle(projectName, userStatus.userInfo) shouldBe None
      }(ownerAuthToken)
    }

    "should not treat non-pet service accounts as pets" in {
      val saEmail = WorkbenchEmail(Config.GCS.qaEmail)
      val sa = findSaInGoogle(Config.Projects.default, google.toAccountName(saEmail)).get

      // ensure clean state: SA's user not registered
      removeUser(sa.subjectId.value)

      implicit val saAuthToken: ServiceAccountAuthToken = ServiceAccountAuthToken(GoogleProject(Config.Projects.default), saEmail)
      register cleanUp saAuthToken.removePrivateKey()

      registerAsNewUser(saEmail)

      // I am no one's pet.  I am myself.
      Sam.user.status()(saAuthToken).map(_.userInfo.userEmail) shouldBe Some(saEmail.value)

      // clean up

      removeUser(sa.subjectId.value)
    }

    "should retrieve a user's proxy group as any user" in {
      val Seq(user1: Credentials, user2: Credentials) = UserPool.chooseStudents(2)
      val authToken1: AuthToken = user1.makeAuthToken()
      val authToken2: AuthToken = user2.makeAuthToken()

      val info1 = Sam.user.status()(authToken1).get.userInfo
      val info2 = Sam.user.status()(authToken2).get.userInfo
      val email1 = WorkbenchEmail(Sam.user.status()(authToken1).get.userInfo.userEmail)
      val email2 = WorkbenchEmail(Sam.user.status()(authToken2).get.userInfo.userEmail)
      val userId1 = Sam.user.status()(authToken1).get.userInfo.userSubjectId
      val userId2 = Sam.user.status()(authToken2).get.userInfo.userSubjectId

      val proxyGroup1_1 = Sam.user.proxyGroup(email1.value)(authToken1)
      val proxyGroup1_2 = Sam.user.proxyGroup(email1.value)(authToken2)
      val proxyGroup2_1 = Sam.user.proxyGroup(email2.value)(authToken1)
      val proxyGroup2_2 = Sam.user.proxyGroup(email2.value)(authToken2)

      // will break when Sam's implementation changes
      proxyGroup1_1 shouldBe WorkbenchEmail(s"PROXY_$userId1@${Config.GCS.appsDomain}")
      proxyGroup1_2 shouldBe WorkbenchEmail(s"PROXY_$userId1@${Config.GCS.appsDomain}")
      proxyGroup2_1 shouldBe WorkbenchEmail(s"PROXY_$userId2@${Config.GCS.appsDomain}")
      proxyGroup2_2 shouldBe WorkbenchEmail(s"PROXY_$userId2@${Config.GCS.appsDomain}")
    }

    "should furnish a new service account key and cache it for further retrievals" in {
      val user = UserPool.chooseStudent

      val key1Id = Sam.user.getPetServiceAccountKey(Config.Projects.default)(user.makeAuthToken)("private_key_id").toString
      val key2Id = Sam.user.getPetServiceAccountKey(Config.Projects.default)(user.makeAuthToken)("private_key_id").toString

      key1Id shouldBe key2Id

      register cleanUp Sam.user.deletePetServiceAccountKey(Config.Projects.default, key1Id.toString)(user.makeAuthToken)
    }

    "should furnish a new service account key after deleting a cached key" in {
      val user = UserPool.chooseStudent

      val key1Id = Sam.user.getPetServiceAccountKey(Config.Projects.default)(user.makeAuthToken)("private_key_id").toString
      Sam.user.deletePetServiceAccountKey(Config.Projects.default, key1Id)(user.makeAuthToken)

      val key2Id = Sam.user.getPetServiceAccountKey(Config.Projects.default)(user.makeAuthToken)("private_key_id").toString
      register cleanUp Sam.user.deletePetServiceAccountKey(Config.Projects.default, key2Id.toString)(user.makeAuthToken)

      key1Id shouldNot be(key2Id)
    }
  }

}