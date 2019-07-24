package org.broadinstitute.dsde.workbench.sam.directory

import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccount, ServiceAccountDisplayName, ServiceAccountSubjectId}
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup
import org.postgresql.util.PSQLException
import org.scalatest.{BeforeAndAfterEach, FreeSpec, Matchers}

import scala.concurrent.ExecutionContext.Implicits.global

class PostgresDirectoryDAOSpec extends FreeSpec with Matchers with BeforeAndAfterEach {
  val dao = new PostgresDirectoryDAO(TestSupport.dbRef, TestSupport.blockingEc)

  val defaultGroupName = WorkbenchGroupName("group")
  val defaultGroup = BasicWorkbenchGroup(defaultGroupName, Set.empty, WorkbenchEmail("foo@bar.com"))
  val defaultUserId = WorkbenchUserId("testUser")
  val defaultUser = WorkbenchUser(defaultUserId, Option(GoogleSubjectId("testGoogleSubject")), WorkbenchEmail("user@foo.com"))

  override protected def beforeEach(): Unit = {
    TestSupport.truncateAll
  }

  private def emptyWorkbenchGroup(groupName: String): BasicWorkbenchGroup = BasicWorkbenchGroup(WorkbenchGroupName(groupName), Set.empty, WorkbenchEmail(s"$groupName@test.com"))

