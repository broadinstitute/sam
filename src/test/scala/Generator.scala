package org.broadinstitute.dsde.workbench.sam

import akka.http.scaladsl.model.headers.{OAuth2BearerToken, RawHeader}
import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccountSubjectId}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.api.CreateWorkbenchUser
import org.scalacheck._
import org.broadinstitute.dsde.workbench.sam.api.StandardUserInfoDirectives._
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup
import org.broadinstitute.dsde.workbench.sam.service.UserService
import org.broadinstitute.dsde.workbench.sam.service.UserService._

object Generator {
  val genNonPetEmail: Gen[WorkbenchEmail] = Gen.alphaStr.map(x => WorkbenchEmail(s"t$x@gmail.com"))
  val genPetEmail: Gen[WorkbenchEmail] = Gen.alphaStr.map(x => WorkbenchEmail(s"t$x@test.iam.gserviceaccount.com"))
  val genGoogleSubjectId: Gen[GoogleSubjectId] = Gen.const(GoogleSubjectId(UserService.genRandom(System.currentTimeMillis())))
  val genServiceAccountSubjectId: Gen[ServiceAccountSubjectId] = genGoogleSubjectId.map(x => ServiceAccountSubjectId(x.value))
  val genOAuth2BearerToken: Gen[OAuth2BearerToken] = Gen.alphaStr.map(x => OAuth2BearerToken("s"+x))

  val genHeadersWithoutExpiresIn: Gen[List[RawHeader]] = for{
    email <- genNonPetEmail
    accessToken <- genOAuth2BearerToken
  } yield List(
    RawHeader(emailHeader, email.value),
    RawHeader(googleSubjectIdHeader, genRandom(System.currentTimeMillis())),
    RawHeader(accessTokenHeader, accessToken.value),
  )

  val genUserInfoHeadersWithInvalidExpiresIn: Gen[List[RawHeader]] = for{
    ls <- genHeadersWithoutExpiresIn
    expiresIn <- Gen.alphaStr.map(x => s"s$x")
  } yield RawHeader(expiresInHeader, expiresIn) :: ls

  val genValidUserInfoHeaders: Gen[List[RawHeader]] = for{
     ls <- genHeadersWithoutExpiresIn
  } yield RawHeader(expiresInHeader, (System.currentTimeMillis() + 1000).toString) :: ls

  val genUserInfo = for{
    token <- genOAuth2BearerToken
    email <- genNonPetEmail
    expires <- Gen.calendar.map(_.getTimeInMillis)
  } yield UserInfo(token, genWorkbenchUserId(System.currentTimeMillis()), email, expires)

  val genCreateWorkbenchUserAPI = for{
    email <- genNonPetEmail
    userId = genWorkbenchUserId(System.currentTimeMillis())
  }yield CreateWorkbenchUser(userId, GoogleSubjectId(userId.value), email)

  val genWorkbenchGroupName = Gen.alphaStr.map(x => WorkbenchGroupName(s"s$x")) //prepending `s` just so this won't be an empty string
  val genGoogleProject = Gen.alphaStr.map(x => GoogleProject(s"s$x")) //prepending `s` just so this won't be an empty string
  val genWorkbenchSubject: Gen[WorkbenchSubject] = for{
    groupId <- genWorkbenchGroupName
    project <- genGoogleProject
    res <- Gen.oneOf[WorkbenchSubject](List(genWorkbenchUserId(System.currentTimeMillis()), groupId, PetServiceAccountId(genWorkbenchUserId(System.currentTimeMillis()), project)))
  }yield res

  val genBasicWorkbenchGroup = for{
    id <- genWorkbenchGroupName
    subject <- Gen.listOf[WorkbenchSubject](genWorkbenchSubject).map(_.toSet)
    email <- genNonPetEmail
  } yield BasicWorkbenchGroup(id, subject, email)

  implicit val arbNonPetEmail: Arbitrary[WorkbenchEmail] = Arbitrary(genNonPetEmail)
  implicit val arbOAuth2BearerToken: Arbitrary[OAuth2BearerToken] = Arbitrary(genOAuth2BearerToken)
  implicit val arbCreateWorkbenchUserAPI: Arbitrary[CreateWorkbenchUser] = Arbitrary(genCreateWorkbenchUserAPI)
}
