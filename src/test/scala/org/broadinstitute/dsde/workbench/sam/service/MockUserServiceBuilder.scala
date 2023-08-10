package org.broadinstitute.dsde.workbench.sam.service

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import org.broadinstitute.dsde.workbench.google.errorReportSource
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchEmail, WorkbenchExceptionWithErrorReport, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.TestSupport.enabledMapNoTosAccepted
import org.broadinstitute.dsde.workbench.sam.model.api.AdminUpdateUserRequest
import org.broadinstitute.dsde.workbench.sam.model.{SamUser, UserStatus, UserStatusDetails}
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentMatchersSugar.{any, eqTo}
import org.mockito.{IdiomaticMockito, Strictness}

import scala.collection.mutable

case class MockUserServiceBuilder() extends IdiomaticMockito {

  private val enabledUsers: mutable.Set[SamUser] = mutable.Set.empty
  private val disabledUsers: mutable.Set[SamUser] = mutable.Set.empty
  private var isBadEmail = false
  private def existingUsers: mutable.Set[SamUser] =
    enabledUsers ++ disabledUsers

  def withEnabledUser(samUser: SamUser): MockUserServiceBuilder = withEnabledUsers(Set(samUser))

  def withEnabledUsers(samUsers: Iterable[SamUser]): MockUserServiceBuilder = {
    enabledUsers.addAll(samUsers)
    this
  }

  def withDisabledUser(samUser: SamUser): MockUserServiceBuilder = withDisabledUsers(Set(samUser))

  def withDisabledUsers(samUsers: Iterable[SamUser]): MockUserServiceBuilder = {
    disabledUsers.addAll(samUsers)
    this
  }

  // TODO: Need to figure out how to have a matcher accept an update user request with a bad email
  def withBadEmail(): MockUserServiceBuilder = {
    isBadEmail = true
    this
  }

  private def initializeDefaults(mockUserService: UserService): Unit = {
    mockUserService.getUserStatusFromEmail(any[WorkbenchEmail], any[SamRequestContext]) returns IO(None)
    mockUserService.getUserStatus(any[WorkbenchUserId], false, any[SamRequestContext]) returns {
      IO(None)
    }
    mockUserService.updateUserCrud(any[WorkbenchUserId], any[AdminUpdateUserRequest], any[SamRequestContext]) returns {
      IO(None)
    }
    mockUserService.getUser(any[WorkbenchUserId], any[SamRequestContext]) returns {
      IO(None)
    }
  }

  private def makeUser(samUser: SamUser, mockUserService: UserService): Unit = {
    mockUserService.getUser(eqTo(samUser.id), any[SamRequestContext]) answers ((_: WorkbenchUserId) => IO(Option(samUser)))
    mockUserService.deleteUser(eqTo(samUser.id), any[SamRequestContext]) returns IO(())
    mockUserService.updateUserCrud(eqTo(samUser.id), any[AdminUpdateUserRequest], any[SamRequestContext]) answers (
      (_: WorkbenchUserId, r: AdminUpdateUserRequest) => IO(Option(samUser.copy(email = r.email.get)))
    )
    mockUserService.enableUser(any[WorkbenchUserId], any[SamRequestContext]) returns {
      IO(None)
    }
    mockUserService.disableUser(any[WorkbenchUserId], any[SamRequestContext]) returns {
      IO(None)
    }
  }

  private def makeUserAppearEnabled(samUser: SamUser, mockUserService: UserService): Unit = {
    mockUserService.disableUser(eqTo(samUser.id), any[SamRequestContext]) returns {
      IO(Option(UserStatus(UserStatusDetails(samUser.id, samUser.email), enabledMapNoTosAccepted + ("ldap" -> false) + ("adminEnabled" -> false))))
    }
    mockUserService.getUserStatus(eqTo(samUser.id), false, any[SamRequestContext]) returns {
      IO(Option(UserStatus(UserStatusDetails(samUser.id, samUser.email), enabledMapNoTosAccepted)))
    }
    mockUserService.getUserStatusFromEmail(eqTo(samUser.email), any[SamRequestContext]) returns {
      IO(Option(UserStatus(UserStatusDetails(samUser.id, samUser.email), enabledMapNoTosAccepted)))
    }

  }

  private def makeUserAppearDisabled(samUser: SamUser, mockUserService: UserService): Unit =
    mockUserService.enableUser(eqTo(samUser.id), any[SamRequestContext]) returns {
      IO(Option(UserStatus(UserStatusDetails(samUser.id, samUser.email), enabledMapNoTosAccepted)))
    }

  private def handleMalformedEmail(mockUserService: UserService): Unit =
    if (isBadEmail) {
      mockUserService.updateUserCrud(any[WorkbenchUserId], any[AdminUpdateUserRequest], any[SamRequestContext]) returns {
        IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, "invalid user update", Seq())))
      }
    } else ()

  // The order that dictates the priority of the mocks should be handled in this build method
  // so that individual tests do not need to be concerned about what order the builder methods are called
  // the more specific the matcher, the later it should be defined as the priority of mock invokes are last in first out
  def build: UserService = {
    val mockUserService: UserService = mock[UserService](withSettings.strictness(Strictness.Lenient))
    initializeDefaults(mockUserService)
    existingUsers.foreach(u => makeUser(u, mockUserService))
    enabledUsers.foreach(u => makeUserAppearEnabled(u, mockUserService))
    disabledUsers.foreach(u => makeUserAppearDisabled(u, mockUserService))
    handleMalformedEmail(mockUserService)
    mockUserService
  }
}
