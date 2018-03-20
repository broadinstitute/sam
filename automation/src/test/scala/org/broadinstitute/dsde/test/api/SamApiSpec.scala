package org.broadinstitute.dsde.test.api

import org.broadinstitute.dsde.workbench.service.{Orchestration, Sam, Thurloe}
import org.broadinstitute.dsde.workbench.service.Sam.user.UserStatusDetails
import org.broadinstitute.dsde.workbench.auth.{AuthToken, ServiceAccountAuthTokenFromJson, ServiceAccountAuthTokenFromPem}
import org.broadinstitute.dsde.workbench.config.{Config, Credentials, UserPool}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.dao.Google.googleIamDAO
import org.broadinstitute.dsde.workbench.fixture.BillingFixtures
import org.broadinstitute.dsde.workbench.service.test.CleanUp
import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccount, ServiceAccountName}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Seconds, Span}
import org.scalatest.{FreeSpec, Matchers}

import scala.concurrent.Await
import scala.concurrent.duration.Duration

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

      // set auth tokens explicitly to control which credentials are used

      val userStatus = Sam.user.status()(userAuthToken).get

      withCleanBillingProject(owner) { projectName =>
        // ensure known state for pet (not present)
        // since projects get reused in tests it is possible that the pet SA is in google but not in ldap
        // and if it is not in ldap sam won't try to remove it from google
        // in order to remove it we need to create it in sam first (and thus ldap) then remove it
        Sam.user.petServiceAccountEmail(projectName)(userAuthToken)
        Sam.removePet(projectName, userStatus.userInfo)
        findPetInGoogle(projectName, userStatus.userInfo) shouldBe None

        val petAccountEmail = Sam.user.petServiceAccountEmail(projectName)(userAuthToken)
        petAccountEmail.value should not be userStatus.userInfo.userEmail
        findPetInGoogle(projectName, userStatus.userInfo).map(_.email) shouldBe Some(petAccountEmail)

        // first call should create pet.  confirm that a second call to create/retrieve gives the same results
        Sam.user.petServiceAccountEmail(projectName)(userAuthToken) shouldBe petAccountEmail

        val petAuthToken = ServiceAccountAuthTokenFromJson(Sam.user.petServiceAccountKey(projectName)(userAuthToken))

        Sam.user.status()(petAuthToken) shouldBe Some(userStatus)

        // who is my pet -> who is my user's pet -> it's me
        Sam.user.petServiceAccountEmail(projectName)(petAuthToken) shouldBe petAccountEmail

        // clean up

        Sam.removePet(projectName, userStatus.userInfo)
        findPetInGoogle(projectName, userStatus.userInfo) shouldBe None
      }
    }

    "should not treat non-pet service accounts as pets" in {
      val saEmail = WorkbenchEmail(Config.GCS.qaEmail)

      implicit val saAuthToken = ServiceAccountAuthTokenFromPem(Config.GCS.qaEmail, Config.GCS.pathToQAPem)

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
      val expectedProxyEmail1 = s"${username1}_$userId1@${Config.GCS.appsDomain}"
*/
      val expectedProxyEmail1 = s"$userId1@${Config.GCS.appsDomain}"
/**/
      proxyGroup1_1.value should endWith (expectedProxyEmail1)
      proxyGroup1_2.value should endWith (expectedProxyEmail1)

/* Re-enable this code and remove the temporary code below after fixing rawls for GAWB-2933
      val expectedProxyEmail2 = s"${username2}_$userId2@${Config.GCS.appsDomain}"
*/
      val expectedProxyEmail2 = s"$userId2@${Config.GCS.appsDomain}"
/**/
      proxyGroup2_1.value should endWith (expectedProxyEmail2)
      proxyGroup2_2.value should endWith (expectedProxyEmail2)
    }

    "should retrieve a user's proxy group from a pet service account email as any user" in {
      val Seq(user1: Credentials, user2: Credentials) = UserPool.chooseStudents(2)
      val authToken1: AuthToken = user1.makeAuthToken()
      val authToken2: AuthToken = user2.makeAuthToken()

      val email = WorkbenchEmail(Sam.user.status()(authToken1).get.userInfo.userEmail)
      val username = email.value.split("@").head
      val userId = Sam.user.status()(authToken1).get.userInfo.userSubjectId

      val petSAEmail = Sam.user.petServiceAccountEmail(Config.Projects.default)(authToken1)

      val proxyGroup_1 = Sam.user.proxyGroup(petSAEmail.value)(authToken1)
      val proxyGroup_2 = Sam.user.proxyGroup(petSAEmail.value)(authToken2)

/* Re-enable this code and remove the temporary code below after fixing rawls for GAWB-2933
      val expectedProxyEmail = s"${username}_$userId@${Config.GCS.appsDomain}"
*/
      val expectedProxyEmail = s"$userId@${Config.GCS.appsDomain}"
/**/
      proxyGroup_1.value should endWith (expectedProxyEmail)
      proxyGroup_2.value should endWith (expectedProxyEmail)
    }


    "should furnish a new service account key and cache it for further retrievals" in {
      val user = UserPool.chooseStudent

      val key1 = Sam.user.petServiceAccountKey(Config.Projects.default)(user.makeAuthToken)
      val key2 = Sam.user.petServiceAccountKey(Config.Projects.default)(user.makeAuthToken)

      key1 shouldBe key2

      register cleanUp Sam.user.deletePetServiceAccountKey(Config.Projects.default, getFieldFromJson(key1, "private_key_id"))(user.makeAuthToken)
    }

    "should furnish a new service account key after deleting a cached key" in {
      val user = UserPool.chooseStudent

      val key1 = Sam.user.petServiceAccountKey(Config.Projects.default)(user.makeAuthToken)
      Sam.user.deletePetServiceAccountKey(Config.Projects.default, getFieldFromJson(key1, "private_key_id"))(user.makeAuthToken)

      val key2 = Sam.user.petServiceAccountKey(Config.Projects.default)(user.makeAuthToken)
      register cleanUp Sam.user.deletePetServiceAccountKey(Config.Projects.default, getFieldFromJson(key2, "private_key_id"))(user.makeAuthToken)

      key1 shouldNot be(key2)
    }

    "should re-create a pet SA in google even if it still exists in sam" in {
      val user = UserPool.chooseStudent
      val projectName = Config.Projects.default

      //this must use a GPAlloc'd project to avoid deleting the pet for a shared project, which
      //may have unexpected side effects
      val petSaKeyOriginal = Sam.user.petServiceAccountKey(projectName)(user.makeAuthToken)
      val petSaEmailOriginal = getFieldFromJson(petSaKeyOriginal, "client_email")
      val petSaKeyIdOriginal = getFieldFromJson(petSaKeyOriginal, "private_key_id")
      val petSaName = petSaEmailOriginal.split('@').head

      //act as a rogue process and delete the pet SA without telling sam
      Await.result(googleIamDAO.removeServiceAccount(GoogleProject(projectName), ServiceAccountName(petSaName)), Duration.Inf)

      val petSaKeyNew = Sam.user.petServiceAccountKey(projectName)(user.makeAuthToken)
      val petSaEmailNew = getFieldFromJson(petSaKeyNew, "client_email")
      val petSaKeyIdNew = getFieldFromJson(petSaKeyNew, "private_key_id")

      petSaEmailOriginal should equal(petSaEmailNew) //sanity check to make sure the SA is the same
      petSaKeyIdOriginal should not equal petSaKeyIdNew //make sure we were able to generate a new key and that a new one was returned
    }
  }

  private def getFieldFromJson(jsonKey: String, field: String): String = {
    import spray.json._
    jsonKey.parseJson.asJsObject.getFields(field).head.asInstanceOf[JsString].value
  }

}