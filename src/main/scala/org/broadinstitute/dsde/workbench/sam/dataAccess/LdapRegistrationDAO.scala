package org.broadinstitute.dsde.workbench.sam.dataAccess

import akka.http.scaladsl.model.StatusCodes
import cats.effect.{ContextShift, IO, Timer}
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

  override def getConnectionType(): ConnectionType = ConnectionType.LDAP

  override def createUser(user: WorkbenchUser, samRequestContext: SamRequestContext): IO[WorkbenchUser] = {
    val attrs = List(
      new Attribute(Attr.email, user.email.value),
      new Attribute(Attr.sn, user.id.value),
      new Attribute(Attr.cn, user.id.value),
      new Attribute(Attr.uid, user.id.value),
      new Attribute("objectclass", Seq("top", "workbenchPerson").asJava)
    ) ++ user.googleSubjectId.map(gsid => List(new Attribute(Attr.googleSubjectId, gsid.value))).getOrElse(List.empty)

    executeLdap(IO(ldapConnectionPool.add(userDn(user.id), attrs: _*)), "createUser", samRequestContext).adaptError {
      case ldape: LDAPException if ldape.getResultCode == ResultCode.ENTRY_ALREADY_EXISTS =>
        new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"identity with id ${user.id} already exists"))
    } *> IO.pure(user)
  }

  override def loadUser(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Option[WorkbenchUser]] = executeLdap(IO(loadUserInternal(userId)), "loadUser", samRequestContext)

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

  //To be used only in the event of the ToS version being bumped
  override def disableAllHumanIdentities(samRequestContext: SamRequestContext): IO[Unit] = {
    //The iam.gserviceaccount.com filter is in place to ensure that only human identities are disabled. Service Accounts (both regular SAs and Pet SAs) are
    // currently exempt for ToS-enforcement, thus, they're ignored when disabling identities
    val humanIdentityDnsIO = (executeLdap(IO(ldapConnectionPool.search(peopleOu, SearchScope.SUB, "(!(mail=*.iam.gserviceaccount.com))")), "getAllIdentitiesToDisable", samRequestContext) map { results =>
      results.getSearchEntries.asScala.toList.map { result =>
        unmarshalUser(result)
      }
    }).map { identityResults => identityResults.collect { case Right(user) => subjectDn(user.id) }}

    val enabledIdentityDnsIO = executeLdap(IO(getAttributes(ldapConnectionPool.getEntry(directoryConfig.enabledUsersGroupDn, Attr.member), Attr.member)), "getAllIdentitiesEnabled", samRequestContext).map(_.toList)

    for {
      humanIdentityDns <- humanIdentityDnsIO
      enabledIdentityDns <- enabledIdentityDnsIO
      humanIdentityDnsToDisable = humanIdentityDns intersect enabledIdentityDns
      result <- executeLdap(IO(ldapConnectionPool.modify(directoryConfig.enabledUsersGroupDn, new Modification(ModificationType.DELETE, Attr.member, humanIdentityDnsToDisable:_*))).void, "disableAllHumanIdentities", samRequestContext).recover {
        case ldape: LDAPException if ldape.getResultCode == ResultCode.NO_SUCH_ATTRIBUTE => //if the attr or member is already missing, then that's fine
      }
    } yield result
  }

  override def isEnabled(subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Boolean] =
    for {
      entry <- executeLdap(IO(ldapConnectionPool.getEntry(directoryConfig.enabledUsersGroupDn, Attr.member)), "isEnabled", samRequestContext)
    } yield {
      val result = for {
        e <- Option(entry)
        members <- Option(e.getAttributeValues(Attr.member))
      } yield members.contains(subjectDn(subject))
      result.getOrElse(false)
    }

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
}
