package org.broadinstitute.dsde.workbench.sam.service.UserServiceSpecs

import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.model.{SamUser, UserStatus}
import org.scalatest.{Inside, OptionValues}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.{MatchResult, Matcher}
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.collection.mutable.ListBuffer

// See: https://www.scalatest.org/user_guide/defining_base_classes
abstract class UserServiceTestTraits
  extends AnyFunSpec
    with Matchers
    with TestSupport
    with MockitoSugar
    with ScalaFutures
    with OptionValues
    with Inside {

  class BeForUserMatcher(expectedUser: SamUser) extends Matcher[UserStatus] {
    def apply(userStatus: UserStatus): MatchResult = {
      val doEmailsMatch = userStatus.userInfo.userEmail == expectedUser.email
      val doIdsMatch = userStatus.userInfo.userSubjectId == expectedUser.id

      val failureMessageList: ListBuffer[String] = ListBuffer.empty
      val failureMessageNegatedList: ListBuffer[String] = ListBuffer.empty

      if (!doEmailsMatch) {
        failureMessageList += s"""UserStatus email ${userStatus.userInfo.userEmail} did not equal ${expectedUser.email}"""
        failureMessageNegatedList += s"""UserStatus email ${userStatus.userInfo.userEmail} equals ${expectedUser.email}"""
      }

      if (!doIdsMatch) {
        failureMessageList += s"""UserStatus id ${userStatus.userInfo.userSubjectId} did not equal ${expectedUser.id}"""
        failureMessageNegatedList += s"""UserStatus id ${userStatus.userInfo.userSubjectId} equals ${expectedUser.id}"""
      }

      MatchResult(
        doEmailsMatch && doIdsMatch,
        failureMessageList.mkString(" and "),
        failureMessageNegatedList.mkString(" and ")
      )
    }
  }
  def beForUser(expectedUser: SamUser) = new BeForUserMatcher(expectedUser)

  class BeEnabledInMatcher(userStatus: UserStatus) extends Matcher[String] {
    def apply(componentName: String): MatchResult = {
      userStatus.enabled.get(componentName) match {
        case Some(status) =>
          MatchResult(
            status,
            s"$componentName is not true",
            s"$componentName is true "
          )
        case None =>
          val failureMsg = s"No entry found for $componentName"
          MatchResult(false, failureMsg, failureMsg)
      }
    }
  }
  def beEnabledIn(userStatus: UserStatus) = new BeEnabledInMatcher(userStatus)
}
