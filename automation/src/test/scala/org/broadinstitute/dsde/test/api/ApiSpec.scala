package org.broadinstitute.dsde.test.api.sam

import org.broadinstitute.dsde.workbench.service.Orchestration
import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.service.test.CleanUp
import org.scalatest.concurrent.ScalaFutures
import org.broadinstitute.dsde.workbench.auth.ServiceAccountAuthToken
import org.broadinstitute.dsde.workbench.service.Sam
import org.broadinstitute.dsde.workbench.service.Sam.user.UserStatusDetails
import org.broadinstitute.dsde.workbench.dao.Google.googleIamDAO
import org.broadinstitute.dsde.workbench.config.{Config, Credentials, UserPool}
import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccount, ServiceAccountName}
import org.broadinstitute.dsde.workbench.model.google
import org.broadinstitute.dsde.workbench.auth.AuthToken
//import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{FreeSpec, Matchers}
import org.scalatest.time.{Seconds, Span}
//import org.scalatest.{FreeSpec, Matchers}

class SamApiSpec extends FreeSpec with Matchers with ScalaFutures with CleanUp {
  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = scaled(Span(5, Seconds)))

  def findSaInGoogle(name: ServiceAccountName): Option[ServiceAccount] = {
    googleIamDAO.findServiceAccount(GoogleProject(Config.Projects.default), name).futureValue
  }

  def findPetInGoogle(userInfo: UserStatusDetails): Option[ServiceAccount] = {
    findSaInGoogle(Sam.petName(userInfo))
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

  // TODO: why isn't WorkbenchSubject a ValueObject?  I'd like to use it here
  def removeUser(subjectId: String): Unit = {
    implicit val token: AuthToken = UserPool.chooseAdmin.makeAuthToken()
    if (Sam.admin.doesUserExist(subjectId).getOrElse(false)) {
      Sam.admin.deleteUser(subjectId)
    }
//    Thurloe.keyValuePairs.deleteAll(subjectId)
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

      // set auth tokens explicitly to control which credentials are used

      val userStatus = Sam.user.status()(userAuthToken).get

      // ensure known state for pet (not present)

      Sam.removePet(userStatus.userInfo)
      findPetInGoogle(userStatus.userInfo) shouldBe None

      val petAccountEmail = Sam.user.petServiceAccountEmail()(userAuthToken)
      petAccountEmail.value should not be userStatus.userInfo.userEmail
      findPetInGoogle(userStatus.userInfo).map(_.email) shouldBe Some(petAccountEmail)


      // first call should create pet.  confirm that a second call to create/retrieve gives the same results
      Sam.user.petServiceAccountEmail()(userAuthToken) shouldBe petAccountEmail


      val petAuthToken = ServiceAccountAuthToken(petAccountEmail)
      register cleanUp petAuthToken.removePrivateKey()

      Sam.user.status()(petAuthToken) shouldBe Some(userStatus)

      // who is my pet -> who is my user's pet -> it's me
      Sam.user.petServiceAccountEmail()(petAuthToken) shouldBe petAccountEmail

      // clean up

      Sam.removePet(userStatus.userInfo)
      findPetInGoogle(userStatus.userInfo) shouldBe None
    }

    "should not treat non-pet service accounts as pets" in {
      val saEmail = WorkbenchEmail(Config.GCS.qaEmail)
      val sa = findSaInGoogle(google.toAccountName(saEmail)).get

      // ensure clean state: SA's user not registered
      removeUser(sa.subjectId.value)

      implicit val saAuthToken: ServiceAccountAuthToken = ServiceAccountAuthToken(saEmail)
      register cleanUp saAuthToken.removePrivateKey()

      registerAsNewUser(saEmail)

      // I am no one's pet.  I am myself.
      Sam.user.status()(saAuthToken).map(_.userInfo.userEmail) shouldBe Some(saEmail.value)

      // clean up

      removeUser(sa.subjectId.value)
    }

    "should retrieve the user's proxy group" in {
      val anyUser: Credentials = UserPool.chooseAnyUser
      implicit val userAuthToken: AuthToken = anyUser.makeAuthToken()

      val userId = Sam.user.status().get.userInfo.userSubjectId
      val proxyGroup = Sam.user.proxyGroup()

      // will break when Sam's implementation does
      proxyGroup shouldBe WorkbenchEmail(s"PROXY_$userId@${Config.GCS.appsDomain}")
    }
  }

}