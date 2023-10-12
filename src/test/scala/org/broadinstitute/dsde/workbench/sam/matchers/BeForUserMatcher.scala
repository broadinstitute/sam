package org.broadinstitute.dsde.workbench.sam.matchers

import org.broadinstitute.dsde.workbench.sam.model.api.SamUser
import org.broadinstitute.dsde.workbench.sam.model.UserStatus
import org.scalatest.matchers.{MatchResult, Matcher}

import scala.collection.mutable.ListBuffer

/** Asserts that the passed UserStatus.userInfo matches the passed in SamUser Id and Email
  *
  * @param expectedUser:
  *   user to expect
  */
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

object BeForUserMatcher {
  def beForUser(expectedUser: SamUser) = new BeForUserMatcher(expectedUser)
}
