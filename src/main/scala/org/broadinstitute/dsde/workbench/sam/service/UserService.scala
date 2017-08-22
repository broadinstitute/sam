package org.broadinstitute.dsde.workbench.sam.service

import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.google.GoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.directory.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.{SamGroupName, SamUser, SamUserStatus}

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by dvoet on 7/14/17.
  */
class UserService(val directoryDAO: DirectoryDAO, val googleDirectoryDAO: GoogleDirectoryDAO)(implicit val executionContext: ExecutionContext) extends LazyLogging {

  private val allUsersGroupName = SamGroupName("All_Users")

  def createUser(user: SamUser): Future[SamUser] = {
    for {
      _ <- directoryDAO.createUser(user)
      _ <- googleDirectoryDAO.createGroup(user.email.value, toProxyFromUser(user.id.value))
      _ <- googleDirectoryDAO.addUserToGroup(toProxyFromUser(user.id.value), user.email.value)
      _ <- directoryDAO.addGroupMember(allUsersGroupName, user.id)
    } yield user
  }

  def getUserStatus(user: SamUser): Future[Option[SamUserStatus]] = {
    for {
      loadedUser <- directoryDAO.loadUser(user.id)
      googleStatus <- googleDirectoryDAO.isGroupMember(toProxyFromUser(user.id.value), user.email.value)
      allUsersStatus <- directoryDAO.isGroupMember(allUsersGroupName, user.id)
    } yield {
      loadedUser.map { user =>
        Option(SamUserStatus(user, Map("google" -> googleStatus, "allUsersGroup" -> allUsersStatus))) //TODO: rawls also returns status in ldap, do this after GAWB-????
      }.getOrElse(None)
    }
  }

  private def toProxyFromUser(subjectId: String): String = s"PROXY_$subjectId@dev.test.firecloud.org" //TODO (email address)

}
