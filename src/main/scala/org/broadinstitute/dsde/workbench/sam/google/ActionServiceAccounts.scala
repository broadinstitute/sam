package org.broadinstitute.dsde.workbench.sam.google

import akka.actor.ActorSystem
import cats.effect.IO
import cats.implicits.{catsSyntaxApplicativeId, catsSyntaxTuple2Parallel, toTraverseOps}
import org.broadinstitute.dsde.workbench.google.{GoogleIamDAO, GoogleProjectDAO}
import org.broadinstitute.dsde.workbench.model.google._
import org.broadinstitute.dsde.workbench.sam.config.GoogleServicesConfig
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, LockDetails, PostgresDistributedLockDAO}
import org.broadinstitute.dsde.workbench.sam.model.{FullyQualifiedResourceId, ResourceAction, ResourceId}
import org.broadinstitute.dsde.workbench.sam.model.api.{ActionServiceAccount, ActionServiceAccountId}
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

class ActionServiceAccounts(
    distributedLock: PostgresDistributedLockDAO[IO],
    googleIamDAO: GoogleIamDAO,
    googleProjectDAO: GoogleProjectDAO,
    directoryDAO: DirectoryDAO,
    googleServicesConfig: GoogleServicesConfig
)(implicit override val system: ActorSystem, executionContext: ExecutionContext)
    extends ServiceAccounts(googleProjectDAO, googleServicesConfig) {

  private[google] def createActionServiceAccount(
      resource: FullyQualifiedResourceId,
      project: GoogleProject,
      action: ResourceAction,
      samRequestContext: SamRequestContext
  ): IO[ActionServiceAccount] = {
    // The normal situation is that the pet either exists in both the database and google or neither.
    // Sometimes, especially in tests, the asa may be removed from the database, but not google or the other way around.
    // This code is a little extra complicated to detect the cases when a asa does not exist in google, the database or
    // both and do the right thing.
    val (asaName, asaDisplayName) = actionServiceAccountName(resource, action)
    val createAsa = for {
      (maybeAsa, maybeServiceAccount) <- retrieveAsaAndSa(resource, project, action, samRequestContext)
      serviceAccount <- maybeServiceAccount match {
        // SA does not exist in google, create it and add it to the proxy group
        case None =>
          for {
            _ <- assertProjectInTerraOrg(project)
            sa <- IO.fromFuture(IO(googleIamDAO.createServiceAccount(project, asaName, asaDisplayName)))
          } yield sa
        // SA already exists in google, use it
        case Some(sa) => IO.pure(sa)
      }
      pet <- (maybeAsa, maybeServiceAccount) match {
        // pet does not exist in the database, create it and enable the identity
        case (None, _) =>
          for {
            p <- directoryDAO.createActionServiceAccount(
              ActionServiceAccount(ActionServiceAccountId(resource.resourceId, action, project), serviceAccount),
              samRequestContext
            )
          } yield p
        // pet already exists in the database, but a new SA was created so update the database with new SA info
        case (Some(p), None) =>
          for {
            p <- directoryDAO.updateActionServiceAccount(p.copy(serviceAccount = serviceAccount), samRequestContext)
          } yield p

        // everything already existed
        case (Some(p), Some(_)) => IO.pure(p)
      }
    } yield pet

    val lock = LockDetails(s"${project.value}-createAsa", s"${resource.resourceId}-${action}", 30 seconds)

    for {
      (asa, sa) <- retrieveAsaAndSa(resource, project, action, samRequestContext) // I'm loving better-monadic-for
      shouldLock = !(asa.isDefined && sa.isDefined) // if either is not defined, we need to lock and potentially create them; else we return the pet
      p <- if (shouldLock) distributedLock.withLock(lock).use(_ => createAsa) else asa.get.pure[IO]
    } yield p
  }

  private def actionServiceAccountName(resource: FullyQualifiedResourceId, action: ResourceAction) = {
    /*
     * Service account IDs must be:
     * 1. between 6 and 30 characters
     * 2. lower case alphanumeric separated by hyphens
     * 3. must start with a lower case letter
     *
     *
     * So, the Action name, truncated to 9 chars, prepended to the Resource Id truncated to 20 chars, with "-" is 30 chars.
     */

    val serviceAccountName = s"${action.value.take(9)}-${resource.resourceId.value.take(20)}"
    val displayName = s"ASA [${action.value} on ${resource.resourceTypeName} ${resource.resourceId.value}]"

    // Display names have a max length of 100 characters
    (ServiceAccountName(serviceAccountName), ServiceAccountDisplayName(displayName.take(100)))
  }

  private def retrieveAsaAndSa(
      resource: FullyQualifiedResourceId,
      project: GoogleProject,
      action: ResourceAction,
      samRequestContext: SamRequestContext
  ): IO[(Option[ActionServiceAccount], Option[ServiceAccount])] = {
    val asaName = actionServiceAccountName(resource, action)._1
    val serviceAccount = IO.fromFuture(IO(googleIamDAO.findServiceAccount(project, asaName)))
    val asaId = ActionServiceAccountId(resource.resourceId, action, project)
    val asa = directoryDAO.loadActionServiceAccount(asaId, samRequestContext)
    (asa, serviceAccount).parTupled
  }

  private[google] def removeActionServiceAccount(actionServiceAccount: ActionServiceAccount, samRequestContext: SamRequestContext): IO[Unit] =
    for {
      // remove the record for the pet service account
      _ <- directoryDAO.deleteActionServiceAccount(actionServiceAccount.id, samRequestContext)
      // remove the service account itself in Google
      _ <- IO.fromFuture(IO(googleIamDAO.removeServiceAccount(actionServiceAccount.id.project, toAccountName(actionServiceAccount.serviceAccount.email))))
    } yield ()

  private[google] def forAllActionServiceAccounts[T](resourceId: ResourceId, samRequestContext: SamRequestContext)(
      f: ActionServiceAccount => IO[T]
  ): IO[Seq[T]] =
    for {
      actionServiceAccounts <- directoryDAO.getAllActionServiceAccountsForResource(resourceId, samRequestContext)
      a <- actionServiceAccounts.traverse { asa =>
        f(asa)
      }
    } yield a

}
