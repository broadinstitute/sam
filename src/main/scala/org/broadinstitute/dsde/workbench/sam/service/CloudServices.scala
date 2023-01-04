package org.broadinstitute.dsde.workbench.sam.service

import akka.actor.ActorSystem
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import cats.effect.IO
import com.google.api.services.admin.directory.model.Group
import org.broadinstitute.dsde.workbench.model.Notifications.Notification
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.broadinstitute.dsde.workbench.sam.api.ExtensionRoutes
import org.broadinstitute.dsde.workbench.sam.model.{BasicWorkbenchGroup, SamUser}
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.broadinstitute.dsde.workbench.util.health.SubsystemStatus
import org.broadinstitute.dsde.workbench.util.health.Subsystems.Subsystem

import scala.concurrent.{ExecutionContext, Future}

object CloudServices {
  val allUsersGroupName = WorkbenchGroupName("All_Users")
}

trait CloudServices {
  // this is temporary until we get the admin group rolled into a sam group
  def isWorkbenchAdmin(memberEmail: WorkbenchEmail): Future[Boolean]

  def isSamSuperAdmin(memberEmail: WorkbenchEmail): Future[Boolean]

  def publishGroup(id: WorkbenchGroupName): Future[Unit]

  def onGroupUpdate(groupIdentities: Seq[WorkbenchGroupIdentity], samRequestContext: SamRequestContext): Future[Unit]

  def onGroupDelete(groupEmail: WorkbenchEmail): Future[Unit]

  def onUserCreate(user: SamUser, samRequestContext: SamRequestContext): Future[Unit]

  def getUserStatus(user: SamUser): Future[Boolean]

  def onUserEnable(user: SamUser, samRequestContext: SamRequestContext): Future[Unit]

  def onUserDisable(user: SamUser, samRequestContext: SamRequestContext): Future[Unit]

  def onUserDelete(userId: WorkbenchUserId, samRequestContext: SamRequestContext): Future[Unit]

  def deleteUserPetServiceAccount(userId: WorkbenchUserId, project: GoogleProject, samRequestContext: SamRequestContext): IO[Boolean]

  def getUserProxy(userEmail: WorkbenchEmail, samRequestContext: SamRequestContext): Future[Option[WorkbenchEmail]]

  def fireAndForgetNotifications[T <: Notification](notifications: Set[T]): Unit

  def checkStatus: Map[Subsystem, Future[SubsystemStatus]]

  def allSubSystems: Set[Subsystem]

  def emailDomain: String

  val allUsersGroupEmail: WorkbenchEmail
  val allUsersGroupStub = BasicWorkbenchGroup(CloudServices.allUsersGroupName, Set.empty, allUsersGroupEmail)

  def getOrCreateAllUsersGroup(samRequestContext: SamRequestContext)
                              (implicit executionContext: ExecutionContext): Future[Group]

  def doesGroupExist(workbenchEmail: WorkbenchEmail,
                     samRequestContext: SamRequestContext)
                    (implicit executionContext: ExecutionContext): Future[Boolean]

  def createGroup(workbenchGroup: WorkbenchGroup,
                  samRequestContext: SamRequestContext)
                 (implicit executionContext: ExecutionContext): Future[Group]
}

trait CloudExtensionsInitializer {
  def onBoot(samApplication: SamApplication)(implicit system: ActorSystem): IO[Unit]
  def cloudExtensions: CloudServices
}

trait NoServicesTrait extends CloudServices {
  override def isWorkbenchAdmin(memberEmail: WorkbenchEmail): Future[Boolean] = Future.successful(true)

  override def isSamSuperAdmin(memberEmail: WorkbenchEmail): Future[Boolean] = Future.successful(true)

  override def publishGroup(id: WorkbenchGroupName): Future[Unit] = Future.successful(())

  override def onGroupUpdate(groupIdentities: Seq[WorkbenchGroupIdentity], samRequestContext: SamRequestContext): Future[Unit] = Future.successful(())

  override def onGroupDelete(groupEmail: WorkbenchEmail): Future[Unit] = Future.successful(())

  override def onUserCreate(user: SamUser, samRequestContext: SamRequestContext): Future[Unit] = Future.successful(())

  override def getUserStatus(user: SamUser): Future[Boolean] = Future.successful(true)

  override def onUserEnable(user: SamUser, samRequestContext: SamRequestContext): Future[Unit] = Future.successful(())

  override def onUserDisable(user: SamUser, samRequestContext: SamRequestContext): Future[Unit] = Future.successful(())

  override def onUserDelete(userId: WorkbenchUserId, samRequestContext: SamRequestContext): Future[Unit] = Future.successful(())

  override def deleteUserPetServiceAccount(userId: WorkbenchUserId, project: GoogleProject, samRequestContext: SamRequestContext): IO[Boolean] = IO.pure(true)

  override def getUserProxy(userEmail: WorkbenchEmail, samRequestContext: SamRequestContext): Future[Option[WorkbenchEmail]] =
    Future.successful(Option(userEmail))

  override def fireAndForgetNotifications[T <: Notification](notifications: Set[T]): Unit = ()

  override def checkStatus: Map[Subsystem, Future[SubsystemStatus]] = Map.empty

  override def allSubSystems: Set[Subsystem] = Set.empty

  override val emailDomain = "example.com"

  override def getOrCreateAllUsersGroup(samRequestContext: SamRequestContext)(implicit
      executionContext: ExecutionContext
  ): Future[Group] = {
    val allUsersGroup = BasicWorkbenchGroup(CloudServices.allUsersGroupName, Set.empty, allUsersGroupEmail)
    createGroup(allUsersGroup, samRequestContext = samRequestContext)
  }

  override val allUsersGroupEmail: WorkbenchEmail = WorkbenchEmail(s"GROUP_${CloudServices.allUsersGroupName.value}@$emailDomain")

  override def doesGroupExist(workbenchEmail: WorkbenchEmail, samRequestContext: SamRequestContext)(implicit executionContext: ExecutionContext): Future[Boolean] = ???

  override def createGroup(workbenchGroup: WorkbenchGroup, samRequestContext: SamRequestContext)(implicit executionContext: ExecutionContext): Future[Group] = ???

}

object NoServices extends NoServicesTrait

object NoExtensionsInitializer extends CloudExtensionsInitializer {
  override def onBoot(samApplication: SamApplication)(implicit system: ActorSystem): IO[Unit] = IO.unit
  override val cloudExtensions: CloudServices = NoServices
}

trait NoExtensionRoutes extends ExtensionRoutes {
  def extensionRoutes(samUser: SamUser, samRequestContext: SamRequestContext): server.Route = reject
  val cloudExtensions = NoServices
}
