package org.broadinstitute.dsde.workbench.sam.directory

import java.net.URI
import java.util.UUID

import akka.http.scaladsl.model.StatusCodes
import com.unboundid.ldap.sdk.{LDAPConnection, LDAPConnectionPool}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccount, ServiceAccountDisplayName, ServiceAccountSubjectId}
import org.broadinstitute.dsde.workbench.sam.{Generator, TestSupport}
import org.broadinstitute.dsde.workbench.sam.TestSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.LdapAccessPolicyDAO
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FlatSpec, Matchers}

import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Created by dvoet on 5/30/17.
  */
class LdapDirectoryDAOSpec extends FlatSpec with Matchers with TestSupport with BeforeAndAfter with BeforeAndAfterAll {
  val dirURI = new URI(directoryConfig.directoryUrl)
  val connectionPool = new LDAPConnectionPool(new LDAPConnection(dirURI.getHost, dirURI.getPort, directoryConfig.user, directoryConfig.password), directoryConfig.connectionPoolSize)
  val dao = new LdapDirectoryDAO(connectionPool, directoryConfig, TestSupport.blockingEc)
  val schemaDao = new JndiSchemaDAO(directoryConfig, schemaLockConfig)

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    runAndWait(schemaDao.init())
  }

  before {
    runAndWait(schemaDao.clearDatabase())
    runAndWait(schemaDao.createOrgUnits())
  }


  "LdapGroupDirectoryDAO" should "create, read, delete groups" in {
    val groupName = WorkbenchGroupName(UUID.randomUUID().toString)
    val group = BasicWorkbenchGroup(groupName, Set.empty, WorkbenchEmail("john@doe.org"))

    assertResult(None) {
      dao.loadGroup(group.id).unsafeRunSync()
    }

    assertResult(group) {
      dao.createGroup(group).unsafeRunSync()
    }

    val conflict = intercept[WorkbenchExceptionWithErrorReport] {
      dao.createGroup(group).unsafeRunSync()
    }
    assert(conflict.errorReport.statusCode.contains(StatusCodes.Conflict))

    assertResult(Some(group)) {
      dao.loadGroup(group.id).unsafeRunSync()
    }

    dao.deleteGroup(group.id).unsafeRunSync()

    assertResult(None) {
      dao.loadGroup(group.id).unsafeRunSync()
    }
  }

  it should "create, read, delete users" in {
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

    runAndWait(dao.deleteUser(user.id))

    assertResult(None) {
      dao.loadUser(user.id).unsafeRunSync()
    }
  }

  it should "add and read proxy group email" in {
    val userId = WorkbenchUserId(UUID.randomUUID().toString)
    val user = WorkbenchUser(userId, None, WorkbenchEmail("foo@bar.com"))

    assertResult(user) {
      dao.createUser(user).unsafeRunSync()
    }

    assertResult(None) {
      runAndWait(dao.readProxyGroup(userId))
    }

    runAndWait(dao.addProxyGroup(userId, WorkbenchEmail("foo_1234@test.firecloud.org")))

    assertResult(Some(WorkbenchEmail("foo_1234@test.firecloud.org"))) {
      runAndWait(dao.readProxyGroup(userId))
    }
  }

  it should "create, read, delete pet service accounts" in {
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

    assertResult(Seq()) {
      runAndWait(dao.getAllPetServiceAccountsForUser(userId))
    }

    assertResult(petServiceAccount) {
      dao.createPetServiceAccount(petServiceAccount).unsafeRunSync()
    }

    assertResult(Some(petServiceAccount)) {
      dao.loadPetServiceAccount(petServiceAccount.id).unsafeRunSync()
    }

    assertResult(Seq(petServiceAccount)) {
      runAndWait(dao.getAllPetServiceAccountsForUser(userId))
    }

    val updatedPetSA = petServiceAccount.copy(serviceAccount = ServiceAccount(ServiceAccountSubjectId(UUID.randomUUID().toString), WorkbenchEmail("foo@bar.com"), ServiceAccountDisplayName("qqq")))
    assertResult(updatedPetSA) {
      dao.updatePetServiceAccount(updatedPetSA).unsafeRunSync()
    }

    assertResult(Some(updatedPetSA)) {
      dao.loadPetServiceAccount(petServiceAccount.id).unsafeRunSync()
    }

    dao.deletePetServiceAccount(petServiceAccount.id).unsafeRunSync()

    assertResult(None) {
      dao.loadPetServiceAccount(petServiceAccount.id).unsafeRunSync()
    }

    assertResult(Seq()) {
      runAndWait(dao.getAllPetServiceAccountsForUser(userId))
    }
  }

  it should "list groups" in {
    val userId = WorkbenchUserId(UUID.randomUUID().toString)
    val user = WorkbenchUser(userId, None, WorkbenchEmail("foo@bar.com"))

    val groupName1 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group1 = BasicWorkbenchGroup(groupName1, Set(userId), WorkbenchEmail("g1@example.com"))

    val groupName2 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group2 = BasicWorkbenchGroup(groupName2, Set(groupName1), WorkbenchEmail("g2@example.com"))

    dao.createUser(user).unsafeRunSync()
    dao.createGroup(group1).unsafeRunSync()
    dao.createGroup(group2).unsafeRunSync()

    try {
      assertResult(Set(groupName1, groupName2)) {
        dao.listUsersGroups(userId).unsafeRunSync()
      }
    } finally {
      runAndWait(dao.deleteUser(userId))
      dao.deleteGroup(groupName2).unsafeRunSync()
      dao.deleteGroup(groupName1).unsafeRunSync()
    }
  }

  it should "list group ancestors" in {
    val groupName1 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group1 = BasicWorkbenchGroup(groupName1, Set(), WorkbenchEmail("g1@example.com"))

    val groupName2 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group2 = BasicWorkbenchGroup(groupName2, Set(groupName1), WorkbenchEmail("g2@example.com"))

    val groupName3 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group3 = BasicWorkbenchGroup(groupName3, Set(groupName2), WorkbenchEmail("g3@example.com"))

    dao.createGroup(group1).unsafeRunSync()
    dao.createGroup(group2).unsafeRunSync()
    dao.createGroup(group3).unsafeRunSync()

    try {
      assertResult(Set(groupName2, groupName3)) {
        dao.listAncestorGroups(groupName1).unsafeRunSync()
      }
    } finally {
      dao.deleteGroup(groupName3).unsafeRunSync()
      dao.deleteGroup(groupName2).unsafeRunSync()
      dao.deleteGroup(groupName1).unsafeRunSync()
    }
  }

  it should "handle circular groups" in {
    val userId = WorkbenchUserId(UUID.randomUUID().toString)
    val user = WorkbenchUser(userId, None, WorkbenchEmail("foo@bar.com"))

    val groupName1 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group1 = BasicWorkbenchGroup(groupName1, Set(userId), WorkbenchEmail("g1@example.com"))

    val groupName2 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group2 = BasicWorkbenchGroup(groupName2, Set(groupName1), WorkbenchEmail("g2@example.com"))

    val groupName3 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group3 = BasicWorkbenchGroup(groupName3, Set(groupName2), WorkbenchEmail("g3@example.com"))

    dao.createUser(user).unsafeRunSync()
    dao.createGroup(group1).unsafeRunSync()
    dao.createGroup(group2).unsafeRunSync()
    dao.createGroup(group3).unsafeRunSync()

    dao.addGroupMember(groupName1, groupName3).unsafeRunSync()

    try {
      assertResult(Set(groupName1, groupName2, groupName3)) {
        dao.listUsersGroups(userId).unsafeRunSync()
      }

      assertResult(Set(groupName1, groupName2, groupName3)) {
        dao.listAncestorGroups(groupName3).unsafeRunSync()
      }
    } finally {
      runAndWait(dao.deleteUser(userId))
      dao.removeGroupMember(groupName1, groupName3).unsafeRunSync()
      dao.deleteGroup(groupName3).unsafeRunSync()
      dao.deleteGroup(groupName2).unsafeRunSync()
      dao.deleteGroup(groupName1).unsafeRunSync()
    }
  }

  it should "add/remove groups" in {
    val userId = WorkbenchUserId(UUID.randomUUID().toString)
    val user = WorkbenchUser(userId, None, WorkbenchEmail("foo@bar.com"))

    val groupName1 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group1 = BasicWorkbenchGroup(groupName1, Set.empty, WorkbenchEmail("g1@example.com"))

    val groupName2 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group2 = BasicWorkbenchGroup(groupName2, Set.empty, WorkbenchEmail("g2@example.com"))

    dao.createUser(user).unsafeRunSync()
    dao.createGroup(group1).unsafeRunSync()
    dao.createGroup(group2).unsafeRunSync()

    try {

      dao.addGroupMember(groupName1, userId).unsafeRunSync()

      assertResult(Some(group1.copy(members = Set(userId)))) {
        dao.loadGroup(groupName1).unsafeRunSync()
      }

      dao.addGroupMember(groupName1, groupName2).unsafeRunSync()

      assertResult(Some(group1.copy(members = Set(userId, groupName2)))) {
        dao.loadGroup(groupName1).unsafeRunSync()
      }

      dao.removeGroupMember(groupName1, userId).unsafeRunSync()

      assertResult(Some(group1.copy(members = Set(groupName2)))) {
        dao.loadGroup(groupName1).unsafeRunSync()
      }

      dao.removeGroupMember(groupName1, groupName2).unsafeRunSync()

      assertResult(Some(group1)) {
        dao.loadGroup(groupName1).unsafeRunSync()
      }

    } finally {
      runAndWait(dao.deleteUser(userId))
      dao.deleteGroup(groupName1).unsafeRunSync()
      dao.deleteGroup(groupName2).unsafeRunSync()
    }
  }

  it should "handle different kinds of groups" in {
    val userId = WorkbenchUserId(UUID.randomUUID().toString)
    val user = WorkbenchUser(userId, None, WorkbenchEmail("foo@bar.com"))

    val groupName1 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group1 = BasicWorkbenchGroup(groupName1, Set(userId), WorkbenchEmail("g1@example.com"))

    val groupName2 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group2 = BasicWorkbenchGroup(groupName2, Set.empty, WorkbenchEmail("g2@example.com"))

    dao.createUser(user).unsafeRunSync()
    dao.createGroup(group1).unsafeRunSync()
    dao.createGroup(group2).unsafeRunSync()

    val policyDAO = new LdapAccessPolicyDAO(connectionPool, directoryConfig, TestSupport.blockingEc)

    val typeName1 = ResourceTypeName(UUID.randomUUID().toString)

    val resource = Resource(typeName1, ResourceId("resource"), Set.empty)
    val policy1 = AccessPolicy(
      FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("role1-a")), Set(userId), WorkbenchEmail("p1@example.com"), Set(ResourceRoleName("role1")), Set(ResourceAction("action1"), ResourceAction("action2")), public = false)

    policyDAO.createResourceType(typeName1).unsafeRunSync()
    policyDAO.createResource(resource).unsafeRunSync()
    policyDAO.createPolicy(policy1).unsafeRunSync()

    assert(runAndWait(dao.isGroupMember(group1.id, userId)))
    assert(!runAndWait(dao.isGroupMember(group2.id, userId)))
    assert(runAndWait(dao.isGroupMember(policy1.id, userId)))
  }

  it should "be case insensitive when checking for group membership" in {
    val userId = WorkbenchUserId(UUID.randomUUID().toString)
    val user = WorkbenchUser(userId, None, WorkbenchEmail("foo@bar.com"))

    val groupName1 = WorkbenchGroupName(UUID.randomUUID().toString)
    val group1 = BasicWorkbenchGroup(groupName1, Set(userId), WorkbenchEmail("g1@example.com"))

    dao.createUser(user).unsafeRunSync()
    dao.createGroup(group1).unsafeRunSync()

    assert(runAndWait(dao.isGroupMember(WorkbenchGroupName(group1.id.value.toUpperCase), userId)))
  }

  it should "get pet for user" in {
    val userId = WorkbenchUserId(UUID.randomUUID().toString)
    val user = WorkbenchUser(userId, None, WorkbenchEmail("foo@bar.com"))

    dao.createUser(user).unsafeRunSync()

    val serviceAccount = ServiceAccount(ServiceAccountSubjectId("09834572039847519384"), WorkbenchEmail("foo@sa.com"), ServiceAccountDisplayName("blarg"))
    val pet = PetServiceAccount(PetServiceAccountId(userId, GoogleProject("foo")), serviceAccount)

    dao.loadPetServiceAccount(pet.id).unsafeRunSync() shouldBe None
    dao.createPetServiceAccount(pet).unsafeRunSync() shouldBe pet
    dao.loadPetServiceAccount(pet.id).unsafeRunSync() shouldBe Some(pet)
    dao.getUserFromPetServiceAccount(serviceAccount.subjectId).unsafeRunSync() shouldBe Some(user)

    // uid that does not exist
    dao.getUserFromPetServiceAccount(ServiceAccountSubjectId("asldkasfa")).unsafeRunSync() shouldBe None

    // uid that does exist but is not a pet
    dao.getUserFromPetServiceAccount(ServiceAccountSubjectId(user.id.value)).unsafeRunSync() shouldBe None
  }

  "JndiDirectoryDAO safeDelete" should "prevent deleting groups that are sub-groups of other groups" in {
    val childGroupName = WorkbenchGroupName(UUID.randomUUID().toString)
    val childGroup = BasicWorkbenchGroup(childGroupName, Set.empty, WorkbenchEmail("donnie@hollywood-lanes.com"))

    val parentGroupName = WorkbenchGroupName(UUID.randomUUID().toString)
    val parentGroup = BasicWorkbenchGroup(parentGroupName, Set(childGroupName), WorkbenchEmail("walter@hollywood-lanes.com"))

    assertResult(None) {
      dao.loadGroup(childGroupName).unsafeRunSync()
    }

    assertResult(None) {
      dao.loadGroup(parentGroupName).unsafeRunSync()
    }

    assertResult(childGroup) {
      dao.createGroup(childGroup).unsafeRunSync()
    }

    assertResult(parentGroup) {
      dao.createGroup(parentGroup).unsafeRunSync()
    }

    assertResult(Some(childGroup)) {
      dao.loadGroup(childGroupName).unsafeRunSync()
    }

    assertResult(Some(parentGroup)) {
      dao.loadGroup(parentGroupName).unsafeRunSync()
    }

    intercept[WorkbenchExceptionWithErrorReport] {
      dao.deleteGroup(childGroupName).unsafeRunSync()
    }

    assertResult(Some(childGroup)) {
      dao.loadGroup(childGroupName).unsafeRunSync()
    }

    assertResult(Some(parentGroup)) {
      dao.loadGroup(parentGroupName).unsafeRunSync()
    }
  }

  "JndiDirectoryDao loadSubjectEmail" should "fail if the user has not been created" in {
    val userId = WorkbenchUserId(UUID.randomUUID().toString)
    val user = WorkbenchUser(userId, None, WorkbenchEmail("foo@bar.com"))

    assertResult(None) {
      dao.loadUser(user.id).unsafeRunSync()
    }

    dao.loadSubjectEmail(userId).unsafeRunSync()shouldEqual None
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

    assertResult(Some(user.email)) {
      dao.loadSubjectEmail(userId).unsafeRunSync()
    }
  }

  "loadSubjectFromEmail" should "be able to load subject given an email" in {
    val user1 = Generator.genWorkbenchUser.sample.get
    val user2 = user1.copy(id = WorkbenchUserId(user1.id.value + "2"))
    val res = for {
      _ <- dao.createUser(user1)
      email1 <- dao.loadSubjectEmail(user1.id)
      email2 <- dao.loadSubjectEmail(user2.id)
    } yield {
      email1 shouldEqual(Some(user1.email))
      email2 shouldEqual(None)
    }
    res.unsafeRunSync()
  }

  "loadSubjectFromGoogleSubjectId" should "be able to load subject given an google subject Id" in {
    val user = Generator.genWorkbenchUser.sample.get
    val user1 = user.copy(googleSubjectId = Some(GoogleSubjectId(user.id.value)))
    val user2 = user1.copy(googleSubjectId = Some(GoogleSubjectId(user.id.value + "2")))

    val res = for {
      _ <- dao.createUser(user1)
      subject1 <- dao.loadSubjectFromGoogleSubjectId(user1.googleSubjectId.get)
      subject2 <- dao.loadSubjectFromGoogleSubjectId(user2.googleSubjectId.get)
    } yield {
      subject1 shouldEqual(Some(user1.id))
      subject2 shouldEqual(None)
    }
    res.unsafeRunSync()
  }
}


