package org.broadinstitute.dsde.workbench.sam.matchers

import org.broadinstitute.dsde.workbench.sam.model.api.SamUser
import org.scalatest.matchers.{MatchResult, Matcher}

import scala.collection.mutable.ListBuffer

/** Asserts that the passed UserStatus.userInfo matches the passed in SamUser Id and Email
  *
  * @param expectedUser:
  *   user to expect
  */
class BeForSamUserMatcher(expectedUser: SamUser) extends Matcher[SamUser] {
  def apply(samUser: SamUser): MatchResult = {
    val doEmailsMatch = samUser.email == expectedUser.email
    val doIdsMatch = samUser.id == expectedUser.id

    val failureMessageList: ListBuffer[String] = ListBuffer.empty
    val failureMessageNegatedList: ListBuffer[String] = ListBuffer.empty

    if (!doEmailsMatch) {
      failureMessageList += s"""SamUser email ${samUser.email} did not equal ${expectedUser.email}"""
      failureMessageNegatedList += s"""SamUser email ${samUser.email} equals ${expectedUser.email}"""
    }

    if (!doIdsMatch) {
      failureMessageList += s"""SamUser id ${samUser.id} did not equal ${expectedUser.id}"""
      failureMessageNegatedList += s"""SamUser id ${samUser.id} equals ${expectedUser.id}"""
    }

    MatchResult(
      doEmailsMatch && doIdsMatch,
      failureMessageList.mkString(" and "),
      failureMessageNegatedList.mkString(" and ")
    )
  }
}

object BeForSamUserMatcher {
  def beForSamUser(expectedUser: SamUser) = new BeForUserMatcher(expectedUser)
}
