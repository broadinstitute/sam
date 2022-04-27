package org.broadinstitute.dsde.workbench.sam
package service

import java.security.SecureRandom
import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging

import javax.naming.NameNotFoundException
import org.apache.commons.codec.binary.Hex
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.api.InviteUser
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, RegistrationDAO}
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

import scala.concurrent.{ExecutionContext, Future}
import scala.util.matching.Regex

/**
  * Created by dvoet on 7/14/17.
  */
class UserService(val directoryDAO: DirectoryDAO, val cloudExtensions: CloudExtensions, val registrationDAO: RegistrationDAO, blockedEmailDomains: Seq[String], tosService: TosService)(implicit val executionContext: ExecutionContext) extends LazyLogging {

  def createUser(user: WorkbenchUser, samRequestContext: SamRequestContext): Future[UserStatus] = {
    for {
      _ <- UserService.validateEmailAddress(user.email, blockedEmailDomains).unsafeToFuture()
      allUsersGroup <- cloudExtensions.getOrCreateAllUsersGroup(directoryDAO, samRequestContext)
      createdUser <- registerUser(user, samRequestContext).unsafeToFuture()
      _ <- enableUserInternal(createdUser, samRequestContext)
      _ <- directoryDAO.addGroupMember(allUsersGroup.id, createdUser.id, samRequestContext).unsafeToFuture()
      userStatus <- getUserStatus(createdUser.id, samRequestContext = samRequestContext)
      res <- userStatus.toRight(new WorkbenchException("getUserStatus returned None after user was created")).fold(Future.failed, Future.successful)
    } yield res
  }


