package org.broadinstitute.dsde.workbench.sam
package service

import java.security.SecureRandom

import akka.http.scaladsl.model.StatusCodes
import javax.naming.NameNotFoundException
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.codec.binary.Hex
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.api.CreateWorkbenchUser
import org.broadinstitute.dsde.workbench.sam.directory.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model._

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by dvoet on 7/14/17.
  */
class UserService(val directoryDAO: DirectoryDAO, val cloudExtensions: CloudExtensions)(implicit val executionContext: ExecutionContext) extends LazyLogging {

  def createUser(user: CreateWorkbenchUser): Future[UserStatus] = {
    for {
      allUsersGroup <- cloudExtensions.getOrCreateAllUsersGroup(directoryDAO)
      createdUser <- registerUser(user)
      _ <- directoryDAO.enableIdentity(createdUser.id)
      _ <- directoryDAO.addGroupMember(allUsersGroup.id, createdUser.id)
      _ <- cloudExtensions.onUserCreate(createdUser)
      userStatus <- getUserStatus(createdUser.id)
    } yield {
      userStatus.getOrElse(throw new WorkbenchException("getUserStatus returned None after user was created"))
    }
  }

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
  protected[service] def registerUser(user: CreateWorkbenchUser): Future[WorkbenchUser] = for{
    existingSubFromGoogleSubjectId <- directoryDAO.loadSubjectFromGoogleSubjectId(user.googleSubjectId)
    user <- existingSubFromGoogleSubjectId match{
      case Some(_) => Future.failed[WorkbenchUser](new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"user ${user} already exists")))
      case None => for{
        subjectFromEmail <- directoryDAO.loadSubjectFromEmail(user.email)
        updated <- subjectFromEmail match{
          case Some(uid : WorkbenchUserId) =>
            directoryDAO.setGoogleSubjectId(uid, user.googleSubjectId).map(_ => WorkbenchUser(uid, Some(user.googleSubjectId), user.email))
          case Some(sub) =>
            //We don't support inviting a group account or pet service account
            Future.failed[WorkbenchUser](new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, s"$user is not a regular user. Please use a different endpoint")))
          case None => directoryDAO.createUser(WorkbenchUser(WorkbenchUserId(user.googleSubjectId.value), Some(user.googleSubjectId), user.email)) //For completely new users, we still use googleSubjectId as their userId
        }
      } yield updated
    }
  } yield user

  def getSubjectFromEmail(email: WorkbenchEmail): Future[Option[WorkbenchSubject]] = directoryDAO.loadSubjectFromEmail(email)

  def getUserStatus(userId: WorkbenchUserId, userDetailsOnly: Boolean = false): Future[Option[UserStatus]] = {
    directoryDAO.loadUser(userId).flatMap {
      case Some(user) if !userDetailsOnly =>
        for {
          googleStatus <- cloudExtensions.getUserStatus(user)
          allUsersGroup <- cloudExtensions.getOrCreateAllUsersGroup(directoryDAO)
          allUsersStatus <- directoryDAO.isGroupMember(allUsersGroup.id, user.id) recover { case e: NameNotFoundException => false }
          ldapStatus <- directoryDAO.isEnabled(user.id)
        } yield {
          Option(UserStatus(UserStatusDetails(user.id, user.email), Map("ldap" -> ldapStatus, "allUsersGroup" -> allUsersStatus, "google" -> googleStatus)))
        }

      case Some(user) if userDetailsOnly => Future.successful(Option(UserStatus(UserStatusDetails(user.id, user.email), Map.empty)))

      case None => Future.successful(None)
    }
  }

  def getUserStatusInfo(userId: WorkbenchUserId): Future[Option[UserStatusInfo]] = {
    directoryDAO.loadUser(userId).flatMap {
      case Some(user) => directoryDAO.isEnabled(user.id).flatMap { ldapStatus =>
        Future.successful(Option(UserStatusInfo(user.id.value, user.email.value, ldapStatus))) }
      case None => Future.successful(None)
    }
  }

  def getUserStatusDiagnostics(userId: WorkbenchUserId): Future[Option[UserStatusDiagnostics]] = {
    directoryDAO.loadUser(userId).flatMap {
      case Some(user) => {
        // pulled out of for comprehension to allow concurrent execution
        val ldapStatus = directoryDAO.isEnabled(user.id)
        val allUsersStatus = cloudExtensions.getOrCreateAllUsersGroup(directoryDAO).flatMap { allUsersGroup =>
          directoryDAO.isGroupMember(allUsersGroup.id, user.id) recover { case e: NameNotFoundException => false } }
        val googleStatus = cloudExtensions.getUserStatus(user)

        for {
          ldap <- ldapStatus
          allUsers <- allUsersStatus
          google <- googleStatus
        } yield Option(UserStatusDiagnostics(ldap, allUsers, google))
      }
      case None => Future.successful(None)
    }
  }

  def getUserStatusFromEmail(email: WorkbenchEmail): Future[Option[UserStatus]] = {
    directoryDAO.loadSubjectFromEmail(email).flatMap {
      // don't attempt to handle groups or service accounts - just users
      case Some(user:WorkbenchUserId) => getUserStatus(user)
      case _ => Future.successful(None)
    }
  }

  def enableUser(userId: WorkbenchUserId, userInfo: UserInfo): Future[Option[UserStatus]] = {
    directoryDAO.loadUser(userId).flatMap {
      case Some(user) =>
        for {
          _ <- directoryDAO.enableIdentity(user.id)
          _ <- cloudExtensions.onUserEnable(user)
          userStatus <- getUserStatus(userId)
        } yield userStatus
      case None => Future.successful(None)
    }
  }

  def disableUser(userId: WorkbenchUserId, userInfo: UserInfo): Future[Option[UserStatus]] = {
    directoryDAO.loadUser(userId).flatMap {
      case Some(user) =>
        for {
          _ <- directoryDAO.disableIdentity(user.id)
          _ <- cloudExtensions.onUserDisable(user)
          userStatus <- getUserStatus(user.id)
        } yield userStatus
      case None => Future.successful(None)
    }
  }

  def deleteUser(userId: WorkbenchUserId, userInfo: UserInfo): Future[Unit] = {
    for {
      allUsersGroup <- cloudExtensions.getOrCreateAllUsersGroup(directoryDAO)
      _ <- directoryDAO.removeGroupMember(allUsersGroup.id, userId)
      _ <- cloudExtensions.onUserDelete(userId)
      deleteResult <- directoryDAO.deleteUser(userId)
    } yield deleteResult
  }
}

object UserService{

  val random = SecureRandom.getInstance("NativePRNGNonBlocking")

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
    val front = if(currentMillisString(0) == '1') currentMillisString.replaceFirst("1", "2") else currentMilli
    front + r
  }

  def genWorkbenchUserId(currentMilli: Long): WorkbenchUserId = {
    WorkbenchUserId(genRandom(currentMilli))
  }
}
