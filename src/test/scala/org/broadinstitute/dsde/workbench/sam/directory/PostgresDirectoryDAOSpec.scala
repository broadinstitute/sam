package org.broadinstitute.dsde.workbench.sam.directory

import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup
import org.postgresql.util.PSQLException
import org.scalatest.{BeforeAndAfterEach, FlatSpec, Matchers}

import scala.concurrent.ExecutionContext.Implicits.global

class PostgresDirectoryDAOSpec extends FlatSpec with Matchers with BeforeAndAfterEach {
  val dao = new PostgresDirectoryDAO(TestSupport.dbRef, TestSupport.blockingEc)

  val defaultGroupName = WorkbenchGroupName("group")
  val defaultGroup = BasicWorkbenchGroup(defaultGroupName, Set.empty, WorkbenchEmail("foo@bar.com"))
  val defaultUserId = WorkbenchUserId("testUser")
  val defaultUser = WorkbenchUser(defaultUserId, Option(GoogleSubjectId("testGoogleSubject")), WorkbenchEmail("user@foo.com"))

  override protected def beforeEach(): Unit = {
    TestSupport.truncateAll
  }

  "PostgresDirectoryDAO" should "create a group" in {
    dao.createGroup(defaultGroup).unsafeRunSync() shouldEqual defaultGroup
  }

  it should "create a group with access instructions" in {
    dao.createGroup(defaultGroup, Option("access instructions")).unsafeRunSync() shouldEqual defaultGroup
  }

  it should "not allow groups with duplicate names" in {
    val duplicateGroup = BasicWorkbenchGroup(defaultGroupName, Set.empty, WorkbenchEmail("foo@bar.com"))
    dao.createGroup(defaultGroup).unsafeRunSync()
    assertThrows[PSQLException] {
      dao.createGroup(duplicateGroup).unsafeRunSync()
    }
  }

  it should "create groups with subGroup members" in {
    val subGroup1 = defaultGroup
    val subGroup2 = BasicWorkbenchGroup(WorkbenchGroupName("subGroup2"), Set.empty, WorkbenchEmail("bar@baz.com"))
    val members: Set[WorkbenchSubject] = Set(subGroup1.id, subGroup2.id)
    val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parentGroup"), members, WorkbenchEmail("baz@qux.com"))

    dao.createGroup(subGroup1).unsafeRunSync()
    dao.createGroup(subGroup2).unsafeRunSync()
    dao.createGroup(parentGroup).unsafeRunSync()

    val loadedGroup = dao.loadGroup(parentGroup.id).unsafeRunSync().getOrElse(fail(s"Failed to load group ${parentGroup.id}"))
    loadedGroup.members shouldEqual members
  }

  it should "not allow nonexistent group members" in {
    val subGroup1 = defaultGroup
    val subGroup2 = BasicWorkbenchGroup(WorkbenchGroupName("subGroup2"), Set.empty, WorkbenchEmail("bar@baz.com"))
    val members: Set[WorkbenchSubject] = Set(subGroup1.id, subGroup2.id)
    val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parentGroup"), members, WorkbenchEmail("baz@qux.com"))

    assertThrows[WorkbenchExceptionWithErrorReport] {
      dao.createGroup(parentGroup).unsafeRunSync()
    }
  }

  it should "load a group" in {
    dao.createGroup(defaultGroup).unsafeRunSync()
    val loadedGroup = dao.loadGroup(defaultGroup.id).unsafeRunSync().getOrElse(fail(s"Failed to load group $defaultGroupName"))
    loadedGroup shouldEqual defaultGroup
  }

  it should "return None when loading a nonexistent group" in {
    dao.loadGroup(WorkbenchGroupName("fakeGroup")).unsafeRunSync() shouldBe None
  }

  it should "load a group's email" in {
    dao.createGroup(defaultGroup).unsafeRunSync()
    val loadedEmail = dao.loadGroupEmail(defaultGroup.id).unsafeRunSync().getOrElse(fail(s"Failed to load group ${defaultGroup.id}"))
    loadedEmail shouldEqual defaultGroup.email
  }

  it should "return None when trying to load the email for a nonexistent group" in {
    dao.loadGroupEmail(WorkbenchGroupName("fakeGroup")).unsafeRunSync() shouldBe None
  }

  it should "delete groups" in {
    dao.createGroup(defaultGroup).unsafeRunSync()

    val loadedGroup = dao.loadGroup(defaultGroup.id).unsafeRunSync().getOrElse(fail(s"Failed to load group $defaultGroupName"))
    loadedGroup shouldEqual defaultGroup

    dao.deleteGroup(defaultGroup.id).unsafeRunSync()

    dao.loadGroup(defaultGroup.id).unsafeRunSync() shouldBe None
  }

