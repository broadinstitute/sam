package org.broadinstitute.dsde.workbench.sam.service

import akka.actor.ActorSystem
import org.broadinstitute.dsde.workbench.model.{WorkbenchGroupIdentity, WorkbenchUser, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.api.ExtensionRoutes

import scala.concurrent.Future
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._

trait CloudExtensions {

  def onBoot()(implicit system: ActorSystem): Unit

  def onGroupUpdate(groupIdentities: Seq[WorkbenchGroupIdentity]): Future[Unit]

  def onUserCreate(user: WorkbenchUser): Future[Unit]

  def getUserStatus(user: WorkbenchUser): Future[Boolean]

  def onUserEnable(user: WorkbenchUser): Future[Unit]

  def onUserDisable(user: WorkbenchUser): Future[Unit]

  def onUserDelete(userId: WorkbenchUserId): Future[Unit]

}

object NoExtensions extends CloudExtensions {
  override def onBoot()(implicit system: ActorSystem): Unit = { }

  override def onGroupUpdate(groupIdentities: Seq[WorkbenchGroupIdentity]): Future[Unit] = Future.successful(())

  override def onUserCreate(user: WorkbenchUser): Future[Unit] = Future.successful(())

  override def getUserStatus(user: WorkbenchUser): Future[Boolean] = Future.successful(true)

  override def onUserEnable(user: WorkbenchUser): Future[Unit] = Future.successful(())

  override def onUserDisable(user: WorkbenchUser): Future[Unit] = Future.successful(())

  override def onUserDelete(userId: WorkbenchUserId): Future[Unit] = Future.successful(())
}

trait NoExtensionRoutes extends ExtensionRoutes {
  def extensionRoutes: server.Route = reject
}
