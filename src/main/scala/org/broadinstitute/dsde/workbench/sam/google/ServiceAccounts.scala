package org.broadinstitute.dsde.workbench.sam.google

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import com.google.api.client.http.HttpResponseException
import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.rpc.Code
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.google.GoogleProjectDAO
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchException, WorkbenchExceptionWithErrorReport}
import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, GoogleResourceTypes}
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.config.GoogleServicesConfig
import org.broadinstitute.dsde.workbench.util.{FutureSupport, Retry}

import java.io.ByteArrayInputStream
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

class ServiceAccounts(googleProjectDAO: GoogleProjectDAO, googleServicesConfig: GoogleServicesConfig)(implicit
    val system: ActorSystem,
    executionContext: ExecutionContext
) extends ProxyEmailSupport(googleServicesConfig)
    with LazyLogging
    with FutureSupport
    with Retry {
  private[google] def assertProjectInTerraOrg(project: GoogleProject): IO[Unit] = {
    val validOrg = IO
      .fromFuture(IO(googleProjectDAO.getAncestry(project.value).map { ancestry =>
        ancestry.exists { ancestor =>
          ancestor.getResourceId.getType == GoogleResourceTypes.Organization.value && ancestor.getResourceId.getId == googleServicesConfig.terraGoogleOrgNumber
        }
      }))
      .recoverWith {
        // if the getAncestry call results in a 403 error the project can't be in the right org
        case e: HttpResponseException if e.getStatusCode == StatusCodes.Forbidden.intValue =>
          IO.raiseError(
            new WorkbenchExceptionWithErrorReport(
              ErrorReport(StatusCodes.BadRequest, s"Access denied from google accessing project ${project.value}, is it a Terra project?", e)
            )
          )
      }

    validOrg.flatMap {
      case true => IO.unit
      case false =>
        IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, s"Project ${project.value} must be in Terra Organization")))
    }
  }

  private[google] def assertProjectIsActive(project: GoogleProject): IO[Unit] =
    for {
      projectIsActive <- IO.fromFuture(IO(googleProjectDAO.isProjectActive(project.value)))
      _ <- IO.raiseUnless(projectIsActive)(
        new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, s"Project ${project.value} is inactive"))
      )
    } yield ()

  def getAccessTokenUsingJson(saKey: String, desiredScopes: Set[String]): Future[String] = Future {
    val keyStream = new ByteArrayInputStream(saKey.getBytes)
    val credential = ServiceAccountCredentials.fromStream(keyStream).createScoped(desiredScopes.asJava)
    credential.refreshAccessToken.getTokenValue
  }

  private[google] def pollShellProjectCreation(operationId: String): Future[Boolean] = {
    def whenCreating(throwable: Throwable): Boolean =
      throwable match {
        case t: WorkbenchException => throw t
        case t: Exception => true
        case _ => false
      }

    retryExponentially(whenCreating) { () =>
      googleProjectDAO.pollOperation(operationId).map { operation =>
        if (operation.getDone && Option(operation.getError).exists(_.getCode.intValue() == Code.ALREADY_EXISTS.getNumber)) true
        else if (operation.getDone && Option(operation.getError).isEmpty) true
        else if (operation.getDone && Option(operation.getError).isDefined)
          throw new WorkbenchException(s"project creation failed with error ${operation.getError.getMessage}")
        else throw new Exception("project still creating...")
      }
    }
  }

}
