package org.broadinstitute.dsde.workbench.sam.directory
import cats.effect.IO
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.util.TraceContext

/**
  * This class is here because even though Postgres is the source of record, Apache proxies still query LDAP directly
  * to determine if incoming requests are for a User in the Enabled Users group. This means that whenever we create,
  * delete, enable, or disable users in Postgres, we must ensure that the change is reflected in LDAP. Once we move
  * away from a solution that requires that the Apache proxies query this group, we can remove the RegistrationDAO.
  */
trait RegistrationDAO {
  def createUser(user: WorkbenchUser, traceContext: TraceContext): IO[WorkbenchUser]
  def loadUser(userId: WorkbenchUserId, traceContext: TraceContext): IO[Option[WorkbenchUser]]
  def deleteUser(userId: WorkbenchUserId, traceContext: TraceContext): IO[Unit]
  def enableIdentity(subject: WorkbenchSubject, traceContext: TraceContext): IO[Unit]
  def disableIdentity(subject: WorkbenchSubject, traceContext: TraceContext): IO[Unit]
  def isEnabled(subject: WorkbenchSubject, traceContext: TraceContext): IO[Boolean]
  def createPetServiceAccount(petServiceAccount: PetServiceAccount, traceContext: TraceContext): IO[PetServiceAccount]
  def loadPetServiceAccount(petServiceAccountId: PetServiceAccountId, traceContext: TraceContext): IO[Option[PetServiceAccount]]
  def deletePetServiceAccount(petServiceAccountId: PetServiceAccountId, traceContext: TraceContext): IO[Unit]
  def updatePetServiceAccount(petServiceAccount: PetServiceAccount, traceContext: TraceContext): IO[PetServiceAccount]
  def setGoogleSubjectId(userId: WorkbenchUserId, googleSubjectId: GoogleSubjectId, traceContext: TraceContext): IO[Unit]
}
