package org.broadinstitute.dsde.workbench.sam.directory

import java.util.{Date, UUID}

import akka.http.scaladsl.model.StatusCodes
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccount, ServiceAccountDisplayName, ServiceAccountSubjectId}
import org.broadinstitute.dsde.workbench.sam.Generator
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.AccessPolicyDAO
import org.postgresql.util.PSQLException
import org.scalatest.{FreeSpec, Matchers}

import scala.concurrent.duration._

trait DirectoryDAOBehaviors extends FreeSpec with Matchers { this: FreeSpec =>
  val defaultGroupName = WorkbenchGroupName("group")
  val defaultGroup = BasicWorkbenchGroup(defaultGroupName, Set.empty, WorkbenchEmail("foo@bar.com"))
  val defaultUserId = WorkbenchUserId("testUser")
  val defaultUser = WorkbenchUser(defaultUserId, Option(GoogleSubjectId("testGoogleSubject")), WorkbenchEmail("user@foo.com"))
  val defaultPetSA = PetServiceAccount(PetServiceAccountId(defaultUser.id, GoogleProject("testProject")), ServiceAccount(ServiceAccountSubjectId("testGoogleSubjectId"), WorkbenchEmail("test@pet.co"), ServiceAccountDisplayName("whoCares")))

  private def emptyWorkbenchGroup(groupName: String): BasicWorkbenchGroup = BasicWorkbenchGroup(WorkbenchGroupName(groupName), Set.empty, WorkbenchEmail(s"$groupName@test.com"))

