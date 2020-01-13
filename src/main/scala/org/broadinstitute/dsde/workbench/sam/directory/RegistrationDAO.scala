package org.broadinstitute.dsde.workbench.sam.directory
import cats.effect.IO
import org.broadinstitute.dsde.workbench.model._

trait RegistrationDAO {
    def createUser(user: WorkbenchUser): IO[WorkbenchUser]
    def loadUser(userId: WorkbenchUserId) : IO[Option[WorkbenchUser]]
    def deleteUser(userId: WorkbenchUserId): IO[Unit]
    def enableIdentity(subject: WorkbenchSubject): IO[Unit]
    def disableIdentity(subject: WorkbenchSubject): IO[Unit]
    def isEnabled(subject: WorkbenchSubject): IO[Boolean]
    def createPetServiceAccount(petServiceAccount: PetServiceAccount): IO[PetServiceAccount]
    def deletePetServiceAccount(petServiceAccountId: PetServiceAccountId): IO[Unit]
  }
