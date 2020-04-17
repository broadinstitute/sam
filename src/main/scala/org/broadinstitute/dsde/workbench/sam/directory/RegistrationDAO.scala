package org.broadinstitute.dsde.workbench.sam.directory
import cats.effect.IO
import io.opencensus.trace.Span
import org.broadinstitute.dsde.workbench.model._

/**
  * This class is here because even though Postgres is the source of record, Apache proxies still query LDAP directly
  * to determine if incoming requests are for a User in the Enabled Users group. This means that whenever we create,
  * delete, enable, or disable users in Postgres, we must ensure that the change is reflected in LDAP. Once we move
  * away from a solution that requires that the Apache proxies query this group, we can remove the RegistrationDAO.
  */
trait RegistrationDAO {
  def createUser(user: WorkbenchUser, parentSpan: Span): IO[WorkbenchUser]
  def loadUser(userId: WorkbenchUserId, parentSpan: Span): IO[Option[WorkbenchUser]]
  def deleteUser(userId: WorkbenchUserId, parentSpan: Span): IO[Unit]
  def enableIdentity(subject: WorkbenchSubject, parentSpan: Span): IO[Unit]
  def disableIdentity(subject: WorkbenchSubject, parentSpan: Span): IO[Unit]
  def isEnabled(subject: WorkbenchSubject, parentSpan: Span): IO[Boolean]
  def createPetServiceAccount(petServiceAccount: PetServiceAccount, parentSpan: Span): IO[PetServiceAccount]
  def loadPetServiceAccount(petServiceAccountId: PetServiceAccountId, parentSpan: Span): IO[Option[PetServiceAccount]]
  def deletePetServiceAccount(petServiceAccountId: PetServiceAccountId, parentSpan: Span): IO[Unit]
  def updatePetServiceAccount(petServiceAccount: PetServiceAccount, parentSpan: Span): IO[PetServiceAccount]
  def setGoogleSubjectId(userId: WorkbenchUserId, googleSubjectId: GoogleSubjectId, parentSpan: Span): IO[Unit]
}
