package org.broadinstitute.dsde.workbench.sam.directory

import java.net.URI
import java.util.UUID

import com.unboundid.ldap.sdk.{LDAPConnection, LDAPConnectionPool}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccount, ServiceAccountDisplayName, ServiceAccountSubjectId}
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.TestSupport._
import org.broadinstitute.dsde.workbench.sam.config.DirectoryConfig
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FlatSpec, Matchers}

import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Created by dvoet on 5/30/17.
  */
class LdapRegistrationDAOSpec extends FlatSpec with Matchers with TestSupport with BeforeAndAfter with BeforeAndAfterAll with DirectorySubjectNameSupport {
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
    val user = WorkbenchUser(userId, None, WorkbenchEmail("foo@bar.com"))

    assertResult(None) {
      dao.loadUser(user.id).unsafeRunSync()
    }

    assertResult(user) {
      dao.createUser(user).unsafeRunSync()
    }

    assertResult(Some(user)) {
      dao.loadUser(user.id).unsafeRunSync()
    }

    dao.deleteUser(user.id).unsafeRunSync()

    assertResult(None) {
      dao.loadUser(user.id).unsafeRunSync()
    }
  }

  it should "create, load, delete pet service accounts" in {
    val userId = WorkbenchUserId(UUID.randomUUID().toString)
    val user = WorkbenchUser(userId, None, WorkbenchEmail("foo@bar.com"))
    val serviceAccountUniqueId = ServiceAccountSubjectId(UUID.randomUUID().toString)
    val serviceAccount = ServiceAccount(serviceAccountUniqueId, WorkbenchEmail("foo@bar.com"), ServiceAccountDisplayName(""))
    val project = GoogleProject("testproject")
    val petServiceAccount = PetServiceAccount(PetServiceAccountId(userId, project), serviceAccount)

    assertResult(user) {
      dao.createUser(user).unsafeRunSync()
    }

    assertResult(None) {
      dao.loadPetServiceAccount(petServiceAccount.id).unsafeRunSync()
    }

    assertResult(petServiceAccount) {
      dao.createPetServiceAccount(petServiceAccount).unsafeRunSync()
    }

    assertResult(Some(petServiceAccount)) {
      dao.loadPetServiceAccount(petServiceAccount.id).unsafeRunSync()
    }

    dao.deletePetServiceAccount(petServiceAccount.id).unsafeRunSync()

    assertResult(None) {
      dao.loadPetServiceAccount(petServiceAccount.id).unsafeRunSync()
    }
  }

  it should "succeed if the user has been created" in {
    val userId = WorkbenchUserId(UUID.randomUUID().toString)
    val user = WorkbenchUser(userId, None, WorkbenchEmail("foo@bar.com"))

    assertResult(None) {
      dao.loadUser(user.id).unsafeRunSync()
    }

    assertResult(user) {
      dao.createUser(user).unsafeRunSync()
    }

    assertResult(Some(user)) {
      dao.loadUser(user.id).unsafeRunSync()
    }
  }
}


