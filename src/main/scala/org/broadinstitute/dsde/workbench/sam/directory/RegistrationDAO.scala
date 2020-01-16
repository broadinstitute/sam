package org.broadinstitute.dsde.workbench.sam.directory
import cats.effect.IO
import org.broadinstitute.dsde.workbench.model._

/**
  * This class is here because even though Postgres is the source of record, Apache proxies still query LDAP directly
  * to determine if incoming requests are for a User in the Enabled Users group. This means that whenever we create,
  * delete, enable, or disable users in Postgres, we must ensure that the change is reflected in LDAP. Once we move
  * away from a solution that requires that the Apache proxies query this group, we can remove the RegistrationDAO.
  */
trait RegistrationDAO {
  def createUser(user: WorkbenchUser): IO[WorkbenchUser]
  def loadUser(userId: WorkbenchUserId): IO[Option[WorkbenchUser]]
  def deleteUser(userId: WorkbenchUserId): IO[Unit]
  def enableIdentity(subject: WorkbenchSubject): IO[Unit]
  def disableIdentity(subject: WorkbenchSubject): IO[Unit]
  def isEnabled(subject: WorkbenchSubject): IO[Boolean]
  def createPetServiceAccount(petServiceAccount: PetServiceAccount): IO[PetServiceAccount]
  def loadPetServiceAccount(petServiceAccountId: PetServiceAccountId): IO[Option[PetServiceAccount]]
  def deletePetServiceAccount(petServiceAccountId: PetServiceAccountId): IO[Unit]
}
