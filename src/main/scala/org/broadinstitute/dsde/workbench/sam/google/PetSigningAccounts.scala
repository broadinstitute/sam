package org.broadinstitute.dsde.workbench.sam.google

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import cats.effect.unsafe.implicits.global
import cats.effect.IO
import cats.implicits.{catsSyntaxApplicativeId, catsSyntaxTuple2Parallel}
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import org.broadinstitute.dsde.workbench.google.{GoogleDirectoryDAO, GoogleIamDAO, GoogleProjectDAO}
import org.broadinstitute.dsde.workbench.model.google._
import org.broadinstitute.dsde.workbench.model.{PetServiceAccount, PetServiceAccountId, WorkbenchEmail, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.config.GoogleServicesConfig
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, LockDetails, PostgresDistributedLockDAO}
import org.broadinstitute.dsde.workbench.sam.model.api.SamUser
import org.broadinstitute.dsde.workbench.sam.service.CloudExtensions
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class PetSigningAccounts(
    distributedLock: PostgresDistributedLockDAO[IO],
    googleIamDAO: GoogleIamDAO,
    googleDirectoryDAO: GoogleDirectoryDAO,
    googleProjectDAO: GoogleProjectDAO,
    googleKeyCache: GoogleKeyCache,
    directoryDAO: DirectoryDAO,
    googleServicesConfig: GoogleServicesConfig
)(implicit override val system: ActorSystem, executionContext: ExecutionContext)
    extends ServiceAccounts(googleProjectDAO, googleServicesConfig) {

  private val emailDomain = googleServicesConfig.appsDomain

  private[google] val allPetSigningAccountsGroupEmail = WorkbenchEmail(
    s"${googleServicesConfig.resourceNamePrefix.getOrElse("")}GROUP_${CloudExtensions.allPetSingingAccountsGroupName.value}@$emailDomain"
  )
  private[google] def createPetSigningAccount(
      user: SamUser,
      petSaName: ServiceAccountName,
      petSaDisplayName: ServiceAccountDisplayName,
      project: GoogleProject,
      samRequestContext: SamRequestContext
  ): IO[PetServiceAccount] = {
    // The normal situation is that the pet either exists in both the database and google or neither.
    // Sometimes, especially in tests, the pet may be removed from the database, but not google or the other way around.
    // This code is a little extra complicated to detect the cases when a pet does not exist in google, the database or
    // both and do the right thing.
    val createPet = for {
      (maybePet, maybeServiceAccount) <- retrievePetSigningAccountAndSA(user.id, petSaName, project, samRequestContext)
      serviceAccount <- maybeServiceAccount match {
        // SA does not exist in google, create it and add it to the proxy group
        case None =>
          for {
            _ <- assertProjectInTerraOrg(project)
            sa <- IO.fromFuture(IO(googleIamDAO.createServiceAccount(project, petSaName, petSaDisplayName)))
            _ <- IO.fromFuture(IO(googleDirectoryDAO.addServiceAccountToGroup(allPetSigningAccountsGroupEmail, sa)))
            _ <- IO.fromFuture(IO(googleIamDAO.addServiceAccountUserRoleForUser(project, sa.email, sa.email)))
          } yield sa
        // SA already exists in google, use it
        case Some(sa) => IO.pure(sa)
      }
      pet <- (maybePet, maybeServiceAccount) match {
        // pet does not exist in the database, create it and enable the identity
        case (None, _) =>
          for {
            p <- directoryDAO.createPetSigningAccount(PetServiceAccount(PetServiceAccountId(user.id, project), serviceAccount), samRequestContext)
          } yield p
        // pet already exists in the database, but a new SA was created so update the database with new SA info
        case (Some(p), None) =>
          for {
            p <- directoryDAO.updatePetSigningAccount(p.copy(serviceAccount = serviceAccount), samRequestContext)
          } yield p

        // everything already existed
        case (Some(p), Some(_)) => IO.pure(p)
      }
    } yield pet

    val lock = LockDetails(s"${project.value}-createPetSigning", user.id.value, 30 seconds)

    for {
      (pet, sa) <- retrievePetSigningAccountAndSA(user.id, petSaName, project, samRequestContext) // I'm loving better-monadic-for
      shouldLock = !(pet.isDefined && sa.isDefined) // if either is not defined, we need to lock and potentially create them; else we return the pet
      p <- if (shouldLock) distributedLock.withLock(lock).use(_ => createPet) else pet.get.pure[IO]
    } yield p
  }

  private[google] def retrievePetSigningAccountAndSA(
      userId: WorkbenchUserId,
      petServiceAccountName: ServiceAccountName,
      project: GoogleProject,
      samRequestContext: SamRequestContext
  ): IO[(Option[PetServiceAccount], Option[ServiceAccount])] = {
    val serviceAccount = IO.fromFuture(IO(googleIamDAO.findServiceAccount(project, petServiceAccountName)))
    val pet = directoryDAO.loadPetSigningAccount(PetServiceAccountId(userId, project), samRequestContext)
    (pet, serviceAccount).parTupled
  }

  private[google] def getUserPetSigningAccount(user: SamUser, samRequestContext: SamRequestContext): IO[Option[String]] = {
    val projectName =
      s"fc-${googleServicesConfig.environment.substring(0, Math.min(googleServicesConfig.environment.length(), 5))}-${user.id.value}" // max 30 characters. subject ID is 21
    val (petSaName, petSaDisplayName) = toPetSigningAccountFromUser(user)

    val keyFuture = for {
      creationOperationId <- googleProjectDAO
        .createProject(projectName, googleServicesConfig.terraGoogleOrgNumber, GoogleResourceTypes.Organization)
        .map(opId => Option(opId)) recover {
        case gjre: GoogleJsonResponseException if gjre.getDetails.getCode == StatusCodes.Conflict.intValue => None
      }
      _ <- creationOperationId match {
        case Some(opId) => pollShellProjectCreation(opId) // poll until it's created
        case None => Future.successful(())
      }
      serviceAccount <- createPetSigningAccount(user, petSaName, petSaDisplayName, GoogleProject(projectName), samRequestContext).unsafeToFuture()
      key <- googleKeyCache.getKey(serviceAccount).unsafeToFuture()
    } yield Some(key)
    IO.fromFuture(IO(keyFuture))
  }

  private[google] def toPetSigningAccountFromUser(user: SamUser): (ServiceAccountName, ServiceAccountDisplayName) = {
    /*
     * Service account IDs must be:
     * 1. between 6 and 30 characters
     * 2. lower case alphanumeric separated by hyphens
     * 3. must start with a lower case letter
     *
     * Subject IDs are 22 numeric characters, so "sign-${subjectId}" fulfills these requirements.
     */
    val serviceAccountName = s"sign-${user.id.value}"
    val displayName = s"Pet Signing Account [${user.email.value}]"

    // Display names have a max length of 100 characters
    (ServiceAccountName(serviceAccountName), ServiceAccountDisplayName(displayName.take(100)))
  }

  private[google] def removePetSigningAccount(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Unit] =
    for {
      petSigningAccount <- directoryDAO.loadUserPetSigningAccount(userId, samRequestContext)
      _ <- petSigningAccount.map(account => directoryDAO.deletePetSigningAccount(account.id, samRequestContext)).getOrElse(IO.unit)
      _ <- IO.fromFuture(
        IO(
          petSigningAccount
            .map(account => googleIamDAO.removeServiceAccount(account.id.project, toAccountName(account.serviceAccount.email)))
            .getOrElse(Future.successful(()))
        )
      )
    } yield ()

}
