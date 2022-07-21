package org.broadinstitute.dsde.workbench.sam
package service

import scala.jdk.CollectionConverters._
import java.security.SecureRandom
import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import net.logstash.logback.argument.StructuredArguments

import javax.naming.NameNotFoundException
import org.apache.commons.codec.binary.Hex
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, RegistrationDAO}
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service.UserService.genWorkbenchUserId
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

import scala.concurrent.{ExecutionContext, Future}
import scala.util.matching.Regex

/**
  * Created by dvoet on 7/14/17.
  */
class UserService(val directoryDAO: DirectoryDAO, val cloudExtensions: CloudExtensions, val registrationDAO: RegistrationDAO, blockedEmailDomains: Seq[String], tosService: TosService)(implicit val executionContext: ExecutionContext) extends LazyLogging {

  def createUser(user: SamUser, samRequestContext: SamRequestContext): Future[UserStatus] = {
    for {
      _ <- UserService.validateEmailAddress(user.email, blockedEmailDomains).unsafeToFuture()
      createdUser <- registerUser(user, samRequestContext).unsafeToFuture()
      _ <- enableUserInternal(createdUser, samRequestContext)
      _ <- addToAllUsersGroup(createdUser.id, samRequestContext)
      userStatus <- getUserStatus(createdUser.id, samRequestContext = samRequestContext)
      res <- userStatus.toRight(new WorkbenchException("getUserStatus returned None after user was created")).fold(Future.failed, Future.successful)
    } yield res
  }

  def addToAllUsersGroup(uid: WorkbenchUserId, samRequestContext: SamRequestContext): Future[Unit] = {
    for {
      allUsersGroup <- cloudExtensions.getOrCreateAllUsersGroup(directoryDAO, samRequestContext)
      _ <- directoryDAO.addGroupMember(allUsersGroup.id, uid, samRequestContext).unsafeToFuture()
    } yield ()
  }

