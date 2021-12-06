package org.broadinstitute.dsde.workbench.sam.dataAccess
import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import org.broadinstitute.dsde.workbench.google.errorReportSource
import org.broadinstitute.dsde.workbench.model.{ErrorReport, GoogleSubjectId, PetServiceAccount, PetServiceAccountId, WorkbenchEmail, WorkbenchExceptionWithErrorReport, WorkbenchSubject, WorkbenchUser, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.dataAccess.ConnectionType.ConnectionType
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

import scala.collection.concurrent.TrieMap
import scala.collection.mutable

class MockRegistrationDAO extends RegistrationDAO {
  private val enabledUsers: mutable.Map[WorkbenchSubject, Unit] = new TrieMap()

  private val users: mutable.Map[WorkbenchUserId, WorkbenchUser] = new TrieMap()
  private val usersWithEmails: mutable.Map[WorkbenchEmail, WorkbenchUserId] = new TrieMap()
  private val usersWithGoogleSubjectIds: mutable.Map[GoogleSubjectId, WorkbenchSubject] = new TrieMap()

  private val petServiceAccountsByUser: mutable.Map[PetServiceAccountId, PetServiceAccount] = new TrieMap()
  private val petsWithEmails: mutable.Map[WorkbenchEmail, PetServiceAccountId] = new TrieMap()

  override def getConnectionType(): ConnectionType = ConnectionType.LDAP

  override def createUser(user: WorkbenchUser, samRequestContext: SamRequestContext): IO[WorkbenchUser] =
    if (users.keySet.contains(user.id)) {
      IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"user ${user.id} already exists")))
    } else {
      users += user.id -> user
      usersWithEmails += user.email -> user.id
      user.googleSubjectId.map(gid => usersWithGoogleSubjectIds += gid -> user.id)

      IO.pure(user)
    }

  override def loadUser(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Option[WorkbenchUser]] = IO {
    users.get(userId)
  }

  override def deleteUser(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Unit] = IO {
    users -= userId
  }

  override def enableIdentity(subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Unit] = IO.pure(enabledUsers += ((subject, ())))

  override def disableIdentity(subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Unit] = IO {
    enabledUsers -= subject
  }

  override def disableAllIdentities(samRequestContext: SamRequestContext): IO[Unit] = IO {
    enabledUsers --= enabledUsers.keys
  }

  override def isEnabled(subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Boolean] = IO {
    enabledUsers.contains(subject)
  }

  override def createPetServiceAccount(petServiceAccount: PetServiceAccount, samRequestContext: SamRequestContext): IO[PetServiceAccount] = {
    if (petServiceAccountsByUser.keySet.contains(petServiceAccount.id)) {
      IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"pet service account ${petServiceAccount.id} already exists")))
    }
    petServiceAccountsByUser += petServiceAccount.id -> petServiceAccount
    petsWithEmails += petServiceAccount.serviceAccount.email -> petServiceAccount.id
    usersWithGoogleSubjectIds += GoogleSubjectId(petServiceAccount.serviceAccount.subjectId.value) -> petServiceAccount.id
    IO.pure(petServiceAccount)
  }

  override def loadPetServiceAccount(petServiceAccountId: PetServiceAccountId, samRequestContext: SamRequestContext): IO[Option[PetServiceAccount]] = IO {
    petServiceAccountsByUser.get(petServiceAccountId)
  }

  override def deletePetServiceAccount(petServiceAccountId: PetServiceAccountId, samRequestContext: SamRequestContext): IO[Unit] = IO {
    petServiceAccountsByUser -= petServiceAccountId
  }

  override def updatePetServiceAccount(petServiceAccount: PetServiceAccount, samRequestContext: SamRequestContext): IO[PetServiceAccount] = IO {
    petServiceAccountsByUser.update(petServiceAccount.id, petServiceAccount)
    petServiceAccount
  }

  override def setGoogleSubjectId(userId: WorkbenchUserId, googleSubjectId: GoogleSubjectId, samRequestContext: SamRequestContext): IO[Unit] = {
    users.get(userId).fold[IO[Unit]](IO.pure(new Exception(s"user $userId not found")))(
      u => IO.pure(users.concat(List((userId, u.copy(googleSubjectId = Some(googleSubjectId))))))
    )
  }

  override def checkStatus(samRequestContext: SamRequestContext): Boolean = true
}