  "PostgresDirectoryDAO" - {
    "createGroup" - {
      "create a group" in {
        dao.createGroup(defaultGroup).unsafeRunSync() shouldEqual defaultGroup
      }

      "create a group with access instructions" in {
        dao.createGroup(defaultGroup, Option("access instructions")).unsafeRunSync() shouldEqual defaultGroup
      }

      "not allow groups with duplicate names" in {
        val duplicateGroup = BasicWorkbenchGroup(defaultGroupName, Set.empty, WorkbenchEmail("foo@bar.com"))
        dao.createGroup(defaultGroup).unsafeRunSync()
        assertThrows[PSQLException] {
          dao.createGroup(duplicateGroup).unsafeRunSync()
        }
      }

      "create groups with subGroup members" in {
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

      "not allow nonexistent group members" in {
        val subGroup1 = defaultGroup
        val subGroup2 = BasicWorkbenchGroup(WorkbenchGroupName("subGroup2"), Set.empty, WorkbenchEmail("bar@baz.com"))
        val members: Set[WorkbenchSubject] = Set(subGroup1.id, subGroup2.id)
        val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parentGroup"), members, WorkbenchEmail("baz@qux.com"))

        assertThrows[WorkbenchExceptionWithErrorReport] {
          dao.createGroup(parentGroup).unsafeRunSync()
        }
      }
    }

    "loadGroup" - {
        "load a group" in {
        dao.createGroup(defaultGroup).unsafeRunSync()
        val loadedGroup = dao.loadGroup(defaultGroup.id).unsafeRunSync().getOrElse(fail(s"Failed to load group $defaultGroupName"))
        loadedGroup shouldEqual defaultGroup
      }

      "return None when loading a nonexistent group" in {
        dao.loadGroup(WorkbenchGroupName("fakeGroup")).unsafeRunSync() shouldBe None
      }
    }

    "loadGroupEmail" - {
      "load a group's email" in {
        dao.createGroup(defaultGroup).unsafeRunSync()
        val loadedEmail = dao.loadGroupEmail(defaultGroup.id).unsafeRunSync().getOrElse(fail(s"Failed to load group ${defaultGroup.id}"))
        loadedEmail shouldEqual defaultGroup.email
      }

      "return None when trying to load the email for a nonexistent group" in {
        dao.loadGroupEmail(WorkbenchGroupName("fakeGroup")).unsafeRunSync() shouldBe None
      }
    }

    "deleteGroup" - {
      "delete groups" in {
        dao.createGroup(defaultGroup).unsafeRunSync()

        val loadedGroup = dao.loadGroup(defaultGroup.id).unsafeRunSync().getOrElse(fail(s"Failed to load group $defaultGroupName"))
        loadedGroup shouldEqual defaultGroup

        dao.deleteGroup(defaultGroup.id).unsafeRunSync()

        dao.loadGroup(defaultGroup.id).unsafeRunSync() shouldBe None
      }

      "not delete a group that is still a member of another group" in {
        val subGroup = defaultGroup.copy(id = WorkbenchGroupName("subGroup"))
        val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parentGroup"), Set(subGroup.id), WorkbenchEmail("bar@baz.com"))

        dao.createGroup(subGroup).unsafeRunSync()
        dao.createGroup(parentGroup).unsafeRunSync()

        assertThrows[PSQLException] {
          dao.deleteGroup(subGroup.id).unsafeRunSync()
        }

        dao.loadGroup(subGroup.id).unsafeRunSync() shouldEqual Option(subGroup)
      }
    }

    "loadGroups" - {
      "load multiple groups" in {
        val group1 = BasicWorkbenchGroup(WorkbenchGroupName("group1"), Set.empty, WorkbenchEmail("group1@foo.com"))
        val group2 = BasicWorkbenchGroup(WorkbenchGroupName("group2"), Set.empty, WorkbenchEmail("group2@foo.com"))

        dao.createGroup(group1).unsafeRunSync()
        dao.createGroup(group2).unsafeRunSync()

        dao.loadGroups(Set(group1.id, group2.id)).unsafeRunSync() should contain theSameElementsAs Set(group1, group2)
      }

      "handle loading nonexistent groups" in {
        val group1 = BasicWorkbenchGroup(WorkbenchGroupName("group1"), Set.empty, WorkbenchEmail("group1@foo.com"))
        val group2 = BasicWorkbenchGroup(WorkbenchGroupName("group2"), Set.empty, WorkbenchEmail("group2@foo.com"))

        dao.createGroup(group1).unsafeRunSync()
        dao.createGroup(group2).unsafeRunSync()

        dao.loadGroups(Set(group1.id, group2.id, WorkbenchGroupName("fakeGroup"))).unsafeRunSync() should contain theSameElementsAs Set(group1, group2)
      }
    }

    "addGroupMember" - {
      "add groups to other groups" in {
        val subGroup = emptyWorkbenchGroup("subGroup")
        dao.createGroup(defaultGroup).unsafeRunSync()
        dao.createGroup(subGroup).unsafeRunSync()

        dao.addGroupMember(defaultGroup.id, subGroup.id).unsafeRunSync() shouldBe true

        val loadedGroup = dao.loadGroup(defaultGroup.id).unsafeRunSync().getOrElse(fail(s"failed to load group ${defaultGroup.id}"))
        loadedGroup.members should contain theSameElementsAs Set(subGroup.id)
      }

      "add users to groups" in {
        dao.createGroup(defaultGroup).unsafeRunSync()
        dao.createUser(defaultUser).unsafeRunSync()

        dao.addGroupMember(defaultGroup.id, defaultUser.id).unsafeRunSync() shouldBe true

        val loadedGroup = dao.loadGroup(defaultGroup.id).unsafeRunSync().getOrElse(fail(s"failed to load group ${defaultGroup.id}"))
        loadedGroup.members should contain theSameElementsAs Set(defaultUser.id)
      }

      "add policies to groups" is pending
      "add groups to policies" is pending
      "add users to policies" is pending
      "add policies to other policies" is pending
    }

    "batchLoadGroupEmail" - {
      "batch load multiple group emails" in {
        val group1 = emptyWorkbenchGroup("group1")
        val group2 = emptyWorkbenchGroup("group2")

        dao.createGroup(group1).unsafeRunSync()
        dao.createGroup(group2).unsafeRunSync()

        dao.batchLoadGroupEmail(Set(group1.id, group2.id)).unsafeRunSync() should contain theSameElementsAs Set(group1, group2).map(group => (group.id, group.email))
      }
    }
  
    "removeGroupMember" - {
      "remove groups from other groups" in {
        val subGroup = emptyWorkbenchGroup("subGroup")
        dao.createGroup(defaultGroup).unsafeRunSync()
        dao.createGroup(subGroup).unsafeRunSync()

        dao.addGroupMember(defaultGroup.id, subGroup.id).unsafeRunSync() shouldBe true
        dao.removeGroupMember(defaultGroup.id, subGroup.id).unsafeRunSync() shouldBe true

        val loadedGroup = dao.loadGroup(defaultGroup.id).unsafeRunSync().getOrElse(fail(s"failed to load group ${defaultGroup.id}"))
        loadedGroup.members shouldBe empty
      }

      "remove users from groups" in {
        dao.createGroup(defaultGroup).unsafeRunSync()
        dao.createUser(defaultUser).unsafeRunSync()

        dao.addGroupMember(defaultGroup.id, defaultUser.id).unsafeRunSync() shouldBe true
        dao.removeGroupMember(defaultGroup.id, defaultUser.id).unsafeRunSync() shouldBe true

        val loadedGroup = dao.loadGroup(defaultGroup.id).unsafeRunSync().getOrElse(fail(s"failed to load group ${defaultGroup.id}"))
        loadedGroup.members shouldBe empty
      }

      "remove policies from groups" is pending
      "remove groups from policies" is pending
      "remove users from policies" is pending
      "remove policies from other policies" is pending
    }
  
    "createUser and loadUser" - {
      "create and load a user" in {
        dao.createUser(defaultUser).unsafeRunSync() shouldEqual defaultUser
        val loadedUser = dao.loadUser(defaultUser.id).unsafeRunSync().getOrElse(fail(s"failed to load user ${defaultUser.id}"))
        loadedUser shouldEqual defaultUser
      }
    }
  
    "deleteUser" - {
      "delete users" in {
        dao.createUser(defaultUser).unsafeRunSync() shouldEqual defaultUser
        val loadedUser = dao.loadUser(defaultUser.id).unsafeRunSync().getOrElse(fail(s"failed to load user ${defaultUser.id}"))
        loadedUser shouldEqual defaultUser
        dao.deleteUser(defaultUser.id).unsafeRunSync()
        dao.loadUser(defaultUser.id).unsafeRunSync() shouldBe None
      }

      "not delete a user that is still a member of a group" in {
        val user = defaultUser
        val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parentGroup"), Set(user.id), WorkbenchEmail("bar@baz.com"))

        dao.createUser(user).unsafeRunSync()
        dao.createGroup(parentGroup).unsafeRunSync()

        assertThrows[PSQLException] {
          dao.deleteUser(user.id).unsafeRunSync()
        }

        dao.loadUser(user.id).unsafeRunSync() shouldEqual Option(user)
      }

      "not delete a user's pet SA when the user is deleted" is pending
    }

    "loadUsers" - {
      "load multiple users at once" in {
        val user1 = defaultUser
        val user2 = WorkbenchUser(WorkbenchUserId("testUser2"), Option(GoogleSubjectId("testGoogleSubjectId2")), WorkbenchEmail("user2@test.com"))

        dao.createUser(user1).unsafeRunSync() shouldEqual user1
        dao.createUser(user2).unsafeRunSync() shouldEqual user2

        dao.loadUsers(Set(user1.id, user2.id)).unsafeRunSync() should contain theSameElementsAs Set(user1, user2)
      }
    }
  
    "createPetServiceAccount" - {
      "create pet service accounts" in {
        val petSA = PetServiceAccount(PetServiceAccountId(defaultUser.id, GoogleProject("testProject")), ServiceAccount(ServiceAccountSubjectId("testGoogleSubjectId"), WorkbenchEmail("test@pet.co"), ServiceAccountDisplayName("whoCares")))
        dao.createUser(defaultUser).unsafeRunSync()
        dao.createPetServiceAccount(petSA).unsafeRunSync() shouldBe petSA
      }
    }
  
    "isGroupMember" - {
      "return true when member is in sub group" in {
        val subGroup1 = defaultGroup
        val subGroup2 = BasicWorkbenchGroup(WorkbenchGroupName("subGroup2"), Set(subGroup1.id), WorkbenchEmail("bar@baz.com"))
        val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parentGroup"), Set(subGroup2.id), WorkbenchEmail("baz@qux.com"))

        dao.createGroup(subGroup1).unsafeRunSync()
        dao.createGroup(subGroup2).unsafeRunSync()
        dao.createGroup(parentGroup).unsafeRunSync()

        dao.isGroupMember(parentGroup.id, subGroup1.id).unsafeRunSync() should be (true)
      }

      "return false when member is not in sub group" in {
        val subGroup1 = defaultGroup
        val subGroup2 = BasicWorkbenchGroup(WorkbenchGroupName("subGroup2"), Set(subGroup1.id), WorkbenchEmail("bar@baz.com"))
        val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parentGroup"), Set.empty, WorkbenchEmail("baz@qux.com"))

        dao.createGroup(subGroup1).unsafeRunSync()
        dao.createGroup(subGroup2).unsafeRunSync()
        dao.createGroup(parentGroup).unsafeRunSync()

        dao.isGroupMember(parentGroup.id, subGroup1.id).unsafeRunSync() should be (false)
      }

      "return true when user is in sub group" is pending
      "return false when user is not in sub group" is pending
      "return true when user is in policy" is pending
      "return false when user is not in policy" is pending
      "return true when policy is in policy" is pending
      "return false when policy is not in policy" is pending
      "return true when policy is in group" is pending
      "return false when policy is not in group" is pending
      "return true when group is in policy" is pending
      "return false when group is not in policy" is pending
    }

    "listIntersectionGroupUsers" - {
      // DV: I have tried this up to 100 groups to intersect locally with no functional issue, performance seems linear
      for (groupCount <- 1 to 3) {
        s"intersect $groupCount groups" in {
          val inAllGroups = WorkbenchUser(WorkbenchUserId("allgroups"), None, WorkbenchEmail("allgroups"))
          dao.createUser(inAllGroups).unsafeRunSync()

          val allGroups = for (i <- 1 to groupCount) yield {
            // create a group with 1 user and 1 subgroup, subgroup with "allgroups" users and another user
            val userInGroup = WorkbenchUser(WorkbenchUserId(s"ingroup$i"), None, WorkbenchEmail(s"ingroup$i"))
            val userInSubGroup = WorkbenchUser(WorkbenchUserId(s"insubgroup$i"), None, WorkbenchEmail(s"insubgroup$i"))
            val subGroup = BasicWorkbenchGroup(WorkbenchGroupName(s"subgroup$i"), Set(inAllGroups.id, userInSubGroup.id), WorkbenchEmail(s"subgroup$i"))
            val group = BasicWorkbenchGroup(WorkbenchGroupName(s"group$i"), Set(userInGroup.id, subGroup.id), WorkbenchEmail(s"group$i"))
            dao.createUser(userInSubGroup).unsafeRunSync()
            dao.createUser(userInGroup).unsafeRunSync()
            dao.createGroup(subGroup).unsafeRunSync()
            dao.createGroup(group).unsafeRunSync()
          }

          val expected = if (groupCount == 1) Set(WorkbenchUserId("allgroups"), WorkbenchUserId("ingroup1"), WorkbenchUserId("insubgroup1")) else Set(inAllGroups.id)

          dao.listIntersectionGroupUsers(allGroups.map(_.id).toSet).unsafeRunSync() should contain theSameElementsAs expected
        }
      }
    }
  }
}
