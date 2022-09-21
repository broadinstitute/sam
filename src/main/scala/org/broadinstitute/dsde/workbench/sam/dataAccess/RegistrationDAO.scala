package org.broadinstitute.dsde.workbench.sam.dataAccess

import cats.effect.IO
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.dataAccess.ConnectionType.ConnectionType
import org.broadinstitute.dsde.workbench.sam.model.SamUser
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

/** This class is here because even though Postgres is the source of record, Apache proxies still query LDAP directly to determine if incoming requests are for
  * a User in the Enabled Users group. This means that whenever we create, delete, enable, or disable users in Postgres, we must ensure that the change is
  * reflected in LDAP. Once we move away from a solution that requires that the Apache proxies query this group, we can remove the RegistrationDAO.
  */
trait RegistrationDAO {
  def getConnectionType(): ConnectionType
  def createUser(user: SamUser, samRequestContext: SamRequestContext): IO[SamUser]
  def loadUser(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Option[SamUser]]
  def deleteUser(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Unit]
  def enableIdentity(subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Unit]
  def disableIdentity(subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Unit]
  def isEnabled(subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Boolean]
  def createEnabledUsersGroup(samRequestContext: SamRequestContext): IO[Unit]
  def createPetServiceAccount(petServiceAccount: PetServiceAccount, samRequestContext: SamRequestContext): IO[PetServiceAccount]
  def loadPetServiceAccount(petServiceAccountId: PetServiceAccountId, samRequestContext: SamRequestContext): IO[Option[PetServiceAccount]]
  def deletePetServiceAccount(petServiceAccountId: PetServiceAccountId, samRequestContext: SamRequestContext): IO[Unit]
  def updatePetServiceAccount(petServiceAccount: PetServiceAccount, samRequestContext: SamRequestContext): IO[PetServiceAccount]
  def setGoogleSubjectId(userId: WorkbenchUserId, googleSubjectId: GoogleSubjectId, samRequestContext: SamRequestContext): IO[Unit]
  def checkStatus(samRequestContext: SamRequestContext): Boolean
  def setUserAzureB2CId(userId: WorkbenchUserId, b2CId: AzureB2CId, samRequestContext: SamRequestContext): IO[Unit]
}
