package org.broadinstitute.dsde.workbench.sam
package service

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.codec.binary.Hex
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.ServiceAccountSubjectId
import org.broadinstitute.dsde.workbench.sam.azure.ManagedIdentityObjectId
import org.broadinstitute.dsde.workbench.sam.config.AzureServicesConfig
import org.broadinstitute.dsde.workbench.sam.dataAccess.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.model.api._
import org.broadinstitute.dsde.workbench.sam.service.UserService.genWorkbenchUserId
import org.broadinstitute.dsde.workbench.sam.util.AsyncLogging.IOWithLogging
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

import java.security.SecureRandom
import java.time.Instant
import javax.naming.NameNotFoundException
import scala.concurrent.ExecutionContext
import scala.util.matching.Regex

/** Created by dvoet on 7/14/17.
  */
class UserService(
    val directoryDAO: DirectoryDAO,
    val cloudExtensions: CloudExtensions,
    blockedEmailDomains: Seq[String],
    tosService: TosService,
    azureConfig: Option[AzureServicesConfig] = None
)(implicit
    val executionContext: ExecutionContext
) extends LazyLogging {
  // this is what's currently called
  def createUser(possibleNewUser: SamUser, samRequestContext: SamRequestContext): IO[UserStatus] =
    createUser(possibleNewUser, None, samRequestContext)

  // this is the new version (not currently being used)
  def createUser(
      possibleNewUserMaybeWithEmail: SamUser,
      registrationRequest: Option[SamUserRegistrationRequest],
      samRequestContext: SamRequestContext
  ): IO[UserStatus] = {
    val email =
      if (shouldCreateUamiEmail(possibleNewUserMaybeWithEmail)) {
        // If the email is missing but Azure B2C ID exist, this is a managed identity. If the config allows it, create an
        // email address for the user.  This is because the email address is used as the unique identifier for the user.
        WorkbenchEmail(s"${possibleNewUserMaybeWithEmail.azureB2CId.get.value}@uami.terra.bio")
      } else {
        possibleNewUserMaybeWithEmail.email
      }
    val possibleNewUser = possibleNewUserMaybeWithEmail.copy(email = email)

    // Validate the values set on the possible new user, short circuit if there's a problem
    val validationErrors = validateUser(possibleNewUser)
    if (validationErrors.nonEmpty) {
      return IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, "invalid user", validationErrors.get)))
    }
    val registrationRequestErrors = registrationRequest.flatMap(_.validateForNewUser)
    if (registrationRequestErrors.nonEmpty) {
      return IO.raiseError(
        new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, "invalid registration request", registrationRequestErrors.get))
      )
    }
    for {
      newUser <- assertUserIsNotAlreadyRegistered(possibleNewUser, samRequestContext)
      maybeWorkbenchSubject <- directoryDAO.loadSubjectFromEmail(newUser.email, samRequestContext)
      registeredUser <- attemptToRegisterSubjectAsAUser(maybeWorkbenchSubject, newUser, samRequestContext)
      _ <- maybeAcceptTermsOfService(registeredUser.id, registrationRequest, samRequestContext)
      _ <- registerNewUserAttributes(registeredUser.id, registrationRequest, samRequestContext)
      registeredAndEnabledUser <- makeUserEnabled(registeredUser, samRequestContext)
      _ <- addToAllUsersGroup(registeredAndEnabledUser.id, samRequestContext)
    } yield
    // We should only make it this far if we successfully perform all of the above steps to set all of the
    // UserStatus.enabled fields to true, with the exception of ToS.  So we should be able to safely just return true
    // for all of these things without needing to go recalculate them.
    UserStatus(
      UserStatusDetails(registeredAndEnabledUser.id, registeredAndEnabledUser.email),
      Map(
        "ldap" -> true,
        "allUsersGroup" -> true,
        "google" -> true,
        "adminEnabled" -> true,
        "tosAccepted" -> registrationRequest.exists(_.acceptsTermsOfService)
      )
    )
  }

  private def shouldCreateUamiEmail(possibleNewUserMaybeWithEmail: SamUser) =
    possibleNewUserMaybeWithEmail.email.value.isEmpty && possibleNewUserMaybeWithEmail.azureB2CId.nonEmpty && azureConfig.exists(
      _.allowManagedIdentityUserCreation
    )

  private def registerNewUserAttributes(
      userId: WorkbenchUserId,
      registrationRequest: Option[SamUserRegistrationRequest],
      samRequestContext: SamRequestContext
  ): IO[Unit] = {
    val attributes = registrationRequest
      .map(_.userAttributes)
      .getOrElse(SamUserAttributesRequest(marketingConsent = Some(false)))
    setUserAttributesFromRequest(userId, attributes, samRequestContext).map(_ => ())
  }

  private def maybeAcceptTermsOfService(
      userId: WorkbenchUserId,
      registrationRequest: Option[SamUserRegistrationRequest],
      samRequestContext: SamRequestContext
  ): IO[Unit] =
    if (registrationRequest.exists(_.acceptsTermsOfService)) {
      tosService.acceptCurrentTermsOfService(userId, samRequestContext).map(_ => ())
    } else {
      IO.unit
    }

  private def attemptToRegisterSubjectAsAUser(
      maybeWorkbenchSubject: Option[WorkbenchSubject],
      possibleNewUser: SamUser,
      samRequestContext: SamRequestContext
  ): IO[SamUser] =
    maybeWorkbenchSubject match {
      // If a WorkbenchUserId was found, then the user was previously invited
      case Some(invitedUserId: WorkbenchUserId) => registerInvitedUser(possibleNewUser, invitedUserId, samRequestContext)
      // If no subject was found, they're a new user and we can proceed to register them
      case None => registerBrandNewUser(possibleNewUser, samRequestContext)
      // If any other type of WorkbenchSubject was found, then we have to stop the user from registering with this email address
      case Some(_) =>
        IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, s"Email ${possibleNewUser.email} is already used.")))
    }

  private def validateUser(user: SamUser): Option[Seq[ErrorReport]] =
    Option(
      Seq(
        validateUserIds(user),
        validateEmail(user.email, blockedEmailDomains)
      ).flatten // flatten to get rid of Nones
    ).filter(_.nonEmpty) // If the final Seq is empty, filter it out and just return a None

  // user record has to have a GoogleSubjectId and/or an AzureB2CId
  private def validateUserIds(user: SamUser): Option[ErrorReport] =
    if (user.googleSubjectId.isEmpty && user.azureB2CId.isEmpty) {
      Option(ErrorReport("cannot create user when neither google subject id nor azure b2c id exists"))
    } else None

  private def validateEmail(email: WorkbenchEmail, blockedEmailDomains: Seq[String]): Option[ErrorReport] =
    if (!UserService.emailRegex.matches(email.value)) {
      Option(ErrorReport(StatusCodes.BadRequest, s"invalid email address [${email.value}]"))
    } else if (blockedEmailDomains.exists(domain => email.value.endsWith("@" + domain) || email.value.endsWith("." + domain))) {
      Option(ErrorReport(StatusCodes.BadRequest, s"email domain not permitted [${email.value}]"))
    } else None

  // Try to find user by GoogleSubject, AzureB2CId
  // A registered user is one that has a record in the database and has a Cloud Identifier specified
  private def tryToFindUserByCloudId(user: SamUser, samRequestContext: SamRequestContext): IO[Option[SamUser]] =
    // running these IOs sequentially.  Could be parallelized but I can't imagine the performance hit here is all that
    // bad.  If we wanted to optimize it, the better thing to do would be to write a single query that searches via
    // either cloud ID
    for {
      maybeGoogleUser <- user.googleSubjectId.map(directoryDAO.loadUserByGoogleSubjectId(_, samRequestContext)).getOrElse(IO(None))
      maybeAzureUser <- user.azureB2CId.map(directoryDAO.loadUserByAzureB2CId(_, samRequestContext)).getOrElse(IO(None))
    } yield maybeGoogleUser.orElse(maybeAzureUser)

  private def assertUserIsNotAlreadyRegistered(user: SamUser, samRequestContext: SamRequestContext): IO[SamUser] =
    tryToFindUserByCloudId(user, samRequestContext).flatMap {
      case Some(registeredUser) =>
        IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"user ${registeredUser.email} is already registered")))
      case None => IO(user)
    }

  def getUser(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Option[SamUser]] =
    directoryDAO.loadUser(userId, samRequestContext)

  def getUsersByQuery(
      userId: Option[WorkbenchUserId],
      googleSubjectId: Option[GoogleSubjectId],
      azureB2CId: Option[AzureB2CId],
      limit: Option[Int],
      samRequestContext: SamRequestContext
  ): IO[Set[SamUser]] = {
    val defaultLimit = 10
    val maximumLimit = 1000
    // This constrains the maximum results to be within the range [1,1000]
    val maxResults = limit.getOrElse(defaultLimit).min(maximumLimit).max(1)
    directoryDAO.loadUsersByQuery(userId, googleSubjectId, azureB2CId, maxResults, samRequestContext)
  }

  def updateUserCrud(userId: WorkbenchUserId, request: AdminUpdateUserRequest, samRequestContext: SamRequestContext): IO[Option[SamUser]] =
    directoryDAO.loadUser(userId, samRequestContext).flatMap {
      case Some(user) =>
        // validate all fields to be updated
        val errorReports = request.isValid(user)
        if (errorReports.nonEmpty) {
          IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, "invalid user update", errorReports)))
        } else { // apply all updates
          if (request.azureB2CId.isDefined || request.googleSubjectId.isDefined) {
            directoryDAO.updateUser(user, request, samRequestContext)
          } else IO(Option(user))
        }
      case None => IO(None)
    }

  // In most cases when this is called we will have a scenario where 1 or more Cloud Ids are set.  For any Cloud Ids
  // that are `Some(id)`, we should try to update the User record in the database with that id.  At this point it will
  // either successfully set the id or throw an exception if the user could not be found or if the id is already set to
  // a different value.
  //
  // In the error scenario, this code will short circuit and bubble up the exception, meaning there may be additional
  // updates that are not attempted.  That is OK.  If some updates are run and others are not, that does not mean those
  // that succeeded are invalid.  Similarly, if there are additional updates that failed to run due to the exception
  // that is OK.
  private def updateInvitedUser(user: SamUser, samRequestContext: SamRequestContext): IO[Unit] =
    for {
      _ <- user.googleSubjectId
        .map(directoryDAO.setGoogleSubjectId(user.id, _, samRequestContext))
        .getOrElse(IO.unit)
      _ <- user.azureB2CId
        .map(directoryDAO.setUserAzureB2CId(user.id, _, samRequestContext))
        .getOrElse(IO.unit)
      _ <- directoryDAO.setUserRegisteredAt(user.id, Instant.now(), samRequestContext)
    } yield ()

  private def registerInvitedUser(invitedUser: SamUser, invitedUserId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[SamUser] = {
    val userToRegister = invitedUser.copy(id = invitedUserId)
    for {
      _ <- updateInvitedUser(userToRegister, samRequestContext)
      groups <- directoryDAO.listUserDirectMemberships(userToRegister.id, samRequestContext)
      _ <- cloudExtensions.onGroupUpdate(groups, samRequestContext)
    } yield userToRegister
  }

  // For now, it looks like createUserInternal does what we need here, but added a new alias method here for naming
  // consistency and just in case things change more as we are refactoring
  private def registerBrandNewUser(user: SamUser, samRequestContext: SamRequestContext): IO[SamUser] =
    createUserInternal(user.copy(registeredAt = Option(Instant.now())), samRequestContext)

  // Would love to just call this "enableUser" but that name is already used
  private def makeUserEnabled(user: SamUser, samRequestContext: SamRequestContext): IO[SamUser] =
    enableUserInternal(user, samRequestContext).map(_ => user.copy(enabled = true))

  def addToAllUsersGroup(uid: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Unit] =
    for {
      allUsersGroup <- cloudExtensions.getOrCreateAllUsersGroup(directoryDAO, samRequestContext)
      _ <- directoryDAO.addGroupMember(allUsersGroup.id, uid, samRequestContext)
    } yield logger.info(s"Added user uid ${uid.value} to the All Users group")

  def inviteUser(inviteeEmail: WorkbenchEmail, samRequestContext: SamRequestContext): IO[UserStatusDetails] =
    for {
      _ <- validateEmailAddress(inviteeEmail, blockedEmailDomains)
      existingSubject <- directoryDAO.loadSubjectFromEmail(inviteeEmail, samRequestContext)
      createdUser <- existingSubject match {
        case None => createUserInternal(SamUser(genWorkbenchUserId(System.currentTimeMillis()), None, inviteeEmail, None, false), samRequestContext)
        case Some(_) =>
          IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"email ${inviteeEmail} already exists")))
      }
      _ <- setUserAttributes(SamUserAttributes(createdUser.id, marketingConsent = false), samRequestContext)
    } yield UserStatusDetails(createdUser.id, createdUser.email)

  private def createUserInternal(user: SamUser, samRequestContext: SamRequestContext): IO[SamUser] =
    for {
      createdUser <- directoryDAO.createUser(user, samRequestContext)
      _ <- cloudExtensions.onUserCreate(createdUser, samRequestContext)
    } yield createdUser

  def getUserFromGoogleSubjectId(userId: GoogleSubjectId, samRequestContext: SamRequestContext): IO[Option[SamUser]] =
    directoryDAO.loadUserByGoogleSubjectId(userId, samRequestContext)

  def getUserByAzureB2CId(userId: AzureB2CId, samRequestContext: SamRequestContext): IO[Option[SamUser]] =
    directoryDAO.loadUserByAzureB2CId(userId, samRequestContext)

  def getUserFromPetServiceAccount(petSA: ServiceAccountSubjectId, samRequestContext: SamRequestContext): IO[Option[SamUser]] =
    directoryDAO.getUserFromPetServiceAccount(petSA, samRequestContext)

  def getUserFromPetManagedIdentity(petManagedIdentityObjectId: ManagedIdentityObjectId, samRequestContext: SamRequestContext): IO[Option[SamUser]] =
    directoryDAO.getUserFromPetManagedIdentity(petManagedIdentityObjectId, samRequestContext)

  def getSubjectFromGoogleSubjectId(googleSubjectId: GoogleSubjectId, samRequestContext: SamRequestContext): IO[Option[WorkbenchSubject]] =
    directoryDAO.loadSubjectFromGoogleSubjectId(googleSubjectId, samRequestContext)
  def getSubjectFromEmail(email: WorkbenchEmail, samRequestContext: SamRequestContext): IO[Option[WorkbenchSubject]] =
    directoryDAO.loadSubjectFromEmail(email, samRequestContext)

  def setUserAzureB2CId(userId: WorkbenchUserId, b2cId: AzureB2CId, samRequestContext: SamRequestContext): IO[Unit] =
    directoryDAO.setUserAzureB2CId(userId, b2cId, samRequestContext)

  // Get User Status v1
  // This endpoint/method should probably be deprecated.
  // Getting the user status returns _some_ information about the user itself:
  //   - User's Sam ID (may or may not be the same value as the user's google subject ID)
  //   - User email
  // In addition, this endpoint also returns some information about various states of "enablement" for the user:
  //   - "ldap" - this is deprecated and should be removed
  //   - "allUsersGroup" - boolean indicating a whether a user is a member of the All Users Group in Sam.  When users
  //     register in Sam, they should be added to this group
  //   - "google" - boolean indicating whether the user's email address is listed as a member of their proxy group on
  //     Google
  //   - "adminEnabled" - boolean value read directly from the Sam User table
  def getUserStatus(userId: WorkbenchUserId, userDetailsOnly: Boolean = false, samRequestContext: SamRequestContext): IO[Option[UserStatus]] =
    directoryDAO.loadUser(userId, samRequestContext).flatMap {
      case Some(user) =>
        if (userDetailsOnly)
          IO.pure(Option(UserStatus(UserStatusDetails(user.id, user.email), Map.empty)))
        else
          for {
            googleStatus <- cloudExtensions.getUserStatus(user)
            allUsersGroup <- cloudExtensions.getOrCreateAllUsersGroup(directoryDAO, samRequestContext)
            allUsersStatus <- directoryDAO.isGroupMember(allUsersGroup.id, user.id, samRequestContext) recover { case _: NameNotFoundException =>
              false
            }
            tosComplianceStatus <- tosService.getTermsOfServiceComplianceStatus(user, samRequestContext)
            adminEnabled <- directoryDAO.isEnabled(user.id, samRequestContext)
          } yield {
            // We are removing references to LDAP but this will require an API version change here, so we are leaving
            // it for the moment.  The "ldap" status was previously returning the same "adminEnabled" value, so we are
            // leaving that logic unchanged for now.
            // ticket: https://broadworkbench.atlassian.net/browse/ID-266
            val enabledMap = Map(
              "ldap" -> adminEnabled,
              "allUsersGroup" -> allUsersStatus,
              "google" -> googleStatus,
              "adminEnabled" -> adminEnabled,
              "tosAccepted" -> tosComplianceStatus.permitsSystemUsage
            )
            val res = Option(UserStatus(UserStatusDetails(user.id, user.email), enabledMap))
            res
          }
      case None => IO.pure(None)
    }

  def acceptTermsOfService(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Option[UserStatus]] =
    for {
      _ <- tosService.acceptCurrentTermsOfService(userId, samRequestContext)
      status <- getUserStatus(userId, false, samRequestContext)
    } yield status

  def rejectTermsOfService(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Option[UserStatus]] =
    for {
      _ <- tosService.rejectCurrentTermsOfService(userId, samRequestContext)
      status <- getUserStatus(userId, false, samRequestContext)
    } yield status

  // `UserStatusInfo` is too complicated.  Yes seriously.  What the heck is the difference between "enabled" and
  // "adminEnabled"? Do our consumers know?  Do they care?  Should they care?  I think the answer is "no".  This class
  // should just have the user details and just a single boolean indicating if the user may or may not use the system.
  // Then again, why does this class have user details in it at all?  The caller knows who they are making the request
  // for, why are we returning user details in the response?  This whole object can go away and we can just return a
  // single boolean response indicating whether the user can use the system.
  // Then there can be a simple, separate endpoint for `getUserInfo` that just returns the user record and that's it.
  // Mixing up the endpoint to return user info AND status information is only causing problems and confusion
  def getUserStatusInfo(user: SamUser, samRequestContext: SamRequestContext): IO[UserStatusInfo] =
    for {
      tosAcceptanceDetails <- tosService.getTermsOfServiceComplianceStatus(user, samRequestContext)
    } yield UserStatusInfo(user.id.value, user.email.value, tosAcceptanceDetails.permitsSystemUsage && user.enabled, user.enabled)

  def getUserStatusDiagnostics(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Option[UserStatusDiagnostics]] =
    directoryDAO.loadUser(userId, samRequestContext).flatMap {
      case Some(user) =>
        // pulled out of for comprehension to allow concurrent execution
        val tosAcceptanceStatus = tosService.getTermsOfServiceComplianceStatus(user, samRequestContext)
        val adminEnabledStatus = directoryDAO.isEnabled(user.id, samRequestContext)
        val allUsersStatus = cloudExtensions.getOrCreateAllUsersGroup(directoryDAO, samRequestContext).flatMap { allUsersGroup =>
          directoryDAO.isGroupMember(allUsersGroup.id, user.id, samRequestContext) recover { case e: NameNotFoundException => false }
        }
        val googleStatus = cloudExtensions.getUserStatus(user)

        for {
          // We are removing references to LDAP but this will require an API version change here, so we are leaving
          // it for the moment.  The "ldap" status was previously returning the same "adminEnabled" value, so we are
          // leaving that logic unchanged for now.
          // ticket: https://broadworkbench.atlassian.net/browse/ID-266
          ldap <- adminEnabledStatus
          allUsers <- allUsersStatus
          tosAccepted <- tosAcceptanceStatus
          google <- googleStatus
          adminEnabled <- adminEnabledStatus
        } yield Option(UserStatusDiagnostics(ldap, allUsers, google, tosAccepted.permitsSystemUsage, adminEnabled))
      case None => IO.pure(None)
    }

  // TODO: return type should be refactored into ADT for easier read
  def getUserIdInfoFromEmail(email: WorkbenchEmail, samRequestContext: SamRequestContext): IO[Either[Unit, Option[UserIdInfo]]] =
    directoryDAO.loadSubjectFromEmail(email, samRequestContext).flatMap {
      // don't attempt to handle groups or service accounts - just users
      case Some(user: WorkbenchUserId) =>
        directoryDAO.loadUser(user, samRequestContext).map {
          case Some(loadedUser) => Right(Option(UserIdInfo(loadedUser.id, loadedUser.email, loadedUser.googleSubjectId)))
          case _ => Left(())
        }
      case Some(_: WorkbenchGroupName) => IO.pure(Right(None))
      case _ => IO.pure(Left(()))
    }

  def getUserStatusFromEmail(email: WorkbenchEmail, samRequestContext: SamRequestContext): IO[Option[UserStatus]] =
    directoryDAO.loadSubjectFromEmail(email, samRequestContext).flatMap {
      // don't attempt to handle groups or service accounts - just users
      case Some(user: WorkbenchUserId) => getUserStatus(user, samRequestContext = samRequestContext)
      case _ => IO.pure(None)
    }

  def enableUser(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Option[UserStatus]] =
    directoryDAO.loadUser(userId, samRequestContext).flatMap {
      case Some(user) =>
        for {
          _ <- enableUserInternal(user, samRequestContext)
          userStatus <- getUserStatus(userId, samRequestContext = samRequestContext)
        } yield userStatus
      case None => IO.pure(None)
    }

  private def enableUserInternal(user: SamUser, samRequestContext: SamRequestContext): IO[Unit] =
    for {
      _ <- directoryDAO.enableIdentity(user.id, samRequestContext)
      _ <- cloudExtensions.onUserEnable(user, samRequestContext)
    } yield logger.info(s"Enabled user ${user.toUserIdInfo}")

  def disableUser(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Option[UserStatus]] =
    directoryDAO.loadUser(userId, samRequestContext).flatMap {
      case Some(user) =>
        for {
          _ <- directoryDAO.disableIdentity(user.id, samRequestContext)
          _ <- cloudExtensions.onUserDisable(user, samRequestContext)
          userStatus <- getUserStatus(user.id, samRequestContext = samRequestContext)
        } yield userStatus
      case None => IO.pure(None)
    }

  def deleteUser(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Unit] =
    for {
      allUsersGroup <- cloudExtensions.getOrCreateAllUsersGroup(directoryDAO, samRequestContext)
      _ <- directoryDAO
        .removeGroupMember(allUsersGroup.id, userId, samRequestContext)
        .withInfoLogMessage(s"Removed $userId from the All Users group")
      _ <- cloudExtensions.onUserDelete(userId, samRequestContext)
      _ <- directoryDAO.deleteUser(userId, samRequestContext)
    } yield logger.info(s"Deleted user $userId")

  // moved this method from the UserService companion object into this class
  // because Mockito would not let us spy/mock the static method
  def validateEmailAddress(email: WorkbenchEmail, blockedEmailDomains: Seq[String]): IO[Unit] =
    email.value match {
      case emailString if blockedEmailDomains.exists(domain => emailString.endsWith("@" + domain) || emailString.endsWith("." + domain)) =>
        IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, s"email domain not permitted [${email.value}]")))
      case UserService.emailRegex() => IO.unit
      case _ => IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, s"invalid email address [${email.value}]")))
    }

  def getUserAllowances(samUser: SamUser, samRequestContext: SamRequestContext): IO[SamUserAllowances] =
    for {
      tosStatus <- tosService.getTermsOfServiceComplianceStatus(samUser, samRequestContext)
    } yield SamUserAllowances(
      enabled = samUser.enabled,
      termsOfService = tosStatus.permitsSystemUsage
    )

  def getUserAttributes(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Option[SamUserAttributes]] =
    directoryDAO.getUserAttributes(userId, samRequestContext)

  def setUserAttributesFromRequest(
      userId: WorkbenchUserId,
      userAttributesRequest: SamUserAttributesRequest,
      samRequestContext: SamRequestContext
  ): IO[SamUserAttributes] =
    for {
      userAttributesOpt <- getUserAttributes(userId, samRequestContext)
      updatedAttributes <- userAttributesOpt match {
        case Some(currentUserAttributes) =>
          currentUserAttributes.updateFromUserAttributesRequest(userAttributesRequest)
        case None =>
          SamUserAttributes.newUserAttributesFromRequest(userId, userAttributesRequest)
      }
      savedAttributes <- setUserAttributes(updatedAttributes, samRequestContext)
    } yield savedAttributes

  def setUserAttributes(userAttributes: SamUserAttributes, samRequestContext: SamRequestContext): IO[SamUserAttributes] =
    directoryDAO.setUserAttributes(userAttributes, samRequestContext).map(_ => userAttributes)
}

object UserService {

  val random = SecureRandom.getInstance("NativePRNGNonBlocking")

  // from https://www.regular-expressions.info/email.html
  val emailRegex: Regex = "(?i)^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$".r

  // Generate a 21 digits unique identifier. First char is fixed 2
  // CurrentMillis.append(randomString)
  private[workbench] def genRandom(currentMilli: Long): String = {
    val currentMillisString = currentMilli.toString
    // one hexadecimal is 4 bits, one byte can generate 2 hexadecimal number, so we only need half the number of bytes, which is 8
    // currentMilli is 13 digits, and it'll be another 200 years before it becomes 14 digits. So we're assuming currentMillis is 13 digits here
    val bytes = new Array[Byte](4)
    random.nextBytes(bytes)
    val r = new String(Hex.encodeHex(bytes))
    // since googleSubjectId starts with 1, we are replacing 1 with 2 to avoid conflicts with existing uid
    val front = if (currentMillisString(0) == '1') currentMillisString.replaceFirst("1", "2") else currentMilli.toString
    front + r
  }

  def genWorkbenchUserId(currentMilli: Long): WorkbenchUserId =
    WorkbenchUserId(genRandom(currentMilli))
}
