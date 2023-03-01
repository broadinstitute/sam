package org.broadinstitute.dsde.workbench.sam
package service

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.codec.binary.Hex
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.openTelemetry.OpenTelemetryMetrics
import org.broadinstitute.dsde.workbench.sam.dataAccess.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service.UserService.genWorkbenchUserId
import org.broadinstitute.dsde.workbench.sam.util.AsyncLogging.IOWithLogging
import org.broadinstitute.dsde.workbench.sam.util.{API_TIMING_DURATION_BUCKET, SamRequestContext}

import java.security.SecureRandom
import javax.naming.NameNotFoundException
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}
import scala.util.matching.Regex

/** Created by dvoet on 7/14/17.
  */
class UserService(val directoryDAO: DirectoryDAO, val cloudExtensions: CloudExtensions, blockedEmailDomains: Seq[String], tosService: TosService)(implicit
    val executionContext: ExecutionContext,
    val openTelemetry: OpenTelemetryMetrics[IO]
) extends LazyLogging {

  def createUser(possibleNewUser: SamUser, samRequestContext: SamRequestContext): IO[UserStatus] = {
    openTelemetry.time("api.v1.user.create.time", API_TIMING_DURATION_BUCKET) {
      // Validate the values set on the possible new user, short circuit if there's a problem
      val validationErrors = validateUser(possibleNewUser)
      if (validationErrors.nonEmpty) {
        IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, "invalid user", validationErrors)))
      }

      verifyUserIsNotAlreadyRegistered(possibleNewUser).map {
        case Some(errorReport) => IO.raiseError(new WorkbenchExceptionWithErrorReport(errorReport))
        case None => _
      }

      // Note: this block is not broken out into its own method because it raises an error, and we are trying to keep
      // all the side effects isolated to createUser
      val newlyRegisteredUser: IO[SamUser] = loadUserIdFromEmail(possibleNewUser.email).flatMap {
        case Success(maybeUserId) => handleUserRegistration(possibleNewUser, maybeUserId)
        // Fails if email is found but it matches a non-User subject
        case Failure(message) => IO.raiseError(
          new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, message))
        )
      }

      val fullyCreatedUser = for {
        registeredUser <- newlyRegisteredUser
        registeredAndEnabledUser <- makeUserEnabled(registeredUser)
        _ <- addToAllUsersGroup(registeredAndEnabledUser.id, samRequestContext = ???)
      } yield registeredAndEnabledUser

      // We should only make it this far if we successfully perform all of the above steps to set all of the
      // UserStatus.enabled fields to true, with the exception of ToS.  So we should be able to safely just return true
      // for all of these things without needing to go recalculate them.
      fullyCreatedUser.map(user => UserStatus(
        UserStatusDetails(user.id, user.email),
        Map("ldap" -> true,
          "allUsersGroup" -> true,
          "google" -> true,
          "adminEnabled" -> true,
          "tosAccepted" -> false // Not sure about this one, but pretty sure this should always be false for a newly created user
        )))
    }
  }

  // If the user's ID already exists, then they were invited and we just need to update their record to complete
  // their registration
  // If the user does not already have an ID, then they're a new user and we need to create a new record for them
  private def handleUserRegistration(user: SamUser, maybeUserId: Option[WorkbenchUserId]): IO[SamUser] = {
    maybeUserId match {
      case Some(invitedUserId) => handleInvitedUser(user, invitedUserId)
      case None => handleBrandNewUser(user)
    }
  }

  private def validateUser(user: SamUser): Seq[ErrorReport] = {
    validateUserIds(user) ++ validateEmail(user.email)
  }

  // user record has to have a GoogleSubjectId and/or an AzureB2CId
  private def validateUserIds(user: SamUser): Seq[ErrorReport] = ???

  private def validateEmail(email: WorkbenchEmail): Seq[ErrorReport] = ???

  private def verifyUserIsNotAlreadyRegistered(user: SamUser): IO[Option[ErrorReport]] = {
    loadRegisteredUser(user).map { maybeUser =>
      maybeUser.map { user =>
        ErrorReport(StatusCodes.Conflict, s"user ${user.email} is already registered")
      }
    }
  }

  // Try to find user by GoogleSubject, AzureB2CId
  // A registered user is one that has a record in the database and has a Cloud Identifier specified
  private def loadRegisteredUser(user: SamUser): IO[Option[SamUser]] = {
    if(user.googleSubjectId.nonEmpty) {
      directoryDAO.loadUserByGoogleSubjectId(user.googleSubjectId.get, samRequestContext = ???)
    } else if(user.azureB2CId.nonEmpty) {
      directoryDAO.loadUserByAzureB2CId(user.azureB2CId.get, samRequestContext = ???)
    } else {
      IO(None)
    }
  }

  private def validateUserCanRegister(user: SamUser): IO[Seq[ErrorReport]] = {
    loadRegisteredUser(user).map {
      case Some(_) => Seq(ErrorReport(StatusCodes.Conflict, s"user ${user.email} already exists"))
      case None => ???
    }
  }

  // Failure is returned if the email address already exists as a non-user subject's email
  // Success[None] is returned if the email address is not already in Sam for any Subject type (including Users)
  // Success[Some[WorkbenchUserId]] is returned if the email address was found for a record in the User table
  private def loadUserIdFromEmail(email: WorkbenchEmail): IO[Try[Option[WorkbenchUserId]]] = {
    directoryDAO.loadSubjectFromEmail(email, samRequestContext = ???).map {
      case Some(userId: WorkbenchUserId) => Success(Option(userId))
      case None => Success(None)
      case Some(_) => Failure(new WorkbenchException(s"$email is not a regular user. Please use a different endpoint"))
    }
  }

  // TODO: Add a simple "updateUser" method to directoryDAO so we can do all this in one call and return the updated user record
  private def updateUser(existingUserId: WorkbenchUserId, user: SamUser): IO[SamUser] = {
    for {
      _ <- if(user.googleSubjectId.nonEmpty) directoryDAO.setGoogleSubjectId(existingUserId, user.googleSubjectId.get, samRequestContext = ???) else IO.unit
      _ <- if(user.azureB2CId.nonEmpty) directoryDAO.setUserAzureB2CId(existingUserId, user.azureB2CId.get, samRequestContext = ???) else IO.unit
    } yield user.copy(id = existingUserId)
  }

  // TODO: Side effect :(
  private def handleInvitedUser(invitedUser: SamUser, invitedUserId: WorkbenchUserId): IO[SamUser] = {
      for {
        updatedUser <- updateUser(invitedUserId, invitedUser)
        groups <- directoryDAO.listUserDirectMemberships(updatedUser.id, samRequestContext = ???)
        _ <- IO.fromFuture(IO(cloudExtensions.onGroupUpdate(groups, samRequestContext = ???)))
      } yield {
        updatedUser
      }
  }

  // For now, it looks like createUserInternal does what we need here, but added a new alias method here for naming
  // consistency and just in case things change more as we are refactoring
  private def handleBrandNewUser(possibleSamUser: SamUser): IO[SamUser] =
    createUserInternal(possibleSamUser, samRequestContext = ???)

  private def makeUserEnabled(user: SamUser): IO[SamUser] =
    enableUserInternal(user, samRequestContext = ???).map(_ => user.copy(enabled = true))

