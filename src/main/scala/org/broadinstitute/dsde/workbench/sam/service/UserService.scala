package org.broadinstitute.dsde.workbench.sam
package service

import java.security.SecureRandom

import akka.http.scaladsl.model.StatusCodes
import cats.effect.{ContextShift, IO}
import com.typesafe.scalalogging.LazyLogging
import javax.naming.NameNotFoundException
import org.apache.commons.codec.binary.Hex
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.api.{CreateWorkbenchUser, InviteUser}
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, RegistrationDAO}
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

import scala.concurrent.{ExecutionContext, Future}
import scala.util.matching.Regex

/**
  * Created by dvoet on 7/14/17.
  */
class UserService(val directoryDAO: DirectoryDAO, val cloudExtensions: CloudExtensions, val registrationDAO: RegistrationDAO, blockedEmailDomains: Seq[String])(implicit val executionContext: ExecutionContext, contextShift: ContextShift[IO]) extends LazyLogging {

  def createUser(user: CreateWorkbenchUser, samRequestContext: SamRequestContext): Future[UserStatus] = {
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
    * If googleSubjectId exists in ldap, return 409; else if email also exists, we lookup pre-created user record and update
    * its googleSubjectId field; otherwise, we create a new user
    *
    * GoogleSubjectId    Email
    *      no             no      ---> We've never seen this user before, create a new user
    *      no             yes      ---> Someone invited this user previous and we have a record for this user already. We just need to update GoogleSubjetId field for this user.
    *      yes            skip    ---> User exists. Do nothing.
    *      yes            skip    ---> User exists. Do nothing.
    */
  protected[service] def registerUser(user: CreateWorkbenchUser, samRequestContext: SamRequestContext): IO[WorkbenchUser] =
    for {
      existingSubFromGoogleSubjectId <- directoryDAO.loadSubjectFromGoogleSubjectId(user.googleSubjectId, samRequestContext)
      user <- existingSubFromGoogleSubjectId match {
        case Some(_) => IO.raiseError[WorkbenchUser](new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"user ${user} already exists")))
        case None =>
          for {
            subjectFromEmail <- directoryDAO.loadSubjectFromEmail(user.email, samRequestContext)
            updated <- subjectFromEmail match {
              case Some(uid: WorkbenchUserId) =>
                for {
                  groups <- directoryDAO.listUserDirectMemberships(uid, samRequestContext)
                  _ <- directoryDAO.setGoogleSubjectId(uid, user.googleSubjectId, samRequestContext)
                  _ <- registrationDAO.setGoogleSubjectId(uid, user.googleSubjectId, samRequestContext)
                  _ <- IO.fromFuture(IO(cloudExtensions.onGroupUpdate(groups, samRequestContext)))
                } yield WorkbenchUser(uid, Some(user.googleSubjectId), user.email, user.identityConcentratorId)

              case Some(_) =>
                //We don't support inviting a group account or pet service account
                IO.raiseError[WorkbenchUser](
                  new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, s"$user is not a regular user. Please use a different endpoint")))
              case None =>
                createUserInternal(WorkbenchUser(WorkbenchUserId(user.googleSubjectId.value), Some(user.googleSubjectId), user.email, user.identityConcentratorId), samRequestContext) //For completely new users, we still use googleSubjectId as their userId

            }
          } yield updated
      }
    } yield user

  private def createUserInternal(user: WorkbenchUser, samRequestContext: SamRequestContext) = {
    for {
      createdUser <- directoryDAO.createUser(user, samRequestContext)
      _ <- registrationDAO.createUser(user, samRequestContext)
      _ <- IO.fromFuture(IO(cloudExtensions.onUserCreate(createdUser, samRequestContext)))
    } yield createdUser
  }
  def getSubjectFromEmail(email: WorkbenchEmail, samRequestContext: SamRequestContext): Future[Option[WorkbenchSubject]] = directoryDAO.loadSubjectFromEmail(email, samRequestContext).unsafeToFuture()

  def getUserStatus(userId: WorkbenchUserId, userDetailsOnly: Boolean = false, samRequestContext: SamRequestContext): Future[Option[UserStatus]] =
    directoryDAO.loadUser(userId, samRequestContext).unsafeToFuture().flatMap {
      case Some(user) =>
        if(userDetailsOnly)
          Future.successful(Option(UserStatus(UserStatusDetails(user.id, user.email), Map.empty)))
        else
          for {
            googleStatus <- cloudExtensions.getUserStatus(user)
            allUsersGroup <- cloudExtensions.getOrCreateAllUsersGroup(directoryDAO, samRequestContext)
            allUsersStatus <- directoryDAO.isGroupMember(allUsersGroup.id, user.id, samRequestContext).unsafeToFuture() recover { case _: NameNotFoundException => false }
            ldapStatus <- registrationDAO.isEnabled(user.id, samRequestContext).unsafeToFuture()
          } yield {
            Option(UserStatus(UserStatusDetails(user.id, user.email), Map("ldap" -> ldapStatus, "allUsersGroup" -> allUsersStatus, "google" -> googleStatus)))
          }
      case None => Future.successful(None)
    }

  def getUserStatusInfo(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Option[UserStatusInfo]] =
    directoryDAO.loadUser(userId, samRequestContext).flatMap {
      case Some(user) =>
        registrationDAO.isEnabled(user.id, samRequestContext).flatMap { ldapStatus =>
          IO.pure(Option(UserStatusInfo(user.id.value, user.email.value, ldapStatus)))
        }
      case None => IO.pure(None)
    }

  def getUserStatusDiagnostics(userId: WorkbenchUserId, samRequestContext: SamRequestContext): Future[Option[UserStatusDiagnostics]] =
    directoryDAO.loadUser(userId, samRequestContext).unsafeToFuture().flatMap {
      case Some(user) => {
        // pulled out of for comprehension to allow concurrent execution
        val ldapStatus = registrationDAO.isEnabled(user.id, samRequestContext).unsafeToFuture()
        val allUsersStatus = cloudExtensions.getOrCreateAllUsersGroup(directoryDAO, samRequestContext).flatMap { allUsersGroup =>
          directoryDAO.isGroupMember(allUsersGroup.id, user.id, samRequestContext).unsafeToFuture() recover { case e: NameNotFoundException => false }
        }
        val googleStatus = cloudExtensions.getUserStatus(user)

        for {
          ldap <- ldapStatus
          allUsers <- allUsersStatus
          google <- googleStatus
        } yield Option(UserStatusDiagnostics(ldap, allUsers, google))
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

  def enableUser(userId: WorkbenchUserId, userInfo: UserInfo, samRequestContext: SamRequestContext): Future[Option[UserStatus]] =
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
      _ <- registrationDAO.enableIdentity(user.id, samRequestContext).unsafeToFuture()
      _ <- cloudExtensions.onUserEnable(user, samRequestContext)
    } yield ()
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
