package org.broadinstitute.dsde.workbench.sam.service

import cats.effect.IO
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.TestSupport.enabledMapNoTosAccepted
import org.broadinstitute.dsde.workbench.sam.model.api._
import org.broadinstitute.dsde.workbench.sam.model.{UserStatus, UserStatusDetails}
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentMatchersSugar.{any, argThat, eqTo}
import org.mockito.Mockito.when
import org.mockito.{IdiomaticMockito, Strictness}

import scala.collection.mutable

case class MockUserServiceBuilder() extends IdiomaticMockito {

  private val enabledUsers: mutable.Set[SamUser] = mutable.Set.empty
  private val disabledUsers: mutable.Set[SamUser] = mutable.Set.empty
  private val allowedUsers: mutable.Set[SamUser] = mutable.Set.empty
  private val disallowedUsers: mutable.Set[SamUser] = mutable.Set.empty
  private val userAttributesSet: mutable.Set[(WorkbenchUserId, SamUserAttributes)] = mutable.Set.empty
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

  def withAllowedUser(samUser: SamUser): MockUserServiceBuilder = withAllowedUsers(Set(samUser))

  def withAllowedUsers(samUsers: Iterable[SamUser]): MockUserServiceBuilder = {
    allowedUsers.addAll(samUsers)
    this
  }

  def withDisallowedUser(samUser: SamUser): MockUserServiceBuilder = withDisallowedUsers(Set(samUser))

  def withDisallowedUsers(samUsers: Iterable[SamUser]): MockUserServiceBuilder = {
    disallowedUsers.addAll(samUsers)
    this
  }

  def withUserAttributes(samUser: SamUser, userAttributes: SamUserAttributes): MockUserServiceBuilder = {
    userAttributesSet.add(samUser.id -> userAttributes)
    this
  }

  // TODO: Need to figure out how to have a matcher accept an update user request with a bad email
  def withBadEmail(): MockUserServiceBuilder = {
    isBadEmail = true
    this
  }

  private def initializeDefaults(mockUserService: UserService): Unit = {
    when(mockUserService.createUser(any[SamUser], any[Option[SamUserRegistrationRequest]], any[SamRequestContext])).thenAnswer { args =>
      val user = args.getArgument[SamUser](0)
      IO(UserStatus(UserStatusDetails(user), Map.empty))
    }
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
    mockUserService.getUsersByQuery(
      any[Option[WorkbenchUserId]],
      any[Option[GoogleSubjectId]],
      any[Option[AzureB2CId]],
      any[Option[Int]],
      any[SamRequestContext]
    ) returns {
      IO(Set.empty)
    }

    mockUserService.getUserAllowances(any[SamUser], any[SamRequestContext]) returns IO(
      SamUserAllowances(enabled = false, termsOfService = false)
    )
    mockUserService.getUserAttributes(any[WorkbenchUserId], any[SamRequestContext]) returns IO(None)
  }

  private def makeUser(samUser: SamUser, mockUserService: UserService): Unit = {
    mockUserService.getUser(eqTo(samUser.id), any[SamRequestContext]) answers ((_: WorkbenchUserId) => IO(Option(samUser)))
    mockUserService.deleteUser(eqTo(samUser.id), any[SamRequestContext]) returns IO(())
    mockUserService.updateUserCrud(eqTo(samUser.id), any[AdminUpdateUserRequest], any[SamRequestContext]) answers (
      (_: WorkbenchUserId, r: AdminUpdateUserRequest, _: SamRequestContext) => {
        val newAzureB2CId = if (r.azureB2CId.contains(AzureB2CId("null"))) None else if (r.azureB2CId.isDefined) r.azureB2CId else samUser.azureB2CId
        val newGoogleSubjectId =
          if (r.googleSubjectId.contains(GoogleSubjectId("null"))) None else if (r.googleSubjectId.isDefined) r.googleSubjectId else samUser.googleSubjectId
        val newUser = samUser.copy(
          azureB2CId = newAzureB2CId,
          googleSubjectId = newGoogleSubjectId
        )
        IO(Option(newUser))
      }
    )
    mockUserService.enableUser(any[WorkbenchUserId], any[SamRequestContext]) returns {
      IO(None)
    }
    mockUserService.disableUser(any[WorkbenchUserId], any[SamRequestContext]) returns {
      IO(None)
    }
    mockUserService.getUsersByQuery(
      eqTo(Option(samUser.id)),
      any[Option[GoogleSubjectId]],
      any[Option[AzureB2CId]],
      any[Option[Int]],
      any[SamRequestContext]
    ) returns {
      IO(Set(samUser))
    }
    mockUserService.getUsersByQuery(
      any[Option[WorkbenchUserId]],
      eqTo(samUser.googleSubjectId),
      any[Option[AzureB2CId]],
      any[Option[Int]],
      any[SamRequestContext]
    ) returns {
      IO(Set(samUser))
    }
    mockUserService.getUsersByQuery(
      any[Option[WorkbenchUserId]],
      any[Option[GoogleSubjectId]],
      eqTo(samUser.azureB2CId),
      any[Option[Int]],
      any[SamRequestContext]
    ) returns {
      IO(Set(samUser))
    }
  }

  private def makeUsers(samUsers: Iterable[SamUser], mockUserService: UserService): Unit = {
    mockUserService.getUserFromGoogleSubjectId(any[GoogleSubjectId], any[SamRequestContext]) answers ((googleSubjectId: GoogleSubjectId) =>
      IO(samUsers.find(_.googleSubjectId.contains(googleSubjectId)))
    )
    samUsers.foreach(u => makeUser(u, mockUserService))
    mockUserService.getUsersByQuery(
      any[Option[WorkbenchUserId]],
      any[Option[GoogleSubjectId]],
      any[Option[AzureB2CId]],
      any[Option[Int]],
      any[SamRequestContext]
    ) answers ((maybeUserId: Option[WorkbenchUserId], maybeGoogleSubjectId: Option[GoogleSubjectId], maybeAzureB2CId: Option[AzureB2CId], limit: Option[Int]) =>
      IO(
        samUsers
          .filter(user =>
            (maybeUserId.isEmpty && maybeGoogleSubjectId.isEmpty && maybeAzureB2CId.isEmpty) ||
              maybeUserId.contains(user.id) ||
              ((maybeGoogleSubjectId, user.googleSubjectId) match {
                case (Some(googleSubjectId), Some(userGoogleSubjectId)) => googleSubjectId == userGoogleSubjectId
                case _ => false
              }) ||
              ((maybeAzureB2CId, user.azureB2CId) match {
                case (Some(azureB2CId), Some(userAzureB2CId)) => azureB2CId == userAzureB2CId
                case _ => false
              })
          )
          .take(limit.getOrElse(samUsers.size))
          .toSet
      )
    )

    mockUserService.getUsersByIds(any[Seq[WorkbenchUserId]], any[SamRequestContext]) answers ((userIds: Seq[WorkbenchUserId]) =>
      IO(samUsers.filter(user => userIds.contains(user.id)).toSeq)
    )
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

  private def makeUserAppearAllowed(samUser: SamUser, mockUserService: UserService): Unit =
    mockUserService.getUserAllowances(eqTo(samUser), any[SamRequestContext]) returns IO(
      SamUserAllowances(enabled = true, termsOfService = true)
    )

  private def makeUserAppearDisallowed(samUser: SamUser, mockUserService: UserService): Unit =
    mockUserService.getUserAllowances(any[SamUser], any[SamRequestContext]) returns IO(
      SamUserAllowances(enabled = false, termsOfService = false)
    )

  private def makeUserAttributesAppear(userId: WorkbenchUserId, userAttributes: SamUserAttributes, mockUserService: UserService): Unit = {
    mockUserService.getUserAttributes(eqTo(userId), any[SamRequestContext]) returns IO(Some(userAttributes))
    mockUserService.setUserAttributes(argThat((attr: SamUserAttributes) => attr.userId.equals(userId)), any[SamRequestContext]) returns IO(userAttributes)
    mockUserService.setUserAttributesFromRequest(
      eqTo(userId),
      SamUserAttributesRequest(
        userId,
        Some(userAttributes.marketingConsent),
        userAttributes.firstName,
        userAttributes.lastName,
        userAttributes.organization,
        userAttributes.contactEmail,
        userAttributes.title,
        userAttributes.department,
        userAttributes.interestInTerra,
        userAttributes.programLocationCity,
        userAttributes.programLocationState,
        userAttributes.programLocationCountry,
        userAttributes.researchArea,
        userAttributes.additionalAttributes),
      any[SamRequestContext]
    ) returns IO(userAttributes)
  }

  // The order that dictates the priority of the mocks should be handled in this build method
  // so that individual tests do not need to be concerned about what order the builder methods are called
  // the more specific the matcher, the later it should be defined as the priority of mock invokes are last in first out
  def build: UserService = {
    val mockUserService: UserService = mock[UserService](withSettings.strictness(Strictness.Lenient))
    initializeDefaults(mockUserService)
    makeUsers(existingUsers, mockUserService)
    enabledUsers.foreach(u => makeUserAppearEnabled(u, mockUserService))
    disabledUsers.foreach(u => makeUserAppearDisabled(u, mockUserService))
    allowedUsers.foreach(u => makeUserAppearAllowed(u, mockUserService))
    disallowedUsers.foreach(u => makeUserAppearDisallowed(u, mockUserService))
    userAttributesSet.foreach(tup => makeUserAttributesAppear(tup._1, tup._2, mockUserService))
    mockUserService
  }
}
