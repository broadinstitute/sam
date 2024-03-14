package org.broadinstitute.dsde.workbench.sam.google

import org.broadinstitute.dsde.workbench.sam._

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchEmail, WorkbenchExceptionWithErrorReport, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.config.GoogleServicesConfig

abstract class ProxyEmailSupport(googleServicesConfig: GoogleServicesConfig) {
  private[google] def getUserProxy(userId: WorkbenchUserId): IO[Option[WorkbenchEmail]] =
    IO.pure(Some(toProxyFromUser(userId)))

  private[google] def withProxyEmail[T](userId: WorkbenchUserId)(f: WorkbenchEmail => IO[T]): IO[T] =
    getUserProxy(userId) flatMap {
      case Some(e) => f(e)
      case None =>
        throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.InternalServerError, s"Proxy group does not exist for subject ID: $userId"))
    }

  private[google] def toProxyFromUser(userId: WorkbenchUserId): WorkbenchEmail =
    WorkbenchEmail(s"${googleServicesConfig.resourceNamePrefix.getOrElse("")}PROXY_${userId.value}@${googleServicesConfig.appsDomain}")

}
