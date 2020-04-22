package org.broadinstitute.dsde.workbench.sam.directory

import akka.http.scaladsl.model.StatusCodes
import cats.effect.{ContextShift, IO, Timer}
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import com.unboundid.ldap.sdk._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.{ServiceAccount, ServiceAccountDisplayName, ServiceAccountSubjectId}
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.config.DirectoryConfig
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO.{Attr, ObjectClass}
import org.broadinstitute.dsde.workbench.sam.util.{LdapSupport, TraceContext}

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

// use ExecutionContexts.blockingThreadPool for blockingEc
class LdapRegistrationDAO(
    protected val ldapConnectionPool: LDAPConnectionPool,
    protected val directoryConfig: DirectoryConfig,
    protected val ecForLdapBlockingIO: ExecutionContext)(implicit val cs: ContextShift[IO], timer: Timer[IO])
    extends DirectorySubjectNameSupport
      with LdapSupport
      with LazyLogging
      with RegistrationDAO {

  def retryLdapBusyWithBackoff[A](initialDelay: FiniteDuration, maxRetries: Int)(ioa: IO[A])
                         (implicit timer: Timer[IO]): IO[A] = {
    ioa.handleErrorWith { error =>
      error match {
        case ldape: LDAPException if maxRetries > 0 && ldape.getResultCode == ResultCode.BUSY =>
          logger.info(s"Retrying LDAP Operation due to BUSY (Error code ${ldape.getResultCode})")
          IO.sleep(initialDelay) *> retryLdapBusyWithBackoff(initialDelay * 2, maxRetries - 1)(ioa)
        case e =>
          logger.info(s"NOT Retrying LDAP Operation ${e.getMessage}) with ${maxRetries}")
          IO.raiseError(error)
      }
    }
  }

  override def createUser(user: WorkbenchUser): IO[WorkbenchUser] = {
    val attrs = List(
      new Attribute(Attr.email, user.email.value),
      new Attribute(Attr.sn, user.id.value),
      new Attribute(Attr.cn, user.id.value),
      new Attribute(Attr.uid, user.id.value),
      new Attribute("objectclass", Seq("top", "workbenchPerson").asJava)
    ) ++ user.googleSubjectId.map(gsid => List(new Attribute(Attr.googleSubjectId, gsid.value))).getOrElse(List.empty)

    executeLdap(IO(ldapConnectionPool.add(userDn(user.id), attrs: _*)), "createUser").adaptError {
      case ldape: LDAPException if ldape.getResultCode == ResultCode.ENTRY_ALREADY_EXISTS =>
        new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"identity with id ${user.id} already exists"))
    } *> IO.pure(user)
  }

  override def loadUser(userId: WorkbenchUserId): IO[Option[WorkbenchUser]] = executeLdap(IO(loadUserInternal(userId)), "loadUser")

  def loadUserInternal(userId: WorkbenchUserId) =
    Option(ldapConnectionPool.getEntry(userDn(userId))) flatMap { results =>
      unmarshalUser(results).toOption
    }

  // Deleting a user in ldap will also disable them to clear them out of the enabled-users group
  override def deleteUser(userId: WorkbenchUserId): IO[Unit] =
    executeLdap(for {
      _ <- disableIdentity(userId)
      _ <- IO(ldapConnectionPool.delete(userDn(userId)))
    } yield (), "deleteUser")

  override def enableIdentity(subject: WorkbenchSubject): IO[Unit] =
    retryLdapBusyWithBackoff(100.millisecond, 4) {
      executeLdap(IO(ldapConnectionPool.modify(directoryConfig.enabledUsersGroupDn, new Modification(ModificationType.ADD, Attr.member, subjectDn(subject)))).void, "enableIdentity-modify").recoverWith {
        case ldape: LDAPException if ldape.getResultCode == ResultCode.NO_SUCH_OBJECT =>
          executeLdap(IO(
              ldapConnectionPool.add(
                directoryConfig.enabledUsersGroupDn,
                new Attribute("objectclass", Seq("top", "groupofnames").asJava),
                new Attribute(Attr.member, subjectDn(subject)))), "enableIdentity-add").void
        case ldape: LDAPException if ldape.getResultCode == ResultCode.ATTRIBUTE_OR_VALUE_EXISTS => IO.unit
      }
    }

  override def disableIdentity(subject: WorkbenchSubject): IO[Unit] = {
    executeLdap(IO(ldapConnectionPool.modify(directoryConfig.enabledUsersGroupDn, new Modification(ModificationType.DELETE, Attr.member, subjectDn(subject)))).void, "disableIdentity").recover {
      case ldape: LDAPException if ldape.getResultCode == ResultCode.NO_SUCH_ATTRIBUTE
        || ldape.getResultCode == ResultCode.NO_SUCH_OBJECT =>
    }
  }

  override def isEnabled(subject: WorkbenchSubject): IO[Boolean] =
    for {
      entry <- executeLdap(IO(ldapConnectionPool.getEntry(directoryConfig.enabledUsersGroupDn, Attr.member)), "isEnabled")
    } yield {
      val result = for {
        e <- Option(entry)
        members <- Option(e.getAttributeValues(Attr.member))
      } yield members.contains(subjectDn(subject))
      result.getOrElse(false)
    }

  override def createPetServiceAccount(petServiceAccount: PetServiceAccount): IO[PetServiceAccount] = {
    val attributes = createPetServiceAccountAttributes(petServiceAccount) ++
      Seq(new Attribute("objectclass", Seq("top", ObjectClass.petServiceAccount).asJava), new Attribute(Attr.project, petServiceAccount.id.project.value))

    executeLdap(IO(ldapConnectionPool.add(petDn(petServiceAccount.id), attributes: _*)), "createPetServiceAccount")
      .handleErrorWith {
        case ldape: LDAPException if ldape.getResultCode == ResultCode.ENTRY_ALREADY_EXISTS =>
          IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"identity with id ${petServiceAccount.id} already exists")))
      }
      .map(_ => petServiceAccount)
  }

  private def createPetServiceAccountAttributes(petServiceAccount: PetServiceAccount) = {
    val attributes = Seq(
      new Attribute(Attr.email, petServiceAccount.serviceAccount.email.value),
      new Attribute(Attr.sn, petServiceAccount.serviceAccount.subjectId.value),
      new Attribute(Attr.cn, petServiceAccount.serviceAccount.subjectId.value),
      new Attribute(Attr.googleSubjectId, petServiceAccount.serviceAccount.subjectId.value),
      new Attribute(Attr.uid, petServiceAccount.serviceAccount.subjectId.value)
    )

    val displayNameAttribute = if (!petServiceAccount.serviceAccount.displayName.value.isEmpty) {
      Option(new Attribute(Attr.givenName, petServiceAccount.serviceAccount.displayName.value))
    } else {
      None
    }

    attributes ++ displayNameAttribute
  }

  override def loadPetServiceAccount(petServiceAccountId: PetServiceAccountId): IO[Option[PetServiceAccount]] =
    executeLdap(IO(Option(ldapConnectionPool.getEntry(petDn(petServiceAccountId))).map(unmarshalPetServiceAccount)), "loadPetServiceAccount")

  private def unmarshalPetServiceAccount(entry: Entry): PetServiceAccount = {
    val uid = getAttribute(entry, Attr.uid).getOrElse(throw new WorkbenchException(s"${Attr.uid} attribute missing"))
    val email = getAttribute(entry, Attr.email).getOrElse(throw new WorkbenchException(s"${Attr.email} attribute missing"))
    val displayName = getAttribute(entry, Attr.givenName).getOrElse("")

    PetServiceAccount(
      dnToSubject(entry.getDN).asInstanceOf[PetServiceAccountId],
      ServiceAccount(ServiceAccountSubjectId(uid), WorkbenchEmail(email), ServiceAccountDisplayName(displayName))
    )
  }

  override def deletePetServiceAccount(petServiceAccountId: PetServiceAccountId): IO[Unit] =
    executeLdap(IO(ldapConnectionPool.delete(petDn(petServiceAccountId))), "deletePetServiceAccount")

  override def updatePetServiceAccount(petServiceAccount: PetServiceAccount): IO[PetServiceAccount] = {
    val modifications = createPetServiceAccountAttributes(petServiceAccount).map { attribute =>
      new Modification(ModificationType.REPLACE, attribute.getName, attribute.getRawValues)
    }
    executeLdap(IO(ldapConnectionPool.modify(petDn(petServiceAccount.id), modifications.asJava)), "updatePetServiceAccount") *> IO.pure(petServiceAccount)
  }

  override def setGoogleSubjectId(userId: WorkbenchUserId, googleSubjectId: GoogleSubjectId): IO[Unit] =
    executeLdap(IO(ldapConnectionPool.modify(userDn(userId), new Modification(ModificationType.ADD, Attr.googleSubjectId, googleSubjectId.value))), "setGoogleSubjectId")
}