  def directoryDAO(dao: DirectoryDAO, accessPolicyDAO: AccessPolicyDAO): Unit = {
    "DirectoryDAO" - {
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

      "createUser" - {
        "create a user" in {
          dao.createUser(defaultUser).unsafeRunSync() shouldEqual defaultUser
          val loadedUser = dao.loadUser(defaultUser.id).unsafeRunSync().getOrElse(fail(s"failed to load user ${defaultUser.id}"))
          loadedUser shouldEqual defaultUser
        }
      }

      "loadUser" - {
        "load a user without a google subject id" in {
          dao.createUser(defaultUser.copy(googleSubjectId = None)).unsafeRunSync()
          dao.loadUser(defaultUser.id).unsafeRunSync().map(user => user.googleSubjectId shouldBe None)
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
      }

      "listUsersGroups" - {
        "list all of the groups a user is in" in {
          val subGroupId = WorkbenchGroupName("subGroup")
          val subGroup = BasicWorkbenchGroup(subGroupId, Set(defaultUserId), WorkbenchEmail("subGroup@foo.com"))
          val parentGroupId = WorkbenchGroupName("parentGroup")
          val parentGroup = BasicWorkbenchGroup(parentGroupId, Set(defaultUserId, subGroupId), WorkbenchEmail("parentGroup@foo.com"))

          dao.createUser(defaultUser).unsafeRunSync()
          dao.createGroup(subGroup).unsafeRunSync()
          dao.createGroup(parentGroup).unsafeRunSync()

          val usersGroups = dao.listUsersGroups(defaultUserId).unsafeRunSync()
          usersGroups should contain theSameElementsAs Set(subGroupId, parentGroupId)
        }

        "list all of the policies a user is in" is pending
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
          dao.createUser(defaultUser).unsafeRunSync()
          dao.createPetServiceAccount(defaultPetSA).unsafeRunSync() shouldBe defaultPetSA
        }
      }

      "loadPetServiceAccount" - {
        "load pet service accounts" in {
          dao.createUser(defaultUser).unsafeRunSync()
          dao.createPetServiceAccount(defaultPetSA).unsafeRunSync()

          dao.loadPetServiceAccount(defaultPetSA.id).unsafeRunSync() shouldBe Some(defaultPetSA)
        }

        "return None for nonexistent pet service accounts" in {
          dao.loadPetServiceAccount(defaultPetSA.id).unsafeRunSync() shouldBe None
        }
      }

      "deletePetServiceAccount" - {
        "delete pet service accounts" in {
          dao.createUser(defaultUser).unsafeRunSync()
          dao.createPetServiceAccount(defaultPetSA).unsafeRunSync()

          dao.loadPetServiceAccount(defaultPetSA.id).unsafeRunSync() shouldBe Some(defaultPetSA)

          dao.deletePetServiceAccount(defaultPetSA.id).unsafeRunSync()

          dao.loadPetServiceAccount(defaultPetSA.id).unsafeRunSync() shouldBe None
        }

        "throw an exception when trying to delete a nonexistent pet service account" in {
          assertThrows[WorkbenchException] {
            dao.deletePetServiceAccount(defaultPetSA.id).unsafeRunSync()
          }
        }
      }

      "getAllPetServiceAccountsForUser" - {
        "get all pet service accounts for user" in {
          dao.createUser(defaultUser).unsafeRunSync()

          val petSA1 = PetServiceAccount(PetServiceAccountId(defaultUser.id, GoogleProject("testProject1")), ServiceAccount(ServiceAccountSubjectId("testGoogleSubjectId1"), WorkbenchEmail("test1@pet.co"), ServiceAccountDisplayName("whoCares")))
          dao.createPetServiceAccount(petSA1).unsafeRunSync()

          val petSA2 = PetServiceAccount(PetServiceAccountId(defaultUser.id, GoogleProject("testProject2")), ServiceAccount(ServiceAccountSubjectId("testGoogleSubjectId2"), WorkbenchEmail("test2@pet.co"), ServiceAccountDisplayName("whoCares")))
          dao.createPetServiceAccount(petSA2).unsafeRunSync()

          dao.getAllPetServiceAccountsForUser(defaultUserId).unsafeRunSync() should contain theSameElementsAs Seq(petSA1, petSA2)
        }
      }

      "getUserFromPetServiceAccount" - {
        "get user from pet service account subject ID" in {
          dao.createUser(defaultUser).unsafeRunSync()

          dao.createPetServiceAccount(defaultPetSA).unsafeRunSync()

          dao.getUserFromPetServiceAccount(defaultPetSA.serviceAccount.subjectId).unsafeRunSync() shouldBe Some(defaultUser)
        }
      }

      "updatePetServiceAccount" - {
        "update a pet service account" in {
          dao.createUser(defaultUser).unsafeRunSync()

          dao.createPetServiceAccount(defaultPetSA).unsafeRunSync()

          val updatedPetSA = defaultPetSA.copy(serviceAccount = ServiceAccount(ServiceAccountSubjectId("updatedTestGoogleSubjectId"), WorkbenchEmail("new@pet.co"), ServiceAccountDisplayName("whoCares")))
          dao.updatePetServiceAccount(updatedPetSA).unsafeRunSync() shouldBe updatedPetSA

          dao.loadPetServiceAccount(updatedPetSA.id).unsafeRunSync() shouldBe Some(updatedPetSA)
        }

        "throw an exception when updating a nonexistent pet SA" in {
          dao.createUser(defaultUser).unsafeRunSync()

          val updatedPetSA = defaultPetSA.copy(serviceAccount = ServiceAccount(ServiceAccountSubjectId("updatedTestGoogleSubjectId"), WorkbenchEmail("new@pet.co"), ServiceAccountDisplayName("whoCares")))
          assertThrows[WorkbenchException] {
            dao.updatePetServiceAccount(updatedPetSA).unsafeRunSync() shouldBe updatedPetSA
          }
        }
      }

      "getManagedGroupAccessInstructions" - {
        "get managed group access instructions" in {
          dao.createGroup(defaultGroup).unsafeRunSync()

          dao.getManagedGroupAccessInstructions(defaultGroupName).unsafeRunSync() shouldBe None
        }
      }

      "setManagedGroupAccessInstructions" - {
        "set managed group access instructions" in {
          dao.createGroup(defaultGroup).unsafeRunSync()

          dao.setManagedGroupAccessInstructions(defaultGroupName, "testinstructions").unsafeRunSync()

          dao.getManagedGroupAccessInstructions(defaultGroupName).unsafeRunSync() shouldBe Some("testinstructions")
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

      "enableIdentity and disableIdentity" - {
        "can enable and disable users" in {
          dao.createUser(defaultUser).unsafeRunSync()
          dao.isEnabled(defaultUser.id).unsafeRunSync() shouldBe true

          dao.disableIdentity(defaultUser.id).unsafeRunSync()
          dao.isEnabled(defaultUser.id).unsafeRunSync() shouldBe false

          dao.enableIdentity(defaultUser.id).unsafeRunSync()
          dao.isEnabled(defaultUser.id).unsafeRunSync() shouldBe true
        }

        "cannot enable and disable pet service accounts" in {
          dao.createUser(defaultUser).unsafeRunSync()
          dao.createPetServiceAccount(defaultPetSA).unsafeRunSync()
          val initialEnabledStatus = dao.isEnabled(defaultPetSA.id).unsafeRunSync()

          dao.disableIdentity(defaultPetSA.id).unsafeRunSync()
          dao.isEnabled(defaultPetSA.id).unsafeRunSync() shouldBe initialEnabledStatus

          dao.enableIdentity(defaultPetSA.id).unsafeRunSync()
          dao.isEnabled(defaultPetSA.id).unsafeRunSync() shouldBe initialEnabledStatus
        }

        "cannot enable and disable groups" in {
          dao.createGroup(defaultGroup).unsafeRunSync()
          val initialEnabledStatus = dao.isEnabled(defaultGroup.id).unsafeRunSync()

          dao.disableIdentity(defaultGroup.id).unsafeRunSync()
          dao.isEnabled(defaultGroup.id).unsafeRunSync() shouldBe initialEnabledStatus

          dao.enableIdentity(defaultGroup.id).unsafeRunSync()
          dao.isEnabled(defaultGroup.id).unsafeRunSync() shouldBe initialEnabledStatus
        }

        "cannot enable and disable policies" is pending
      }

      "isEnabled" - {
        "gets a user's enabled status" in {
          dao.createUser(defaultUser).unsafeRunSync()

          dao.disableIdentity(defaultUser.id).unsafeRunSync()
          dao.isEnabled(defaultUser.id).unsafeRunSync() shouldBe false

          dao.enableIdentity(defaultUser.id).unsafeRunSync()
          dao.isEnabled(defaultUser.id).unsafeRunSync() shouldBe true
        }

        "gets a pet's user's enabled status" in {
          dao.createUser(defaultUser).unsafeRunSync()
          dao.createPetServiceAccount(defaultPetSA).unsafeRunSync()

          dao.disableIdentity(defaultUser.id).unsafeRunSync()
          dao.isEnabled(defaultPetSA.id).unsafeRunSync() shouldBe false

          dao.enableIdentity(defaultUser.id).unsafeRunSync()
          dao.isEnabled(defaultPetSA.id).unsafeRunSync() shouldBe true
        }

        "returns false for groups" in {
          dao.createGroup(defaultGroup).unsafeRunSync()

          dao.isEnabled(defaultGroup.id).unsafeRunSync() shouldBe false
          dao.enableIdentity(defaultGroup.id).unsafeRunSync()
          dao.isEnabled(defaultGroup.id).unsafeRunSync() shouldBe false
        }

        "returns false for policies" is pending
      }

      "listUserDirectMemberships" - {
        "lists all groups that a user is in directly" in {
          val subSubGroup = BasicWorkbenchGroup(WorkbenchGroupName("ssg"), Set(defaultUser.id), WorkbenchEmail("ssg@groups.r.us"))
          val subGroup = BasicWorkbenchGroup(WorkbenchGroupName("sg"), Set(defaultUser.id, subSubGroup.id), WorkbenchEmail("sg@groups.r.us"))
          val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("pg"), Set(subGroup.id), WorkbenchEmail("pg@groups.r.us"))

          dao.createUser(defaultUser).unsafeRunSync()
          dao.createGroup(subSubGroup).unsafeRunSync()
          dao.createGroup(subGroup).unsafeRunSync()
          dao.createGroup(parentGroup).unsafeRunSync()

          dao.listUserDirectMemberships(defaultUser.id).unsafeRunSync() should contain theSameElementsAs Set(subGroup.id, subSubGroup.id)
        }

        "lists all policies that a user is in directly" is pending
      }

      "listAncestorGroups" - {
        "list all of the groups a group is in" in {
          val subSubGroup = BasicWorkbenchGroup(WorkbenchGroupName("ssg"), Set.empty, WorkbenchEmail("ssg@groups.r.us"))
          val subGroup = BasicWorkbenchGroup(WorkbenchGroupName("sg"), Set(subSubGroup.id), WorkbenchEmail("sg@groups.r.us"))
          val directParentGroup = BasicWorkbenchGroup(WorkbenchGroupName("dpg"), Set(subGroup.id, subSubGroup.id), WorkbenchEmail("dpg@groups.r.us"))
          val indirectParentGroup = BasicWorkbenchGroup(WorkbenchGroupName("ipg"), Set(subGroup.id), WorkbenchEmail("ipg@groups.r.us"))

          dao.createGroup(subSubGroup).unsafeRunSync()
          dao.createGroup(subGroup).unsafeRunSync()
          dao.createGroup(directParentGroup).unsafeRunSync()
          dao.createGroup(indirectParentGroup).unsafeRunSync()

          val ancestorGroups = dao.listAncestorGroups(subSubGroup.id).unsafeRunSync()
          ancestorGroups should contain theSameElementsAs Set(subGroup.id, directParentGroup.id, indirectParentGroup.id)
        }

        "list all of the policies a group is in" is pending
        "list all of the groups a policy is in" is pending
        "list all of the policies a policy is in" is pending
      }

      "getSynchronizedEmail" - {
        "load the email for a group" in {
          dao.createGroup(defaultGroup).unsafeRunSync()

          dao.getSynchronizedEmail(defaultGroup.id).unsafeRunSync() shouldEqual Option(defaultGroup.email)
        }

        "load the email for a policy" is pending
      }

      "getSynchronizedDate" - {
        "load the synchronized date for a group" in {
          dao.createGroup(defaultGroup).unsafeRunSync()

          dao.updateSynchronizedDate(defaultGroup.id).unsafeRunSync()

          val loadedDate = dao.getSynchronizedDate(defaultGroup.id).unsafeRunSync().getOrElse(fail("failed to load date"))
          loadedDate.getTime() should equal (new Date().getTime +- 2.seconds.toMillis)
        }

        "load the synchronized date for a policy" is pending
      }

      "setGoogleSubjectId" - {
        "update the googleSubjectId for a user" in {
          val newGoogleSubjectId = GoogleSubjectId("newGoogleSubjectId")
          dao.createUser(defaultUser.copy(googleSubjectId = None)).unsafeRunSync()

          dao.loadUser(defaultUser.id).unsafeRunSync().flatMap(_.googleSubjectId) shouldBe None
          dao.setGoogleSubjectId(defaultUser.id, newGoogleSubjectId).unsafeRunSync()

          dao.loadUser(defaultUser.id).unsafeRunSync().flatMap(_.googleSubjectId) shouldBe Option(newGoogleSubjectId)
        }
      }

      "LdapTests" - {
        "create, read, delete groups" in {
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

        "create, read, delete users" in {
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

        "create, read, delete pet service accounts" in {
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
            dao.getAllPetServiceAccountsForUser(userId).unsafeRunSync()
          }

          assertResult(petServiceAccount) {
            dao.createPetServiceAccount(petServiceAccount).unsafeRunSync()
          }

          assertResult(Some(petServiceAccount)) {
            dao.loadPetServiceAccount(petServiceAccount.id).unsafeRunSync()
          }

          assertResult(Seq(petServiceAccount)) {
            dao.getAllPetServiceAccountsForUser(userId).unsafeRunSync()
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
            dao.getAllPetServiceAccountsForUser(userId).unsafeRunSync()
          }
        }

        "list groups" in {
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
            dao.deleteUser(userId).unsafeRunSync()
            dao.deleteGroup(groupName2).unsafeRunSync()
            dao.deleteGroup(groupName1).unsafeRunSync()
          }
        }

        "list group ancestors" in {
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

        "handle circular groups" in {
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
            dao.deleteUser(userId).unsafeRunSync()
            dao.removeGroupMember(groupName1, groupName3).unsafeRunSync()
            dao.deleteGroup(groupName3).unsafeRunSync()
            dao.deleteGroup(groupName2).unsafeRunSync()
            dao.deleteGroup(groupName1).unsafeRunSync()
          }
        }

        "add/remove groups" in {
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
            dao.deleteUser(userId).unsafeRunSync()
            dao.deleteGroup(groupName1).unsafeRunSync()
            dao.deleteGroup(groupName2).unsafeRunSync()
          }
        }

        "handle different kinds of groups" ignore {
          val userId = WorkbenchUserId(UUID.randomUUID().toString)
          val user = WorkbenchUser(userId, None, WorkbenchEmail("foo@bar.com"))

          val groupName1 = WorkbenchGroupName(UUID.randomUUID().toString)
          val group1 = BasicWorkbenchGroup(groupName1, Set(userId), WorkbenchEmail("g1@example.com"))

          val groupName2 = WorkbenchGroupName(UUID.randomUUID().toString)
          val group2 = BasicWorkbenchGroup(groupName2, Set.empty, WorkbenchEmail("g2@example.com"))

          dao.createUser(user).unsafeRunSync()
          dao.createGroup(group1).unsafeRunSync()
          dao.createGroup(group2).unsafeRunSync()

          val typeName1 = ResourceTypeName(UUID.randomUUID().toString)

          val resourceType = ResourceType(ResourceTypeName(UUID.randomUUID().toString), Set.empty, Set(ResourceRole(ResourceRoleName("owner"), Set.empty)), ResourceRoleName("owner"))

          val resource = Resource(typeName1, ResourceId("resource"), Set.empty)
          val policy1 = AccessPolicy(
            FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("role1-a")), Set(userId), WorkbenchEmail("p1@example.com"), Set(ResourceRoleName("role1")), Set(ResourceAction("action1"), ResourceAction("action2")), public = false)

          accessPolicyDAO.createResourceType(resourceType).unsafeRunSync()
          accessPolicyDAO.createResource(resource).unsafeRunSync()
          accessPolicyDAO.createPolicy(policy1).unsafeRunSync()

          assert(dao.isGroupMember(group1.id, userId).unsafeRunSync())
          assert(!dao.isGroupMember(group2.id, userId).unsafeRunSync())
          assert(dao.isGroupMember(policy1.id, userId).unsafeRunSync())
        }

        "be case insensitive when checking for group membership" in {
          val userId = WorkbenchUserId(UUID.randomUUID().toString)
          val user = WorkbenchUser(userId, None, WorkbenchEmail("foo@bar.com"))

          val groupName1 = WorkbenchGroupName(UUID.randomUUID().toString)
          val group1 = BasicWorkbenchGroup(groupName1, Set(userId), WorkbenchEmail("g1@example.com"))

          dao.createUser(user).unsafeRunSync()
          dao.createGroup(group1).unsafeRunSync()

          assert(dao.isGroupMember(WorkbenchGroupName(group1.id.value.toUpperCase), userId).unsafeRunSync())
        }

        "get pet for user" in {
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

        "safeDelete" - {
          "prevent deleting groups that are sub-groups of other groups" in {
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
        }

        "loadSubjectEmail" - {
          "fail if the user has not been created" ignore {
            val userId = WorkbenchUserId(UUID.randomUUID().toString)
            val user = WorkbenchUser(userId, None, WorkbenchEmail("foo@bar.com"))

            assertResult(None) {
              dao.loadUser(user.id).unsafeRunSync()
            }

            dao.loadSubjectEmail(userId).unsafeRunSync() shouldEqual None
          }

          "succeed if the user has been created" ignore {
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
        }

        "loadSubjectFromEmail" - {
          "be able to load subject given an email" ignore {
            val user1 = Generator.genWorkbenchUser.sample.get
            val user2 = user1.copy(email = WorkbenchEmail(user1.email.value + "2"))
            val res = for {
              _ <- dao.createUser(user1)
              subject1 <- dao.loadSubjectFromEmail(user1.email)
              subject2 <- dao.loadSubjectFromEmail(user2.email)
            } yield {
              subject1 shouldEqual (Some(user1.id))
              subject2 shouldEqual (None)
            }
            res.unsafeRunSync()
          }
        }

        "loadSubjectEmail" - {
          "be able to load a subject's email" ignore {
            val user1 = Generator.genWorkbenchUser.sample.get
            val user2 = user1.copy(id = WorkbenchUserId(user1.id.value + "2"))
            val res = for {
              _ <- dao.createUser(user1)
              email1 <- dao.loadSubjectEmail(user1.id)
              email2 <- dao.loadSubjectEmail(user2.id)
            } yield {
              email1 shouldEqual (Some(user1.email))
              email2 shouldEqual (None)
            }
            res.unsafeRunSync()
          }
        }

        "loadSubjectFromGoogleSubjectId" - {
          "be able to load subject given an google subject Id" ignore {
            val user = Generator.genWorkbenchUser.sample.get
            val user1 = user.copy(googleSubjectId = Some(GoogleSubjectId(user.id.value)))
            val user2 = user1.copy(googleSubjectId = Some(GoogleSubjectId(user.id.value + "2")))

            val res = for {
              _ <- dao.createUser(user1)
              subject1 <- dao.loadSubjectFromGoogleSubjectId(user1.googleSubjectId.get)
              subject2 <- dao.loadSubjectFromGoogleSubjectId(user2.googleSubjectId.get)
            } yield {
              subject1 shouldEqual (Some(user1.id))
              subject2 shouldEqual (None)
            }
            res.unsafeRunSync()
          }
        }
      }
    }
  }
}