  it should "not delete a group that is still a member of another group" in {
    val subGroup = defaultGroup.copy(id = WorkbenchGroupName("subGroup"))
    val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parentGroup"), Set(subGroup.id), WorkbenchEmail("bar@baz.com"))

    dao.createGroup(subGroup).unsafeRunSync()
    dao.createGroup(parentGroup).unsafeRunSync()

    assertThrows[PSQLException] {
      dao.deleteGroup(subGroup.id).unsafeRunSync()
    }

    dao.loadGroup(subGroup.id).unsafeRunSync() shouldEqual Option(subGroup)
  }

  it should "load multiple groups" in {
    val group1 = BasicWorkbenchGroup(WorkbenchGroupName("group1"), Set.empty, WorkbenchEmail("group1@foo.com"))
    val group2 = BasicWorkbenchGroup(WorkbenchGroupName("group2"), Set.empty, WorkbenchEmail("group2@foo.com"))

    dao.createGroup(group1).unsafeRunSync()
    dao.createGroup(group2).unsafeRunSync()

    dao.loadGroups(Set(group1.id, group2.id)).unsafeRunSync() should contain theSameElementsAs Set(group1, group2)
  }

  it should "handle loading nonexistent groups" in {
    val group1 = BasicWorkbenchGroup(WorkbenchGroupName("group1"), Set.empty, WorkbenchEmail("group1@foo.com"))
    val group2 = BasicWorkbenchGroup(WorkbenchGroupName("group2"), Set.empty, WorkbenchEmail("group2@foo.com"))

    dao.createGroup(group1).unsafeRunSync()
    dao.createGroup(group2).unsafeRunSync()

    dao.loadGroups(Set(group1.id, group2.id, WorkbenchGroupName("fakeGroup"))).unsafeRunSync() should contain theSameElementsAs Set(group1, group2)
  }

  private def emptyWorkbenchGroup(groupName: String): BasicWorkbenchGroup = BasicWorkbenchGroup(WorkbenchGroupName(groupName), Set.empty, WorkbenchEmail(s"$groupName@test.com"))

  it should "add groups to other groups" in {
    val subGroup = emptyWorkbenchGroup("subGroup")
    dao.createGroup(defaultGroup).unsafeRunSync()
    dao.createGroup(subGroup).unsafeRunSync()

    dao.addGroupMember(defaultGroup.id, subGroup.id).unsafeRunSync() shouldBe true

    val loadedGroup = dao.loadGroup(defaultGroup.id).unsafeRunSync().getOrElse(fail(s"failed to load group ${defaultGroup.id}"))
    loadedGroup.members should contain theSameElementsAs Set(subGroup.id)
  }

  // TODO: Pending implementation of adding users to database
  ignore should "add users to groups" in {
    val testUser = WorkbenchUserId("user")
    dao.createGroup(defaultGroup).unsafeRunSync()

    dao.addGroupMember(defaultGroup.id, testUser).unsafeRunSync() shouldBe true

    val loadedGroup = dao.loadGroup(defaultGroup.id).unsafeRunSync().getOrElse(fail(s"failed to load group ${defaultGroup.id}"))
    loadedGroup.members should contain theSameElementsAs Set(testUser)
  }

  // TODO: pending policy creation
  ignore should "add policies to groups" in {

  }

  it should "batch load multiple group emails" in {
    val group1 = emptyWorkbenchGroup("group1")
    val group2 = emptyWorkbenchGroup("group2")

    dao.createGroup(group1).unsafeRunSync()
    dao.createGroup(group2).unsafeRunSync()

    dao.batchLoadGroupEmail(Set(group1.id, group2.id)).unsafeRunSync() should contain theSameElementsAs Set(group1, group2).map(group => (group.id, group.email))
  }

  it should "remove groups from other groups" in {
    val subGroup = emptyWorkbenchGroup("subGroup")
    dao.createGroup(defaultGroup).unsafeRunSync()
    dao.createGroup(subGroup).unsafeRunSync()

    dao.addGroupMember(defaultGroup.id, subGroup.id).unsafeRunSync() shouldBe true
    dao.removeGroupMember(defaultGroup.id, subGroup.id).unsafeRunSync() shouldBe true

    val loadedGroup = dao.loadGroup(defaultGroup.id).unsafeRunSync().getOrElse(fail(s"failed to load group ${defaultGroup.id}"))
    loadedGroup.members shouldBe empty
  }

  ignore should "remove users from other groups" in {

  }

  ignore should "remove policies from other groups" in {

  }

  it should "create and load a user" in {
    dao.createUser(defaultUser).unsafeRunSync() shouldEqual defaultUser
    val loadedUser = dao.loadUser(defaultUser.id).unsafeRunSync().getOrElse(fail(s"failed to load user ${defaultUser.id}"))
    loadedUser shouldEqual defaultUser
  }

  it should "delete users" in {
    dao.createUser(defaultUser).unsafeRunSync() shouldEqual defaultUser
    val loadedUser = dao.loadUser(defaultUser.id).unsafeRunSync().getOrElse(fail(s"failed to load user ${defaultUser.id}"))
    loadedUser shouldEqual defaultUser
    dao.deleteUser(defaultUser.id).unsafeRunSync()
    dao.loadUser(defaultUser.id).unsafeRunSync() shouldBe None
  }
}
