package org.broadinstitute.dsde.workbench.sam.service

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import org.broadinstitute.dsde.workbench.google.errorReportSource
import org.broadinstitute.dsde.workbench.model.{AzureB2CId, ErrorReport, GoogleSubjectId, WorkbenchEmail, WorkbenchExceptionWithErrorReport, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.TestSupport.enabledMapNoTosAccepted
import org.broadinstitute.dsde.workbench.sam.model.{SamUser, UserStatus, UserStatusDetails}
import org.broadinstitute.dsde.workbench.sam.model.api.AdminUpdateUserRequest
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.broadinstitute.dsde.workbench.util.health.{SubsystemStatus, Subsystems}
import org.mockito.ArgumentMatchersSugar.{any, eqTo}
import org.mockito.{IdiomaticMockito, Strictness}

import scala.collection.mutable
import scala.concurrent.Future

case class MockUserServiceBuilder() extends IdiomaticMockito {

  val mockUserService: UserService = mock[UserService](withSettings.strictness(Strictness.Lenient))
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
  mockUserService.getUsersByQuery(any[Option[WorkbenchUserId]], any[Option[GoogleSubjectId]], any[Option[AzureB2CId]], any[SamRequestContext]) returns {
    IO(Set.empty)
  }

  private def makeUser(samUser: SamUser): Unit = {
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
    mockUserService.getUsersByQuery(eqTo(Option(samUser.id)), any[Option[GoogleSubjectId]], any[Option[AzureB2CId]], any[SamRequestContext]) returns {
      IO(Set(samUser))
    }
    mockUserService.getUsersByQuery(any[Option[WorkbenchUserId]], eqTo(samUser.googleSubjectId), any[Option[AzureB2CId]], any[SamRequestContext]) returns {
      IO(Set(samUser))
    }
    mockUserService.getUsersByQuery(any[Option[WorkbenchUserId]], any[Option[GoogleSubjectId]], eqTo(samUser.azureB2CId), any[SamRequestContext]) returns {
      IO(Set(samUser))
    }
  }

  def withEnabledUser(samUser: SamUser): MockUserServiceBuilder = withEnabledUsers(Set(samUser))
  def withEnabledUsers(samUsers: Iterable[SamUser]): MockUserServiceBuilder = {
    samUsers.foreach(makeUserAppearEnabled)
    this
  }

  def withDisabledUser(samUser: SamUser): MockUserServiceBuilder = withDisabledUsers(Set(samUser))
  def withDisabledUsers(samUsers: Iterable[SamUser]): MockUserServiceBuilder = {
    samUsers.foreach(makeUserAppearDisabled)
    this
  }

  // TODO: Need to figure out how to have a matcher accept an update user request with a bad email
  def withBadEmail(): MockUserServiceBuilder = {
    mockUserService.updateUserCrud(any[WorkbenchUserId], any[AdminUpdateUserRequest], any[SamRequestContext]) returns {
      IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, "invalid user update", Seq())))
    }
    this
  }

  private val subsystemStatuses: mutable.Map[Subsystems.Subsystem, Future[SubsystemStatus]] = mutable.Map.empty

  def withHealthySubsystem(subsystem: Subsystems.Subsystem): MockUserServiceBuilder = {
    subsystemStatuses.addOne(subsystem -> Future.successful(SubsystemStatus(true, None)))
    this
  }

  def withUnhealthySubsystem(subsystem: Subsystems.Subsystem, messages: List[String]): MockUserServiceBuilder = {
    subsystemStatuses.addOne(subsystem -> Future.successful(SubsystemStatus(false, Some(messages))))
    this
  }

  private def makeUserAppearEnabled(samUser: SamUser): Unit = {
    makeUser(samUser)
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

  private def makeUserAppearDisabled(samUser: SamUser): Unit = {
    makeUser(samUser)
    mockUserService.enableUser(eqTo(samUser.id), any[SamRequestContext]) returns {
      IO(Option(UserStatus(UserStatusDetails(samUser.id, samUser.email), enabledMapNoTosAccepted)))
    }
  }

  def build: UserService =
    mockUserService
}