  def inviteUser(invitee: InviteUser, samRequestContext: SamRequestContext): IO[UserStatusDetails] =
    for {
      _ <- UserService.validateEmailAddress(invitee.inviteeEmail, blockedEmailDomains)
      existingSubject <- directoryDAO.loadSubjectFromEmail(invitee.inviteeEmail, samRequestContext)
      createdUser <- existingSubject match {
        case None => createUserInternal(WorkbenchUser(invitee.inviteeId, None, invitee.inviteeEmail, None), samRequestContext)
        case Some(_) =>
          IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"email ${invitee.inviteeEmail} already exists")))
      }
    } yield UserStatusDetails(createdUser.id, createdUser.email)

  /**
    * First lookup user by either googleSubjectId or azureB2DId, whichever is populated. If the user exists
    * throw a conflict error. If the user does not exist look them up by email. If the user email exists
    * then this is an invited user, update their googleSubjectId and/or azureB2CId. If the email does not exist,
    * this is a new user, create them.
    */
  protected[service] def registerUser(user: WorkbenchUser, samRequestContext: SamRequestContext): IO[WorkbenchUser] =
    for {
      _ <- validateNewWorkbenchUser(user, samRequestContext)
      subjectWithEmail <- directoryDAO.loadSubjectFromEmail(user.email, samRequestContext)
      updated <- subjectWithEmail match {
        case Some(uid: WorkbenchUserId) =>
          acceptInvitedUser(user, samRequestContext, uid)

        case None =>
          createUserInternal(WorkbenchUser(user.id, user.googleSubjectId, user.email, user.azureB2CId), samRequestContext)

        case Some(_) =>
          //We don't support inviting a group account or pet service account
          IO.raiseError[WorkbenchUser](
            new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, s"$user is not a regular user. Please use a different endpoint")))

      }
    } yield updated

  private def acceptInvitedUser(user: WorkbenchUser, samRequestContext: SamRequestContext, uid: WorkbenchUserId) = {
    for {
      groups <- directoryDAO.listUserDirectMemberships(uid, samRequestContext)
      _ <- user.googleSubjectId.traverse { googleSubjectId =>
        for {
          _ <- directoryDAO.setGoogleSubjectId(uid, googleSubjectId, samRequestContext)
          _ <- registrationDAO.setGoogleSubjectId(uid, googleSubjectId, samRequestContext)
        } yield ()
      }
      _ <- user.azureB2CId.traverse { azureB2CId =>
        directoryDAO.setUserAzureB2CId(uid, azureB2CId, samRequestContext)
      }
      _ <- IO.fromFuture(IO(cloudExtensions.onGroupUpdate(groups, samRequestContext)))
    } yield WorkbenchUser(uid, user.googleSubjectId, user.email, user.azureB2CId)
  }

  private def validateNewWorkbenchUser(newWorkbenchUser: WorkbenchUser, samRequestContext: SamRequestContext): IO[Unit] = {
    for {
      existingUser <- newWorkbenchUser match {
        case WorkbenchUser(_, Some(googleSubjectId), _, _) => directoryDAO.loadSubjectFromGoogleSubjectId(googleSubjectId, samRequestContext)
        case WorkbenchUser(_, _, _, Some(azureB2CId)) => directoryDAO.loadUserByAzureB2CId(azureB2CId, samRequestContext).map(_.map(_.id))
        case _ => IO.raiseError(new WorkbenchException("cannot create user when neither google subject id nor azure b2c id exists"))
      }

      _ <- existingUser match {
        case Some(_) => IO.raiseError[WorkbenchUser](new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"user ${newWorkbenchUser.email} already exists")))
        case None => IO.unit
      }
    } yield ()
  }

  private def createUserInternal(user: WorkbenchUser, samRequestContext: SamRequestContext) = {
    for {
      createdUser <- directoryDAO.createUser(user, samRequestContext)
      _ <- registrationDAO.createUser(user, samRequestContext)
      _ <- IO.fromFuture(IO(cloudExtensions.onUserCreate(createdUser, samRequestContext)))
    } yield createdUser
  }
  def getSubjectFromEmail(email: WorkbenchEmail, samRequestContext: SamRequestContext): Future[Option[WorkbenchSubject]] = directoryDAO.loadSubjectFromEmail(email, samRequestContext).unsafeToFuture()

  def getUserStatus(userId: WorkbenchUserId, userDetailsOnly: Boolean = false, samRequestContext: SamRequestContext): Future[Option[UserStatus]] = {
    directoryDAO.loadUser(userId, samRequestContext).unsafeToFuture().flatMap {
      case Some(user) =>
        if (userDetailsOnly)
          Future.successful(Option(UserStatus(UserStatusDetails(user.id, user.email), Map.empty)))
        else
          for {
            googleStatus <- cloudExtensions.getUserStatus(user)
            allUsersGroup <- cloudExtensions.getOrCreateAllUsersGroup(directoryDAO, samRequestContext)
            allUsersStatus <- directoryDAO.isGroupMember(allUsersGroup.id, user.id, samRequestContext).unsafeToFuture() recover { case _: NameNotFoundException => false }
            tosAcceptedStatus <- tosService.getTosStatus(user.id).unsafeToFuture()
            ldapStatus <- registrationDAO.isEnabled(user.id, samRequestContext).unsafeToFuture()
            adminEnabled <- directoryDAO.isEnabled(user.id, samRequestContext).unsafeToFuture()
          } yield {
            val enabledMap = Map("ldap" -> ldapStatus, "allUsersGroup" -> allUsersStatus, "google" -> googleStatus)
            val enabledStatuses = tosAcceptedStatus match {
              case Some(status) => enabledMap + ("tosAccepted" -> status) + ("adminEnabled" -> adminEnabled)
              case None => enabledMap
            }
            val res = Option(UserStatus(UserStatusDetails(user.id, user.email), enabledStatuses))
            res
          }
      case None => Future.successful(None)
    }
  }

  def acceptTermsOfService(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Option[UserStatus]] = {
    directoryDAO.loadUser(userId, samRequestContext).flatMap {
      case Some(_) =>
        for {
          _ <- tosService.acceptTosStatus(userId)
          enabled <- directoryDAO.isEnabled(userId, samRequestContext)
          _ <- if (enabled) registrationDAO.enableIdentity(userId, samRequestContext) else IO.none
          status <- IO.fromFuture(IO(getUserStatus(userId, false, samRequestContext)))
        } yield status
      case None => IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"Could not accept the Terms of Service. User not found.")))
    }
  }

  def rejectTermsOfService(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Option[UserStatus]] = {
    directoryDAO.loadUser(userId, samRequestContext).flatMap {
      case Some(_) =>
        for {
          _ <- tosService.rejectTosStatus(userId)
          _ <- registrationDAO.disableIdentity(userId, samRequestContext)
          status <- IO.fromFuture(IO(getUserStatus(userId, false, samRequestContext)))
        } yield status
      case None => IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"Could not reject the Terms of Service. User not found.")))
    }
  }

  def getTermsOfServiceStatus(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Option[Boolean]] = {
    tosService.getTosStatus(userId)
  }

  def getUserStatusInfo(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Option[UserStatusInfo]] =
    directoryDAO.loadUser(userId, samRequestContext).flatMap {
      case Some(user) =>
        for {
          tosStatus <- getTermsOfServiceStatusInternal(user, samRequestContext)
          adminEnabled <- directoryDAO.isEnabled(user.id, samRequestContext)
        } yield {
          Some(UserStatusInfo(user.id.value, user.email.value, tosStatus && adminEnabled, adminEnabled))
        }
      case None => IO.pure(None)
    }

  /**
    * If grace period enabled, don't check ToS, return true
    * If ToS disabled, return true
    * Otherwise return true if user has accepted ToS
    */
  private def getTermsOfServiceStatusInternal(user: WorkbenchUser, samRequestContext: SamRequestContext) = {
    if (tosService.tosConfig.isGracePeriodEnabled) {
      IO.pure(true)
    } else {
      getTermsOfServiceStatus(user.id, samRequestContext).map(_.getOrElse(true))
    }
  }

  def getUserStatusDiagnostics(userId: WorkbenchUserId, samRequestContext: SamRequestContext): Future[Option[UserStatusDiagnostics]] =
    directoryDAO.loadUser(userId, samRequestContext).unsafeToFuture().flatMap {
      case Some(user) => {
        // pulled out of for comprehension to allow concurrent execution
        val ldapStatus = registrationDAO.isEnabled(user.id, samRequestContext).unsafeToFuture()
        val tosAcceptedStatus = tosService.getTosStatus(user.id).unsafeToFuture()
        val adminEnabledStatus = directoryDAO.isEnabled(user.id, samRequestContext).unsafeToFuture()
        val allUsersStatus = cloudExtensions.getOrCreateAllUsersGroup(directoryDAO, samRequestContext).flatMap { allUsersGroup =>
          directoryDAO.isGroupMember(allUsersGroup.id, user.id, samRequestContext).unsafeToFuture() recover { case e: NameNotFoundException => false }
        }
        val googleStatus = cloudExtensions.getUserStatus(user)

        for {
          ldap <- ldapStatus
          allUsers <- allUsersStatus
          tosAccepted <- tosAcceptedStatus
          google <- googleStatus
          adminEnabled <- adminEnabledStatus
        } yield Option(UserStatusDiagnostics(ldap, allUsers, google, tosAccepted, adminEnabled))
      }
      case None => Future.successful(None)
    }

  //TODO: return type should be refactored into ADT for easier read
  def getUserIdInfoFromEmail(email: WorkbenchEmail, samRequestContext: SamRequestContext): Future[Either[Unit, Option[UserIdInfo]]] =
    directoryDAO.loadSubjectFromEmail(email, samRequestContext).unsafeToFuture().flatMap {
      // don't attempt to handle groups or service accounts - just users
      case Some(user: WorkbenchUserId) =>
        directoryDAO.loadUser(user, samRequestContext).unsafeToFuture().map {
          case Some(loadedUser) => Right(Option(UserIdInfo(loadedUser.id, loadedUser.email, loadedUser.googleSubjectId)))
          case _ => Left(())
        }
      case Some(_: WorkbenchGroupName) => Future.successful(Right(None))
      case _ => Future.successful(Left(()))
    }

  def getUserStatusFromEmail(email: WorkbenchEmail, samRequestContext: SamRequestContext): Future[Option[UserStatus]] =
    directoryDAO.loadSubjectFromEmail(email, samRequestContext).unsafeToFuture().flatMap {
      // don't attempt to handle groups or service accounts - just users
      case Some(user: WorkbenchUserId) => getUserStatus(user, samRequestContext = samRequestContext)
      case _ => Future.successful(None)
    }

  def enableUser(userId: WorkbenchUserId, samRequestContext: SamRequestContext): Future[Option[UserStatus]] =
    directoryDAO.loadUser(userId, samRequestContext).unsafeToFuture().flatMap {
      case Some(user) =>
        for {
          _ <- enableUserInternal(user, samRequestContext)
          userStatus <- getUserStatus(userId, samRequestContext = samRequestContext)
        } yield userStatus
      case None => Future.successful(None)
    }

  private def enableUserInternal(user: WorkbenchUser, samRequestContext: SamRequestContext): Future[Unit] = {
    for {
      _ <- directoryDAO.enableIdentity(user.id, samRequestContext).unsafeToFuture()
      _ <- enableIdentityIfTosAccepted(user, samRequestContext).unsafeToFuture()
      _ <- cloudExtensions.onUserEnable(user, samRequestContext)
    } yield ()
  }

  val serviceAccountDomain = "\\S+@\\S+\\.gserviceaccount\\.com".r

  private def isServiceAccount(email: String) = {
    serviceAccountDomain.pattern.matcher(email).matches
  }

  private def enableIdentityIfTosAccepted(user: WorkbenchUser, samRequestContext: SamRequestContext): IO[Unit] = {
    val gracePeriodEnabled = tosService.tosConfig.isGracePeriodEnabled
    tosService.getTosStatus(user.id)
      .flatMap {
        // If the user has accepted TOS, the grace period is enabled, or TOS is disabled, then enable the user in LDAP
        // Additionally, if the user is an SA, it's also acceptable to enable them in LDAP
        case Some(true) | None => {
          logger.info(s"ToS requirement will not be bypassed for user ${user.id} / ${user.email}. termsOfService.enabled: ${tosService.tosConfig.enabled}, gracePeriod: ${gracePeriodEnabled}")
          registrationDAO.enableIdentity(user.id, samRequestContext)
        }
        case _ =>
          if(isServiceAccount(user.email.value) || gracePeriodEnabled) {
            logger.info(s"Bypassing ToS requirement for user ${user.id} / ${user.email}. " +
              s"gracePeriod: ${gracePeriodEnabled}, isServiceAccount: ${isServiceAccount(user.email.value)}")
            registrationDAO.enableIdentity(user.id, samRequestContext)
          }
          else {
            logger.info(s"ToS requirement will not be bypassed for user ${user.id} / ${user.email}. termsOfService.enabled: ${tosService.tosConfig.enabled}, gracePeriod: ${gracePeriodEnabled}")
            IO.unit
          }
      }
  }

  def disableUser(userId: WorkbenchUserId, samRequestContext: SamRequestContext): Future[Option[UserStatus]] =
    directoryDAO.loadUser(userId, samRequestContext).unsafeToFuture().flatMap {
      case Some(user) =>
        for {
          _ <- directoryDAO.disableIdentity(user.id, samRequestContext).unsafeToFuture()
          _ <- registrationDAO.disableIdentity(user.id, samRequestContext).unsafeToFuture()
          _ <- cloudExtensions.onUserDisable(user, samRequestContext)
          userStatus <- getUserStatus(user.id, samRequestContext = samRequestContext)
        } yield {
          userStatus
        }
      case None => Future.successful(None)
    }

  def deleteUser(userId: WorkbenchUserId, userInfo: UserInfo, samRequestContext: SamRequestContext): Future[Unit] =
    for {
      allUsersGroup <- cloudExtensions.getOrCreateAllUsersGroup(directoryDAO, samRequestContext)
      _ <- directoryDAO.removeGroupMember(allUsersGroup.id, userId, samRequestContext).unsafeToFuture()
      _ <- cloudExtensions.onUserDelete(userId, samRequestContext)
      _ <- registrationDAO.deleteUser(userId, samRequestContext).unsafeToFuture()
      deleteResult <- directoryDAO.deleteUser(userId, samRequestContext).unsafeToFuture()
    } yield deleteResult
}

object UserService {

  val random = SecureRandom.getInstance("NativePRNGNonBlocking")

  // from https://www.regular-expressions.info/email.html
  val emailRegex: Regex = "(?i)^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$".r

  // Generate a 21 digits unique identifier. First char is fixed 2
  // CurrentMillis.append(randomString)
  private[workbench] def genRandom(currentMilli: Long): String = {
    val currentMillisString = currentMilli.toString
    // one hexdecimal is 4 bits, one byte can generate 2 hexdecial number, so we only need half the number of bytes, which is 8
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

  def validateEmailAddress(email: WorkbenchEmail, blockedEmailDomains: Seq[String]): IO[Unit] = {
    email.value match {
      case emailString if blockedEmailDomains.exists(domain => {
        emailString.endsWith("@" + domain) || emailString.endsWith("." + domain)
      }) => IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, s"email domain not permitted [${email.value}]")))
      case UserService.emailRegex() => IO.unit
      case _ => IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, s"invalid email address [${email.value}]")))
    }
  }
}
