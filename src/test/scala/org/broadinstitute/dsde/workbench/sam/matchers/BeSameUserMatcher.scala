package org.broadinstitute.dsde.workbench.sam.matchers

import org.broadinstitute.dsde.workbench.sam.model.SamUser
import org.scalatest.matchers.{MatchResult, Matcher}

import scala.collection.mutable.ListBuffer

/** Asserts that the passed UserStatus.userInfo matches the passed in SamUser Id and Email
  *
  * @param expectedUser:
  *   user to expect
  */
class BeSameUserMatcher(expectedUser: SamUser) extends Matcher[SamUser] {
  def apply(samUser: SamUser): MatchResult = {
    val doEmailsMatch = samUser.email == expectedUser.email
    val doIdsMatch = samUser.id == expectedUser.id
    val doGoogleSubjectIdsMatch = samUser.googleSubjectId == expectedUser.googleSubjectId
    val doAzureB2CIdsMatch = samUser.azureB2CId == expectedUser.azureB2CId

    val failureMessageList: ListBuffer[String] = ListBuffer.empty
    val failureMessageNegatedList: ListBuffer[String] = ListBuffer.empty

    if (!doEmailsMatch) {
      failureMessageList += s"""SamUser email ${samUser.email} did not equal ${expectedUser.email}"""
      failureMessageNegatedList += s"""SamUSer email ${samUser.email} equals ${expectedUser.email}"""
    }

    if (!doIdsMatch) {
      failureMessageList += s"""SamUser id ${samUser.id} did not equal ${expectedUser.id}"""
      failureMessageNegatedList += s"""SamUSer id ${samUser.id} equals ${expectedUser.id}"""
    }

    if (!doAzureB2CIdsMatch) {
      failureMessageList += s"""SamUser Azure B2C ID ${samUser.azureB2CId} did not equal ${expectedUser.azureB2CId}"""
      failureMessageNegatedList += s"""SamUSer Azure B2C ID ${samUser.azureB2CId} equals ${expectedUser.azureB2CId}"""
    }

    MatchResult(
      doEmailsMatch && doIdsMatch && doGoogleSubjectIdsMatch && doAzureB2CIdsMatch,
      failureMessageList.mkString(" and "),
      failureMessageNegatedList.mkString(" and ")
    )
  }
}

object BeSameUserMatcher {
  def beSameUserAs(expectedUser: SamUser) = new BeSameUserMatcher(expectedUser)

}
