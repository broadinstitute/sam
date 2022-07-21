package org.broadinstitute.dsde.workbench.sam.dataAccess

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import com.unboundid.ldap.sdk._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.{ServiceAccount, ServiceAccountDisplayName, ServiceAccountSubjectId}
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.config.DirectoryConfig
import org.broadinstitute.dsde.workbench.sam.dataAccess.ConnectionType.ConnectionType
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO.{Attr, ObjectClass}
import org.broadinstitute.dsde.workbench.sam.util.{LdapSupport, SamRequestContext}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}
import cats.effect.Temporal
import org.broadinstitute.dsde.workbench.sam.model.SamUser

// use ExecutionContexts.blockingThreadPool for blockingEc
class LdapRegistrationDAO(
    protected val ldapConnectionPool: LDAPConnectionPool,
    protected val directoryConfig: DirectoryConfig,
    protected val ecForLdapBlockingIO: ExecutionContext)(implicit timer: Temporal[IO])
    extends DirectorySubjectNameSupport
      with LdapSupport
      with LazyLogging
      with RegistrationDAO {

  def retryLdapBusyWithBackoff[A](initialDelay: FiniteDuration, maxRetries: Int)(ioa: IO[A])
                         (implicit timer: Temporal[IO]): IO[A] = {
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

  override def getConnectionType(): ConnectionType = ConnectionType.LDAP

  override def createUser(user: SamUser, samRequestContext: SamRequestContext): IO[SamUser] = {
    val attrs = List(
      new Attribute(Attr.email, user.email.value),
      new Attribute(Attr.sn, user.id.value),
      new Attribute(Attr.cn, user.id.value),
      new Attribute(Attr.uid, user.id.value),
      new Attribute("objectclass", Seq("top", "workbenchPerson").asJava)
    ) ++ List(
      user.googleSubjectId.map(gsid => new Attribute(Attr.googleSubjectId, gsid.value)),
      user.azureB2CId.map(b2cId => new Attribute(Attr.azureB2CId, b2cId.value))
    ).flatten

    executeLdap(IO(ldapConnectionPool.add(userDn(user.id), attrs: _*)), "createUser", samRequestContext).adaptError {
      case ldape: LDAPException if ldape.getResultCode == ResultCode.ENTRY_ALREADY_EXISTS =>
        new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"identity with id ${user.id} already exists"))
    } *> IO.pure(user)
  }

  override def loadUser(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Option[SamUser]] = executeLdap(IO(loadUserInternal(userId)), "loadUser", samRequestContext)

  def loadUserInternal(userId: WorkbenchUserId) =
    Option(ldapConnectionPool.getEntry(userDn(userId))) flatMap { results =>
      unmarshalUser(results).toOption
    }

  // Deleting a user in ldap will also disable them to clear them out of the enabled-users group
  override def deleteUser(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Unit] =
    executeLdap(for {
      _ <- disableIdentity(userId, samRequestContext)
      _ <- IO(ldapConnectionPool.delete(userDn(userId)))
    } yield (), "deleteUser", samRequestContext)

  override def enableIdentity(subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Unit] =
    retryLdapBusyWithBackoff(100.millisecond, 4) {
      executeLdap(IO(ldapConnectionPool.modify(directoryConfig.enabledUsersGroupDn, new Modification(ModificationType.ADD, Attr.member, subjectDn(subject)))).void, "enableIdentity-modify", samRequestContext).recoverWith {
        case ldape: LDAPException if ldape.getResultCode == ResultCode.NO_SUCH_OBJECT =>
          executeLdap(IO(
              ldapConnectionPool.add(
                directoryConfig.enabledUsersGroupDn,
                new Attribute("objectclass", Seq("top", "groupofnames").asJava),
                new Attribute(Attr.member, subjectDn(subject)))), "enableIdentity-add", samRequestContext).void
        case ldape: LDAPException if ldape.getResultCode == ResultCode.ATTRIBUTE_OR_VALUE_EXISTS => IO.unit
      }
    }

  override def disableIdentity(subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Unit] = {
    executeLdap(IO(ldapConnectionPool.modify(directoryConfig.enabledUsersGroupDn, new Modification(ModificationType.DELETE, Attr.member, subjectDn(subject)))).void, "disableIdentity", samRequestContext).recover {
      case ldape: LDAPException if ldape.getResultCode == ResultCode.NO_SUCH_ATTRIBUTE
        || ldape.getResultCode == ResultCode.NO_SUCH_OBJECT =>
    }
  }

  @deprecated
  override def isEnabled(subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Boolean] =
    IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.InternalServerError, s"LdapRegistrationDAO is deprecated")))

  override def createEnabledUsersGroup(samRequestContext: SamRequestContext): IO[Unit] = {
    val objectClassAttr = new Attribute("objectclass", Seq("top", "groupofnames").asJava)

    executeLdap(IO(ldapConnectionPool.add(directoryConfig.enabledUsersGroupDn, Seq(objectClassAttr).asJava)), "createEnabledUsersGroup", samRequestContext)
      .handleErrorWith {
        case ldape: LDAPException if ldape.getResultCode == ResultCode.ENTRY_ALREADY_EXISTS =>
          IO.unit
      }
      .map(_ => ())
  }

  override def createPetServiceAccount(petServiceAccount: PetServiceAccount, samRequestContext: SamRequestContext): IO[PetServiceAccount] = {
    val attributes = createPetServiceAccountAttributes(petServiceAccount) ++
      Seq(new Attribute("objectclass", Seq("top", ObjectClass.petServiceAccount).asJava), new Attribute(Attr.project, petServiceAccount.id.project.value))

    executeLdap(IO(ldapConnectionPool.add(petDn(petServiceAccount.id), attributes: _*)), "createPetServiceAccount", samRequestContext)
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

  override def loadPetServiceAccount(petServiceAccountId: PetServiceAccountId, samRequestContext: SamRequestContext): IO[Option[PetServiceAccount]] =
    executeLdap(IO(Option(ldapConnectionPool.getEntry(petDn(petServiceAccountId))).map(unmarshalPetServiceAccount)), "loadPetServiceAccount", samRequestContext)

  private def unmarshalPetServiceAccount(entry: Entry): PetServiceAccount = {
    val uid = getAttribute(entry, Attr.uid).getOrElse(throw new WorkbenchException(s"${Attr.uid} attribute missing"))
    val email = getAttribute(entry, Attr.email).getOrElse(throw new WorkbenchException(s"${Attr.email} attribute missing"))
    val displayName = getAttribute(entry, Attr.givenName).getOrElse("")

    PetServiceAccount(
      dnToSubject(entry.getDN).asInstanceOf[PetServiceAccountId],
      ServiceAccount(ServiceAccountSubjectId(uid), WorkbenchEmail(email), ServiceAccountDisplayName(displayName))
    )
  }

  override def deletePetServiceAccount(petServiceAccountId: PetServiceAccountId, samRequestContext: SamRequestContext): IO[Unit] =
    executeLdap(IO(ldapConnectionPool.delete(petDn(petServiceAccountId))), "deletePetServiceAccount", samRequestContext)

  override def updatePetServiceAccount(petServiceAccount: PetServiceAccount, samRequestContext: SamRequestContext): IO[PetServiceAccount] = {
    val modifications = createPetServiceAccountAttributes(petServiceAccount).map { attribute =>
      new Modification(ModificationType.REPLACE, attribute.getName, attribute.getRawValues)
    }
    executeLdap(IO(ldapConnectionPool.modify(petDn(petServiceAccount.id), modifications.asJava)), "updatePetServiceAccount", samRequestContext) *> IO.pure(petServiceAccount)
  }

  override def setGoogleSubjectId(userId: WorkbenchUserId, googleSubjectId: GoogleSubjectId, samRequestContext: SamRequestContext): IO[Unit] =
    executeLdap(IO(ldapConnectionPool.modify(userDn(userId), new Modification(ModificationType.ADD, Attr.googleSubjectId, googleSubjectId.value))), "setGoogleSubjectId", samRequestContext)

  override def checkStatus(samRequestContext: SamRequestContext): Boolean = {
    val ldapIsHealthy = Try {
      ldapConnectionPool.getHealthCheck
      val connection = ldapConnectionPool.getConnection
      ldapConnectionPool.getHealthCheck.ensureNewConnectionValid(connection)
      ldapConnectionPool.releaseConnection(connection)
    } match {
      case Success(_) => true
      case Failure(_) => false
    }
    ldapIsHealthy
  }

  override def setUserAzureB2CId(userId: WorkbenchUserId, b2CId: AzureB2CId, samRequestContext: SamRequestContext): IO[Unit] =
    executeLdap(IO(ldapConnectionPool.modify(userDn(userId), new Modification(ModificationType.REPLACE, Attr.azureB2CId, b2CId.value))), "setUserAzureB2CId", samRequestContext)

}