//  def createUser(user: SamUser, samRequestContext: SamRequestContext): IO[UserStatus] =
//    openTelemetry.time("api.v1.user.create.time", API_TIMING_DURATION_BUCKET) {
//      for {
//        _ <- validateEmailAddress(user.email, blockedEmailDomains)
//        createdUser <- registerUser(user, samRequestContext)
//        _ <- enableUserInternal(createdUser, samRequestContext)
//        _ <- addToAllUsersGroup(createdUser.id, samRequestContext)
//        userStatus <- getUserStatus(createdUser.id, samRequestContext = samRequestContext)
//        res <- IO
//          .fromOption(userStatus)(new WorkbenchException("getUserStatus returned None after user was created"))
//          .withInfoLogMessage(s"New user ${createdUser.toUserIdInfo} was successfully created")
//      } yield res
//    }

  def addToAllUsersGroup(uid: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Unit] =
    openTelemetry.time("api.v1.user.addToAllUsersGroup.time", API_TIMING_DURATION_BUCKET) {
      for {
        allUsersGroup <- cloudExtensions.getOrCreateAllUsersGroup(directoryDAO, samRequestContext)
        _ <- directoryDAO.addGroupMember(allUsersGroup.id, uid, samRequestContext)
      } yield logger.info(s"Added user uid ${uid.value} to the All Users group")
    }

  def inviteUser(inviteeEmail: WorkbenchEmail, samRequestContext: SamRequestContext): IO[UserStatusDetails] =
    openTelemetry.time("api.v1.user.invite.time", API_TIMING_DURATION_BUCKET) {
      for {
        _ <- validateEmailAddress(inviteeEmail, blockedEmailDomains)
        existingSubject <- directoryDAO.loadSubjectFromEmail(inviteeEmail, samRequestContext)
        createdUser <- existingSubject match {
          case None => createUserInternal(SamUser(genWorkbenchUserId(System.currentTimeMillis()), None, inviteeEmail, None, false, None), samRequestContext)
          case Some(_) =>
            IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"email ${inviteeEmail} already exists")))
        }
      } yield UserStatusDetails(createdUser.id, createdUser.email)
    }

  /** First lookup user by either googleSubjectId or azureB2DId, whichever is populated. If the user exists throw a conflict error. If the user does not exist
    * look them up by email. If the user email exists then this is an invited user, update their googleSubjectId and/or azureB2CId and return the updated user
    * record. If the email does not exist, this is a new user, create them. It is critical that this method returns the updated/created SamUser record FROM THE
    * DATABASE and not the SamUser passed in as the first parameter.
    */
  protected[service] def registerUser(user: SamUser, samRequestContext: SamRequestContext): IO[SamUser] =
    openTelemetry.time("api.v1.user.register.time", API_TIMING_DURATION_BUCKET) {
      for {
        _ <- validateNewWorkbenchUser(user, samRequestContext)
        subjectWithEmail <- directoryDAO.loadSubjectFromEmail(user.email, samRequestContext)
        updated <- subjectWithEmail match {
          case Some(uid: WorkbenchUserId) =>
            acceptInvitedUser(user, samRequestContext, uid)
              .withInfoLogMessage(s"Accepted invited user ${user.email} with uid ${uid.value}")

          case None =>
            createUserInternal(user, samRequestContext)
              .withComputedInfoLogMessage(u => s"Created user ${u.email} with uid ${u.id}")

          case Some(_) =>
            // We don't support inviting a group account or pet service account
            IO.raiseError[SamUser](
              new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, s"$user is not a regular user. Please use a different endpoint"))
            )

        }
      } yield updated
    }

  private def acceptInvitedUser(user: SamUser, samRequestContext: SamRequestContext, uid: WorkbenchUserId): IO[SamUser] =
    openTelemetry.time("api.v1.user.acceptInvited.time", API_TIMING_DURATION_BUCKET) {
      for {
        groups <- directoryDAO.listUserDirectMemberships(uid, samRequestContext)
        _ <- user.googleSubjectId.traverse { googleSubjectId =>
          for {
            _ <- directoryDAO.setGoogleSubjectId(uid, googleSubjectId, samRequestContext)
          } yield ()
        }
        _ <- user.azureB2CId.traverse { azureB2CId =>
          directoryDAO.setUserAzureB2CId(uid, azureB2CId, samRequestContext)
        }
        _ <- IO.fromFuture(IO(cloudExtensions.onGroupUpdate(groups, samRequestContext)))
        updatedUser <- directoryDAO.loadUser(uid, samRequestContext)
      } yield updatedUser.getOrElse(
        throw new WorkbenchExceptionWithErrorReport(
          ErrorReport(StatusCodes.InternalServerError, s"$user could not be accepted from invite because user could not be loaded from the db")
        )
      )
    }

  private def validateNewWorkbenchUser(newWorkbenchUser: SamUser, samRequestContext: SamRequestContext): IO[Unit] =
    openTelemetry.time("api.v1.user.validateNewWorkbenchUser.time", API_TIMING_DURATION_BUCKET) {
      for {
        existingUser <- newWorkbenchUser match {
          case SamUser(_, Some(googleSubjectId), _, _, _, _) => directoryDAO.loadSubjectFromGoogleSubjectId(googleSubjectId, samRequestContext)
          case SamUser(_, _, _, Some(azureB2CId), _, _) => directoryDAO.loadUserByAzureB2CId(azureB2CId, samRequestContext).map(_.map(_.id))
          case _ => IO.raiseError(new WorkbenchException("cannot create user when neither google subject id nor azure b2c id exists"))
        }

        _ <- existingUser match {
          case Some(_) =>
            IO.raiseError[SamUser](new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"user ${newWorkbenchUser.email} already exists")))
          case None => IO.unit
        }
      } yield ()
    }

  private def createUserInternal(user: SamUser, samRequestContext: SamRequestContext): IO[SamUser] =
    openTelemetry.time("api.v1.createInternal.invite.time", API_TIMING_DURATION_BUCKET) {
      for {
        createdUser <- directoryDAO.createUser(user, samRequestContext)
        _ <- cloudExtensions.onUserCreate(createdUser, samRequestContext)
      } yield createdUser
    }

  def getSubjectFromEmail(email: WorkbenchEmail, samRequestContext: SamRequestContext): IO[Option[WorkbenchSubject]] =
    directoryDAO.loadSubjectFromEmail(email, samRequestContext)

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
    openTelemetry.time("api.v1.user.getStatus.time", API_TIMING_DURATION_BUCKET) {
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
              tosComplianceStatus <- tosService.getTosComplianceStatus(user)
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
    }

  def acceptTermsOfService(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Option[UserStatus]] =
    for {
      _ <- tosService.acceptTosStatus(userId, samRequestContext)
      status <- getUserStatus(userId, false, samRequestContext)
    } yield status

  def rejectTermsOfService(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Option[UserStatus]] =
    for {
      _ <- tosService.rejectTosStatus(userId, samRequestContext)
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
      tosAcceptanceDetails <- tosService.getTosComplianceStatus(user)
    } yield UserStatusInfo(user.id.value, user.email.value, tosAcceptanceDetails.permitsSystemUsage && user.enabled, user.enabled)

  def getUserStatusDiagnostics(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Option[UserStatusDiagnostics]] =
    openTelemetry.time("api.v1.user.statusDiagnostics.time", API_TIMING_DURATION_BUCKET) {
      directoryDAO.loadUser(userId, samRequestContext).flatMap {
        case Some(user) =>
          // pulled out of for comprehension to allow concurrent execution
          val tosAcceptanceStatus = tosService.getTosComplianceStatus(user)
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
    }

  // TODO: return type should be refactored into ADT for easier read
  def getUserIdInfoFromEmail(email: WorkbenchEmail, samRequestContext: SamRequestContext): IO[Either[Unit, Option[UserIdInfo]]] =
    openTelemetry.time("api.v1.user.idInfoFromEmail.time", API_TIMING_DURATION_BUCKET) {
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
    }

  def getUserStatusFromEmail(email: WorkbenchEmail, samRequestContext: SamRequestContext): IO[Option[UserStatus]] =
    directoryDAO.loadSubjectFromEmail(email, samRequestContext).flatMap {
      // don't attempt to handle groups or service accounts - just users
      case Some(user: WorkbenchUserId) => getUserStatus(user, samRequestContext = samRequestContext)
      case _ => IO.pure(None)
    }

  def enableUser(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Option[UserStatus]] =
    openTelemetry.time("api.v1.user.enable.time", API_TIMING_DURATION_BUCKET) {
      directoryDAO.loadUser(userId, samRequestContext).flatMap {
        case Some(user) =>
          for {
            _ <- enableUserInternal(user, samRequestContext)
            userStatus <- getUserStatus(userId, samRequestContext = samRequestContext)
          } yield userStatus
        case None => IO.pure(None)
      }
    }

  private def enableUserInternal(user: SamUser, samRequestContext: SamRequestContext): IO[Unit] =
    openTelemetry.time("api.v1.user.enableInternal.time", API_TIMING_DURATION_BUCKET) {
      for {
        _ <- directoryDAO.enableIdentity(user.id, samRequestContext)
        _ <- cloudExtensions.onUserEnable(user, samRequestContext)
      } yield logger.info(s"Enabled user ${user.toUserIdInfo}")
    }

  val serviceAccountDomain = "\\S+@\\S+\\.gserviceaccount\\.com".r

  private def isServiceAccount(email: String) =
    serviceAccountDomain.pattern.matcher(email).matches

  def disableUser(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Option[UserStatus]] =
    openTelemetry.time("api.v1.user.disable.time", API_TIMING_DURATION_BUCKET) {
      directoryDAO.loadUser(userId, samRequestContext).flatMap {
        case Some(user) =>
          for {
            _ <- directoryDAO.disableIdentity(user.id, samRequestContext)
            _ <- cloudExtensions.onUserDisable(user, samRequestContext)
            userStatus <- getUserStatus(user.id, samRequestContext = samRequestContext)
          } yield userStatus
        case None => IO.pure(None)
      }
    }

  def deleteUser(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Unit] =
    openTelemetry.time("api.v1.user.delete.time", API_TIMING_DURATION_BUCKET) {
      for {
        allUsersGroup <- cloudExtensions.getOrCreateAllUsersGroup(directoryDAO, samRequestContext)
        _ <- directoryDAO
          .removeGroupMember(allUsersGroup.id, userId, samRequestContext)
          .withInfoLogMessage(s"Removed $userId from the All Users group")
        _ <- cloudExtensions.onUserDelete(userId, samRequestContext)
        _ <- directoryDAO.deleteUser(userId, samRequestContext)
      } yield logger.info(s"Deleted user $userId")
    }

  // moved this method from the UserService companion object into this class
  // because Mockito would not let us spy/mock the static method
  def validateEmailAddress(email: WorkbenchEmail, blockedEmailDomains: Seq[String]): IO[Unit] =
    email.value match {
      case emailString if blockedEmailDomains.exists(domain => emailString.endsWith("@" + domain) || emailString.endsWith("." + domain)) =>
        IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, s"email domain not permitted [${email.value}]")))
      case UserService.emailRegex() => IO.unit
      case _ => IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, s"invalid email address [${email.value}]")))
    }
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
