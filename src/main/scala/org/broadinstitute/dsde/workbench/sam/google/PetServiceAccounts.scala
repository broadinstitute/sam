package org.broadinstitute.dsde.workbench.sam.google

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import cats.effect.unsafe.implicits.global
import cats.effect.IO
import cats.implicits.{catsSyntaxApplicativeId, catsSyntaxTuple2Parallel, toTraverseOps}
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import org.broadinstitute.dsde.workbench.google.{GoogleDirectoryDAO, GoogleIamDAO, GoogleProjectDAO}
import org.broadinstitute.dsde.workbench.model.{PetServiceAccount, PetServiceAccountId, WorkbenchEmail, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.model.google._
import org.broadinstitute.dsde.workbench.sam.config.GoogleServicesConfig
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, LockDetails, PostgresDistributedLockDAO}
import org.broadinstitute.dsde.workbench.sam.model.api.SamUser
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

class PetServiceAccounts(
    distributedLock: PostgresDistributedLockDAO[IO],
    googleIamDAO: GoogleIamDAO,
    googleDirectoryDAO: GoogleDirectoryDAO,
    googleProjectDAO: GoogleProjectDAO,
    googleKeyCache: GoogleKeyCache,
    directoryDAO: DirectoryDAO,
    googleServicesConfig: GoogleServicesConfig
)(implicit override val system: ActorSystem, executionContext: ExecutionContext)
    extends ServiceAccounts(googleProjectDAO, googleServicesConfig) {

  private[google] def toPetSAFromUser(user: SamUser): (ServiceAccountName, ServiceAccountDisplayName) = {
    /*
     * Service account IDs must be:
     * 1. between 6 and 30 characters
     * 2. lower case alphanumeric separated by hyphens
     * 3. must start with a lower case letter
     *
     * Subject IDs are 22 numeric characters, so "pet-${subjectId}" fulfills these requirements.
     */
    val serviceAccountName = s"${googleServicesConfig.resourceNamePrefix.getOrElse("")}pet-${user.id.value}"
    val displayName = s"Pet Service Account for user [${user.email.value}]"

    // Display names have a max length of 100 characters
    (ServiceAccountName(serviceAccountName), ServiceAccountDisplayName(displayName.take(100)))
  }

  private def retrievePetAndSA(
      userId: WorkbenchUserId,
      petServiceAccountName: ServiceAccountName,
      project: GoogleProject,
      samRequestContext: SamRequestContext
  ): IO[(Option[PetServiceAccount], Option[ServiceAccount])] = {
    val serviceAccount = IO.fromFuture(IO(googleIamDAO.findServiceAccount(project, petServiceAccountName)))
    val pet = directoryDAO.loadPetServiceAccount(PetServiceAccountId(userId, project), samRequestContext)
    (pet, serviceAccount).parTupled
  }
  def createUserPetServiceAccount(user: SamUser, project: GoogleProject, samRequestContext: SamRequestContext): IO[PetServiceAccount] = {
    val (petSaName, petSaDisplayName) = toPetSAFromUser(user)
    // The normal situation is that the pet either exists in both the database and google or neither.
    // Sometimes, especially in tests, the pet may be removed from the database, but not google or the other way around.
    // This code is a little extra complicated to detect the cases when a pet does not exist in google, the database or
    // both and do the right thing.
    val createPet = for {
      (maybePet, maybeServiceAccount) <- retrievePetAndSA(user.id, petSaName, project, samRequestContext)
      serviceAccount <- maybeServiceAccount match {
        // SA does not exist in google, create it and add it to the proxy group
        case None =>
          for {
            _ <- assertProjectInTerraOrg(project)
            _ <- assertProjectIsActive(project)
            sa <- IO.fromFuture(IO(googleIamDAO.createServiceAccount(project, petSaName, petSaDisplayName)))
            _ <- withProxyEmail(user.id) { proxyEmail =>
              // Add group member by uniqueId instead of email to avoid race condition
              // See: https://broadworkbench.atlassian.net/browse/CA-1005
              IO.fromFuture(IO(googleDirectoryDAO.addServiceAccountToGroup(proxyEmail, sa)))
            }
            _ <- IO.fromFuture(IO(googleIamDAO.addServiceAccountUserRoleForUser(project, sa.email, sa.email)))
          } yield sa
        // SA already exists in google, use it
        case Some(sa) => IO.pure(sa)
      }
      pet <- (maybePet, maybeServiceAccount) match {
        // pet does not exist in the database, create it and enable the identity
        case (None, _) =>
          for {
            p <- directoryDAO.createPetServiceAccount(PetServiceAccount(PetServiceAccountId(user.id, project), serviceAccount), samRequestContext)
            _ <- directoryDAO.enableIdentity(p.id, samRequestContext)
          } yield p
        // pet already exists in the database, but a new SA was created so update the database with new SA info
        case (Some(p), None) =>
          for {
            p <- directoryDAO.updatePetServiceAccount(p.copy(serviceAccount = serviceAccount), samRequestContext)
          } yield p

        // everything already existed
        case (Some(p), Some(_)) => IO.pure(p)
      }
    } yield pet

    val lock = LockDetails(s"${project.value}-createPet", user.id.value, 30 seconds)

    for {
      (pet, sa) <- retrievePetAndSA(user.id, petSaName, project, samRequestContext) // I'm loving better-monadic-for
      shouldLock = !(pet.isDefined && sa.isDefined) // if either is not defined, we need to lock and potentially create them; else we return the pet
      p <- if (shouldLock) distributedLock.withLock(lock).use(_ => createPet) else pet.get.pure[IO]
    } yield p
  }

  def getPetServiceAccountKey(userEmail: WorkbenchEmail, project: GoogleProject, samRequestContext: SamRequestContext): IO[Option[String]] =
    for {
      subject <- directoryDAO.loadSubjectFromEmail(userEmail, samRequestContext)
      key <- subject match {
        case Some(userId: WorkbenchUserId) =>
          getPetServiceAccountKey(SamUser(userId, None, userEmail, None, false), project, samRequestContext).map(Option(_))
        case _ => IO.pure(None)
      }
    } yield key

  def getPetServiceAccountKey(user: SamUser, project: GoogleProject, samRequestContext: SamRequestContext): IO[String] =
    for {
      pet <- createUserPetServiceAccount(user, project, samRequestContext)
      key <- googleKeyCache.getKey(pet)
    } yield key

  def deleteUserPetServiceAccount(userId: WorkbenchUserId, project: GoogleProject, samRequestContext: SamRequestContext): IO[Boolean] =
    for {
      maybePet <- directoryDAO.loadPetServiceAccount(PetServiceAccountId(userId, project), samRequestContext)
      deletedSomething <- maybePet match {
        case Some(pet) => removePetServiceAccount(pet, samRequestContext).map(_ => true)
        case None => IO.pure(false) // didn't find the pet, nothing to delete
      }
    } yield deletedSomething

  private[google] def removePetServiceAccount(petServiceAccount: PetServiceAccount, samRequestContext: SamRequestContext): IO[Unit] =
    for {
      // disable the pet service account
      _ <- disablePetServiceAccount(petServiceAccount, samRequestContext)
      // remove the record for the pet service account
      _ <- directoryDAO.deletePetServiceAccount(petServiceAccount.id, samRequestContext)
      // remove the service account itself in Google
      _ <- IO.fromFuture(IO(googleIamDAO.removeServiceAccount(petServiceAccount.id.project, toAccountName(petServiceAccount.serviceAccount.email))))
    } yield ()

  private[google] def disablePetServiceAccount(petServiceAccount: PetServiceAccount, samRequestContext: SamRequestContext): IO[Unit] =
    for {
      _ <- directoryDAO.disableIdentity(petServiceAccount.id, samRequestContext)
      _ <- withProxyEmail(petServiceAccount.id.userId) { proxyEmail =>
        IO.fromFuture(IO(googleDirectoryDAO.removeMemberFromGroup(proxyEmail, petServiceAccount.serviceAccount.email)))
      }
    } yield ()

  def getPetServiceAccountToken(user: SamUser, project: GoogleProject, scopes: Set[String], samRequestContext: SamRequestContext): Future[String] =
    getPetServiceAccountKey(user, project, samRequestContext).unsafeToFuture().flatMap { key =>
      getAccessTokenUsingJson(key, scopes)
    }

  def getArbitraryPetServiceAccountKey(userEmail: WorkbenchEmail, samRequestContext: SamRequestContext): IO[Option[String]] =
    for {
      subject <- directoryDAO.loadSubjectFromEmail(userEmail, samRequestContext)
      key <- subject match {
        case Some(userId: WorkbenchUserId) =>
          IO.fromFuture(IO(getArbitraryPetServiceAccountKey(SamUser(userId, None, userEmail, None, false), samRequestContext))).map(Option(_))
        case _ => IO.none
      }
    } yield key

  def getArbitraryPetServiceAccountKey(user: SamUser, samRequestContext: SamRequestContext): Future[String] =
    getDefaultServiceAccountForShellProject(user, samRequestContext)

  def getArbitraryPetServiceAccountToken(user: SamUser, scopes: Set[String], samRequestContext: SamRequestContext): Future[String] =
    getArbitraryPetServiceAccountKey(user, samRequestContext).flatMap { key =>
      getAccessTokenUsingJson(key, scopes)
    }

  private def getDefaultServiceAccountForShellProject(user: SamUser, samRequestContext: SamRequestContext): Future[String] = {
    val projectName =
      s"fc-${googleServicesConfig.environment.substring(0, Math.min(googleServicesConfig.environment.length(), 5))}-${user.id.value}" // max 30 characters. subject ID is 21
    for {
      creationOperationId <- googleProjectDAO
        .createProject(projectName, googleServicesConfig.terraGoogleOrgNumber, GoogleResourceTypes.Organization)
        .map(opId => Option(opId)) recover {
        case gjre: GoogleJsonResponseException if gjre.getDetails.getCode == StatusCodes.Conflict.intValue => None
      }
      _ <- creationOperationId match {
        case Some(opId) => pollShellProjectCreation(opId) // poll until it's created
        case None => Future.successful(())
      }
      key <- getPetServiceAccountKey(user, GoogleProject(projectName), samRequestContext).unsafeToFuture()
    } yield key
  }

  private[google] def enablePetServiceAccount(petServiceAccount: PetServiceAccount, samRequestContext: SamRequestContext): IO[Unit] =
    for {
      _ <- directoryDAO.enableIdentity(petServiceAccount.id, samRequestContext)
      _ <- withProxyEmail(petServiceAccount.id.userId) { proxyEmail =>
        IO.fromFuture(IO(googleDirectoryDAO.addMemberToGroup(proxyEmail, petServiceAccount.serviceAccount.email)))
      }
    } yield ()

  private[google] def removePetServiceAccountKey(
      userId: WorkbenchUserId,
      project: GoogleProject,
      keyId: ServiceAccountKeyId,
      samRequestContext: SamRequestContext
  ): IO[Unit] =
    for {
      maybePet <- directoryDAO.loadPetServiceAccount(PetServiceAccountId(userId, project), samRequestContext)
      result <- maybePet match {
        case Some(pet) => googleKeyCache.removeKey(pet, keyId)
        case None => IO.unit
      }
    } yield result

  /** Evaluate a future for each pet in parallel.
    */
  private[google] def forAllPets[T](userId: WorkbenchUserId, samRequestContext: SamRequestContext)(f: PetServiceAccount => IO[T]): IO[Seq[T]] =
    for {
      pets <- directoryDAO.getAllPetServiceAccountsForUser(userId, samRequestContext)
      a <- pets.traverse { pet =>
        f(pet)
      }
    } yield a
}
