package org.broadinstitute.dsde.workbench.sam.dataAccess

import cats.effect.unsafe.implicits.global
import com.unboundid.ldap.sdk.{LDAPConnection, LDAPConnectionPool, LDAPException}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccount, ServiceAccountDisplayName, ServiceAccountSubjectId}
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.TestSupport._
import org.broadinstitute.dsde.workbench.sam.config.DirectoryConfig
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}

import java.net.URI
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.{global => globalEc}

/**
  * Created by dvoet on 5/30/17.
  */
class LdapRegistrationDAOSpec extends AnyFlatSpec with Matchers with TestSupport with BeforeAndAfter with BeforeAndAfterAll with DirectorySubjectNameSupport {
  override lazy val directoryConfig: DirectoryConfig = TestSupport.directoryConfig
  val dirURI = new URI(directoryConfig.directoryUrl)
  val connectionPool = new LDAPConnectionPool(new LDAPConnection(dirURI.getHost, dirURI.getPort, directoryConfig.user, directoryConfig.password), directoryConfig.connectionPoolSize)
  val dao = new LdapRegistrationDAO(connectionPool, directoryConfig, TestSupport.blockingEc)
  val schemaDao = new JndiSchemaDAO(directoryConfig, schemaLockConfig)

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    runAndWait(schemaDao.init())
  }

  before {
    runAndWait(schemaDao.clearDatabase())
    runAndWait(schemaDao.createOrgUnits())
  }


  "LdapGroupDirectoryDAO"  should "create, read, delete users" in {
    val userId = WorkbenchUserId(UUID.randomUUID().toString)
    val user = WorkbenchUser(userId, None, WorkbenchEmail("foo@bar.com"), None)

    assertResult(None) {
      dao.loadUser(user.id, samRequestContext).unsafeRunSync()
    }

    assertResult(user) {
      dao.createUser(user, samRequestContext).unsafeRunSync()
    }

    assertResult(Some(user)) {
      dao.loadUser(user.id, samRequestContext).unsafeRunSync()
    }

    dao.deleteUser(user.id, samRequestContext).unsafeRunSync()

    assertResult(None) {
      dao.loadUser(user.id, samRequestContext).unsafeRunSync()
    }
  }

  it should "create, load, delete pet service accounts" in {
    val userId = WorkbenchUserId(UUID.randomUUID().toString)
    val user = WorkbenchUser(userId, None, WorkbenchEmail("foo@bar.com"), None)
    val serviceAccountUniqueId = ServiceAccountSubjectId(UUID.randomUUID().toString)
    val serviceAccount = ServiceAccount(serviceAccountUniqueId, WorkbenchEmail("foo@bar.com"), ServiceAccountDisplayName(""))
    val project = GoogleProject("testproject")
    val petServiceAccount = PetServiceAccount(PetServiceAccountId(userId, project), serviceAccount)

    assertResult(user) {
      dao.createUser(user, samRequestContext).unsafeRunSync()
    }

    assertResult(None) {
      dao.loadPetServiceAccount(petServiceAccount.id, samRequestContext).unsafeRunSync()
    }

    assertResult(petServiceAccount) {
      dao.createPetServiceAccount(petServiceAccount, samRequestContext).unsafeRunSync()
    }

    assertResult(Some(petServiceAccount)) {
      dao.loadPetServiceAccount(petServiceAccount.id, samRequestContext).unsafeRunSync()
    }

    dao.deletePetServiceAccount(petServiceAccount.id, samRequestContext).unsafeRunSync()

    assertResult(None) {
      dao.loadPetServiceAccount(petServiceAccount.id, samRequestContext).unsafeRunSync()
    }
  }

  it should "succeed if the user has been created" in {
    val userId = WorkbenchUserId(UUID.randomUUID().toString)
    val user = WorkbenchUser(userId, None, WorkbenchEmail("foo@bar.com"), None)

    assertResult(None) {
      dao.loadUser(user.id, samRequestContext).unsafeRunSync()
    }

    assertResult(user) {
      dao.createUser(user, samRequestContext).unsafeRunSync()
    }

    assertResult(Some(user)) {
      dao.loadUser(user.id, samRequestContext).unsafeRunSync()
    }
  }

  it should "disable users when deleting them" in {
    val user = WorkbenchUser(WorkbenchUserId(UUID.randomUUID().toString), None, WorkbenchEmail("foo@bar.com"), None)

    assertResult(user) {
      dao.createUser(user, samRequestContext).unsafeRunSync()
    }

    dao.enableIdentity(user.id, samRequestContext).unsafeRunSync()

    assertResult(true) {
      dao.isEnabled(user.id, samRequestContext).unsafeRunSync()
    }

    dao.deleteUser(user.id, samRequestContext).unsafeRunSync()

    assertResult(None) {
      dao.loadUser(user.id, samRequestContext).unsafeRunSync()
    }

    assertResult(false) {
      dao.isEnabled(user.id, samRequestContext).unsafeRunSync()
    }
  }

  it should "throw an exception when trying to overwrite an existing googleSubjectId" in {
    val user = WorkbenchUser(WorkbenchUserId(UUID.randomUUID().toString), Some(GoogleSubjectId("existingGoogleSubjectId")), WorkbenchEmail("foo@bar.com"), None)

    assertResult(user) {
      dao.createUser(user, samRequestContext).unsafeRunSync()
    }

    assertThrows[LDAPException] {
      dao.setGoogleSubjectId(user.id, GoogleSubjectId("newGoogleSubjectId"), samRequestContext).unsafeRunSync()
    }
  }
}