  def inviteUser(inviteeEmail: WorkbenchEmail, samRequestContext: SamRequestContext): IO[UserStatusDetails] =
    for {
      _ <- UserService.validateEmailAddress(inviteeEmail, blockedEmailDomains)
      existingSubject <- directoryDAO.loadSubjectFromEmail(inviteeEmail, samRequestContext)
      createdUser <- existingSubject match {
        case None => createUserInternal(SamUser(genWorkbenchUserId(System.currentTimeMillis()), None, inviteeEmail, None, false, None), samRequestContext)
        case Some(_) =>
          IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"email ${inviteeEmail} already exists")))
      }
    } yield UserStatusDetails(createdUser.id, createdUser.email)

  /**
    * First lookup user by either googleSubjectId or azureB2DId, whichever is populated. If the user exists
    * throw a conflict error. If the user does not exist look them up by email. If the user email exists
    * then this is an invited user, update their googleSubjectId and/or azureB2CId and return the updated user record.
    * If the email does not exist, this is a new user, create them.
    * It is critical that this method returns the updated/created SamUser record FROM THE DATABASE and not the SamUser
    * passed in as the first parameter.
    */
  protected[service] def registerUser(user: SamUser, samRequestContext: SamRequestContext): IO[SamUser] =
    for {
      _ <- validateNewWorkbenchUser(user, samRequestContext)
      subjectWithEmail <- directoryDAO.loadSubjectFromEmail(user.email, samRequestContext)
      updated <- subjectWithEmail match {
        case Some(uid: WorkbenchUserId) =>
          acceptInvitedUser(user, samRequestContext, uid)

        case None =>
          createUserInternal(user, samRequestContext)

        case Some(_) =>
          //We don't support inviting a group account or pet service account
          IO.raiseError[SamUser](
            new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, s"$user is not a regular user. Please use a different endpoint")))

      }
    } yield updated

  private def acceptInvitedUser(user: SamUser, samRequestContext: SamRequestContext, uid: WorkbenchUserId): IO[SamUser] = {
    for {
      groups <- directoryDAO.listUserDirectMemberships(uid, samRequestContext)
      _ <- user.googleSubjectId.traverse { googleSubjectId =>
        for {
          _ <- directoryDAO.setGoogleSubjectId(uid, googleSubjectId, samRequestContext)
          // Note: we need to store the user's Google Subject Id in LDAP
          _ <- registrationDAO.setGoogleSubjectId(uid, googleSubjectId, samRequestContext)
        } yield ()
      }
      _ <- user.azureB2CId.traverse { azureB2CId =>
        directoryDAO.setUserAzureB2CId(uid, azureB2CId, samRequestContext)
        // Note: we do not store the user's AzureB2CId in LDAP, only in the database
      }
      _ <- IO.fromFuture(IO(cloudExtensions.onGroupUpdate(groups, samRequestContext)))
      updatedUser <- directoryDAO.loadUser(uid, samRequestContext)
    } yield updatedUser.getOrElse(throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.InternalServerError, s"$user could not be accepted from invite because user could not be loaded from the db")))
  }

  private def validateNewWorkbenchUser(newWorkbenchUser: SamUser, samRequestContext: SamRequestContext): IO[Unit] = {
    for {
      existingUser <- newWorkbenchUser match {
        case SamUser(_, Some(googleSubjectId), _, _, _, _) => directoryDAO.loadSubjectFromGoogleSubjectId(googleSubjectId, samRequestContext)
        case SamUser(_, _, _, Some(azureB2CId), _, _) => directoryDAO.loadUserByAzureB2CId(azureB2CId, samRequestContext).map(_.map(_.id))
        case _ => IO.raiseError(new WorkbenchException("cannot create user when neither google subject id nor azure b2c id exists"))
      }

      _ <- existingUser match {
        case Some(_) => IO.raiseError[SamUser](new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"user ${newWorkbenchUser.email} already exists")))
        case None => IO.unit
      }
    } yield ()
  }

  private def createUserInternal(user: SamUser, samRequestContext: SamRequestContext): IO[SamUser] = {
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
            tosAcceptedStatus <- tosService.getTosStatus(user.id, samRequestContext).unsafeToFuture()
            ldapStatus <- directoryDAO.isEnabled(user.id, samRequestContext).unsafeToFuture() // calling postgres instead of opendj here as a temporary measure as we work toward eliminating opendj
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
    for {
      _ <- tosService.acceptTosStatus(userId, samRequestContext)
      status <- IO.fromFuture(IO(getUserStatus(userId, false, samRequestContext)))
    } yield status
  }

  def rejectTermsOfService(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Option[UserStatus]] = {
    for {
      _ <- tosService.rejectTosStatus(userId, samRequestContext)
      status <- IO.fromFuture(IO(getUserStatus(userId, false, samRequestContext)))
    } yield status
  }

  def getUserStatusInfo(user: SamUser, samRequestContext: SamRequestContext): IO[UserStatusInfo] = {
    val tosStatus = tosService.isTermsOfServiceStatusAcceptable(user)
    IO.pure(UserStatusInfo(user.id.value, user.email.value, tosStatus && user.enabled, user.enabled))
  }

  def getUserStatusDiagnostics(userId: WorkbenchUserId, samRequestContext: SamRequestContext): Future[Option[UserStatusDiagnostics]] =
    directoryDAO.loadUser(userId, samRequestContext).unsafeToFuture().flatMap {
      case Some(user) => {
        // pulled out of for comprehension to allow concurrent execution
        val ldapStatus = directoryDAO.isEnabled(user.id, samRequestContext).unsafeToFuture() // calling postgres instead of opendj here as a temporary measure as we work toward eliminating opendj
        val tosAcceptedStatus = tosService.getTosStatus(user.id, samRequestContext).unsafeToFuture()
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
        } yield {
          samRequestContext.parentSpan.foreach(span =>
            logger.error(s"test log user ${user.email}", StructuredArguments.entries(Map("logging.googleapis.com/trace" -> s"projects/broad-dsde-qa/traces/${span.getContext.getTraceId.toLowerBase16}").asJava))
          )

          Option(UserStatusDiagnostics(ldap, allUsers, google, tosAccepted, adminEnabled))
        }
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

  private def enableUserInternal(user: SamUser, samRequestContext: SamRequestContext): Future[Unit] = {
    for {
      _ <- directoryDAO.enableIdentity(user.id, samRequestContext).unsafeToFuture()
      _ <- registrationDAO.enableIdentity(user.id, samRequestContext).unsafeToFuture()
      _ <- cloudExtensions.onUserEnable(user, samRequestContext)
    } yield ()
  }

  val serviceAccountDomain = "\\S+@\\S+\\.gserviceaccount\\.com".r

  private def isServiceAccount(email: String) = {
    serviceAccountDomain.pattern.matcher(email).matches
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

  def deleteUser(userId: WorkbenchUserId, samRequestContext: SamRequestContext): Future[Unit] =
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
