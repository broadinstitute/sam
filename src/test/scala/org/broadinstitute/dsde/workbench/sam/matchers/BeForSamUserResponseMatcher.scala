package org.broadinstitute.dsde.workbench.sam.matchers

import org.broadinstitute.dsde.workbench.sam.model.api.{SamUser, SamUserResponse}
import org.scalatest.matchers.{MatchResult, Matcher}

import scala.collection.mutable.ListBuffer

/** Asserts that the passed UserStatus.userInfo matches the passed in SamUser Id and Email
  *
  * @param expectedUser:
  *   user to expect
  */
class BeForSamUserResponseMatcher(expectedUser: SamUser) extends Matcher[SamUserResponse] {
  def apply(samUserResponse: SamUserResponse): MatchResult = {
    val doEmailsMatch = samUserResponse.email == expectedUser.email
    val doIdsMatch = samUserResponse.id == expectedUser.id

    val failureMessageList: ListBuffer[String] = ListBuffer.empty
    val failureMessageNegatedList: ListBuffer[String] = ListBuffer.empty

    if (!doEmailsMatch) {
      failureMessageList += s"""SamUserResponse email ${samUserResponse.email} did not equal ${expectedUser.email}"""
      failureMessageNegatedList += s"""SamUserResponse email ${samUserResponse.email} equals ${expectedUser.email}"""
    }

    if (!doIdsMatch) {
      failureMessageList += s"""SamUserResponse id ${samUserResponse.id} did not equal ${expectedUser.id}"""
      failureMessageNegatedList += s"""SamUserResponse id ${samUserResponse.id} equals ${expectedUser.id}"""
    }

    MatchResult(
      doEmailsMatch && doIdsMatch,
      failureMessageList.mkString(" and "),
      failureMessageNegatedList.mkString(" and ")
    )
  }
}

object BeForSamUserResponseMatcher {
  def beForUser(expectedUser: SamUser) = new BeForSamUserResponseMatcher(expectedUser)
}
