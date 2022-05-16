package org.broadinstitute.dsde.workbench.sam.dataAccess

import akka.http.scaladsl.model.StatusCodes
import cats.effect.unsafe.implicits.global
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccount, ServiceAccountDisplayName, ServiceAccountSubjectId}
import org.broadinstitute.dsde.workbench.sam.{Generator, TestSupport}
import org.broadinstitute.dsde.workbench.sam.TestSupport.samRequestContext
import org.broadinstitute.dsde.workbench.sam.model._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.util.Date
import scala.concurrent.duration._

class PostgresDirectoryDAOSpec extends AnyFreeSpec with Matchers with BeforeAndAfterEach {
  val dao = new PostgresDirectoryDAO(TestSupport.dbRef, TestSupport.dbRef)
  val policyDAO = new PostgresAccessPolicyDAO(TestSupport.dbRef, TestSupport.dbRef)

  val defaultGroupName = WorkbenchGroupName("group")
  val defaultGroup = BasicWorkbenchGroup(defaultGroupName, Set.empty, WorkbenchEmail("foo@bar.com"))
  val defaultUser = Generator.genWorkbenchUserBoth.sample.get
  val defaultPetSA = PetServiceAccount(PetServiceAccountId(defaultUser.id, GoogleProject("testProject")), ServiceAccount(ServiceAccountSubjectId("testGoogleSubjectId"), WorkbenchEmail("test@pet.co"), ServiceAccountDisplayName("whoCares")))

  val actionPatterns = Set(ResourceActionPattern("write", "description of pattern1", false),
    ResourceActionPattern("read", "description of pattern2", false))
  val writeAction = ResourceAction("write")
  val readAction = ResourceAction("read")

  val ownerRoleName = ResourceRoleName("role1")
  val ownerRole = ResourceRole(ownerRoleName, Set(writeAction, readAction))
  val readerRole = ResourceRole(ResourceRoleName("role2"), Set(readAction))
  val actionlessRole = ResourceRole(ResourceRoleName("cantDoNuthin"), Set()) // yeah, it's a double negative, sue me!
  val roles = Set(ownerRole, readerRole, actionlessRole)

  val resourceTypeName = ResourceTypeName("awesomeType")
  val resourceType = ResourceType(resourceTypeName, actionPatterns, roles, ownerRoleName, false)
  val defaultResource = Resource(resourceType.name, ResourceId("defaultResource"), Set.empty)
  val defaultPolicy = AccessPolicy(FullyQualifiedPolicyId(defaultResource.fullyQualifiedId, AccessPolicyName("defaultPolicy")), Set.empty, WorkbenchEmail("default@policy.com"), roles.map(_.roleName), Set(writeAction, readAction), Set.empty, false)

  override protected def beforeEach(): Unit = {
    TestSupport.truncateAll
  }

  private def emptyWorkbenchGroup(groupName: String): BasicWorkbenchGroup = BasicWorkbenchGroup(WorkbenchGroupName(groupName), Set.empty, WorkbenchEmail(s"$groupName@test.com"))

  "PostgresDirectoryDAO" - {
    "createGroup" - {
      "create a group" in {
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync() shouldEqual defaultGroup
      }

      "create a group with access instructions" in {
        dao.createGroup(defaultGroup, Option("access instructions"), samRequestContext = samRequestContext).unsafeRunSync() shouldEqual defaultGroup
      }

      "not allow groups with duplicate names" in {
        val duplicateGroup = BasicWorkbenchGroup(defaultGroupName, Set.empty, WorkbenchEmail("foo@bar.com"))
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()
        val exception = intercept[WorkbenchExceptionWithErrorReport] {
          dao.createGroup(duplicateGroup, samRequestContext = samRequestContext).unsafeRunSync()
        }

        exception.errorReport.statusCode shouldEqual Some(StatusCodes.Conflict)
      }

      "create groups with subGroup members" in {
        val subGroup1 = defaultGroup
        val subGroup2 = BasicWorkbenchGroup(WorkbenchGroupName("subGroup2"), Set.empty, WorkbenchEmail("bar@baz.com"))
        val members: Set[WorkbenchSubject] = Set(subGroup1.id, subGroup2.id)
        val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parentGroup"), members, WorkbenchEmail("baz@qux.com"))

        dao.createGroup(subGroup1, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(subGroup2, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(parentGroup, samRequestContext = samRequestContext).unsafeRunSync()

        val loadedGroup = dao.loadGroup(parentGroup.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"Failed to load group ${parentGroup.id}"))
        loadedGroup.members shouldEqual members
      }

      "create groups with policy members" in {
        val memberPolicy = defaultPolicy
        val members: Set[WorkbenchSubject] = Set(memberPolicy.id)
        val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parentGroup"), members, WorkbenchEmail("baz@qux.com"))

        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(defaultPolicy, samRequestContext).unsafeRunSync()
        dao.createGroup(parentGroup, samRequestContext = samRequestContext).unsafeRunSync()

        val loadedGroup = dao.loadGroup(parentGroup.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"Failed to load group ${parentGroup.id}"))
        loadedGroup.members shouldEqual members
      }

      "create groups with both subGroup and policy members" in {
        val subGroup = defaultGroup
        dao.createGroup(subGroup, samRequestContext = samRequestContext).unsafeRunSync()

        val memberPolicy = defaultPolicy
        val members: Set[WorkbenchSubject] = Set(memberPolicy.id, subGroup.id)
        val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parentGroup"), members, WorkbenchEmail("baz@qux.com"))

        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(defaultPolicy, samRequestContext).unsafeRunSync()
        dao.createGroup(parentGroup, samRequestContext = samRequestContext).unsafeRunSync()

        val loadedGroup = dao.loadGroup(parentGroup.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"Failed to load group ${parentGroup.id}"))
        loadedGroup.members shouldEqual members
      }

      "not allow nonexistent group members" in {
        val subGroup1 = defaultGroup
        val subGroup2 = BasicWorkbenchGroup(WorkbenchGroupName("subGroup2"), Set.empty, WorkbenchEmail("bar@baz.com"))
        val members: Set[WorkbenchSubject] = Set(subGroup1.id, subGroup2.id)
        val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parentGroup"), members, WorkbenchEmail("baz@qux.com"))

        assertThrows[WorkbenchException] {
          dao.createGroup(parentGroup, samRequestContext = samRequestContext).unsafeRunSync()
        }
      }
    }

    "loadGroup" - {
        "load a group" in {
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()
        val loadedGroup = dao.loadGroup(defaultGroup.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"Failed to load group $defaultGroupName"))
        loadedGroup shouldEqual defaultGroup
      }

      "return None when loading a nonexistent group" in {
        dao.loadGroup(WorkbenchGroupName("fakeGroup"), samRequestContext).unsafeRunSync() shouldBe None
      }
    }

    "loadGroupEmail" - {
      "load a group's email" in {
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()
        val loadedEmail = dao.loadGroupEmail(defaultGroup.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"Failed to load group ${defaultGroup.id}"))
        loadedEmail shouldEqual defaultGroup.email
      }

      "return None when trying to load the email for a nonexistent group" in {
        dao.loadGroupEmail(WorkbenchGroupName("fakeGroup"), samRequestContext).unsafeRunSync() shouldBe None
      }
    }

    "deleteGroup" - {
      "delete groups" in {
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()

        val loadedGroup = dao.loadGroup(defaultGroup.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"Failed to load group $defaultGroupName"))
        loadedGroup shouldEqual defaultGroup

        dao.deleteGroup(defaultGroup.id, samRequestContext).unsafeRunSync()

        dao.loadGroup(defaultGroup.id, samRequestContext).unsafeRunSync() shouldBe None
      }

      "not delete a group that is still a member of another group" in {
        val subGroup = defaultGroup.copy(id = WorkbenchGroupName("subGroup"))
        val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parentGroup"), Set(subGroup.id), WorkbenchEmail("bar@baz.com"))

        dao.createGroup(subGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(parentGroup, samRequestContext = samRequestContext).unsafeRunSync()

        val inUseException = intercept[WorkbenchExceptionWithErrorReport] {
          dao.deleteGroup(subGroup.id, samRequestContext).unsafeRunSync()
        }

        inUseException.errorReport.statusCode shouldEqual Some(StatusCodes.Conflict)

        dao.loadGroup(subGroup.id, samRequestContext).unsafeRunSync() shouldEqual Option(subGroup)
      }
    }

    "addGroupMember" - {
      "add groups to other groups" in {
        val subGroup = emptyWorkbenchGroup("subGroup")
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(subGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.addGroupMember(defaultGroup.id, subGroup.id, samRequestContext).unsafeRunSync() shouldBe true

        val loadedGroup = dao.loadGroup(defaultGroup.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"failed to load group ${defaultGroup.id}"))
        loadedGroup.members should contain theSameElementsAs Set(subGroup.id)
      }

      "add users to groups" in {
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()

        dao.addGroupMember(defaultGroup.id, defaultUser.id, samRequestContext).unsafeRunSync() shouldBe true

        val loadedGroup = dao.loadGroup(defaultGroup.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"failed to load group ${defaultGroup.id}"))
        loadedGroup.members should contain theSameElementsAs Set(defaultUser.id)
      }

      "add policies to groups" in {
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()
        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(defaultPolicy, samRequestContext).unsafeRunSync()

        dao.addGroupMember(defaultGroup.id, defaultPolicy.id, samRequestContext).unsafeRunSync() shouldBe true

        val loadedGroup = dao.loadGroup(defaultGroup.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"failed to load group ${defaultGroup.id}"))
        loadedGroup.members should contain theSameElementsAs Set(defaultPolicy.id)
      }

      "add groups to policies" in {
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()
        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(defaultPolicy, samRequestContext).unsafeRunSync()

        dao.addGroupMember(defaultPolicy.id, defaultGroup.id, samRequestContext).unsafeRunSync()

        val loadedPolicy = policyDAO.loadPolicy(defaultPolicy.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"s'failed to load policy ${defaultPolicy.id}"))
        loadedPolicy.members should contain theSameElementsAs Set(defaultGroup.id)
      }

      "add users to policies" in {
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(defaultPolicy, samRequestContext).unsafeRunSync()

        dao.addGroupMember(defaultPolicy.id, defaultUser.id, samRequestContext).unsafeRunSync()

        val loadedPolicy = policyDAO.loadPolicy(defaultPolicy.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"s'failed to load policy ${defaultPolicy.id}"))
        loadedPolicy.members should contain theSameElementsAs Set(defaultUser.id)
      }

      "add policies to other policies" in {
        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(defaultPolicy, samRequestContext).unsafeRunSync()
        val memberPolicy = defaultPolicy.copy(id = defaultPolicy.id.copy(accessPolicyName = AccessPolicyName("memberPolicy")), email = WorkbenchEmail("copied@policy.com"))
        policyDAO.createPolicy(memberPolicy, samRequestContext).unsafeRunSync()

        dao.addGroupMember(defaultPolicy.id, memberPolicy.id, samRequestContext).unsafeRunSync()

        val loadedPolicy = policyDAO.loadPolicy(defaultPolicy.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"s'failed to load policy ${defaultPolicy.id}"))
        loadedPolicy.members should contain theSameElementsAs Set(memberPolicy.id)
      }

      "trying to add a group that does not exist will fail" in {
        val subGroup = emptyWorkbenchGroup("subGroup")
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()

        assertThrows[WorkbenchException] {
          dao.addGroupMember(defaultGroup.id, subGroup.id, samRequestContext).unsafeRunSync() shouldBe true
        }
      }

      "prevents group cycles" in {
        val subGroup = emptyWorkbenchGroup("subGroup")
        val badGroup = emptyWorkbenchGroup("badGroup")
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(subGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(badGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.addGroupMember(defaultGroup.id, subGroup.id, samRequestContext).unsafeRunSync() shouldBe true
        dao.addGroupMember(subGroup.id, badGroup.id, samRequestContext).unsafeRunSync() shouldBe true

        val exception = intercept[WorkbenchExceptionWithErrorReport] {
          dao.addGroupMember(badGroup.id, defaultGroup.id, samRequestContext).unsafeRunSync()
        }

        exception.errorReport.statusCode shouldBe Some(StatusCodes.BadRequest)
        exception.errorReport.message should include (defaultGroup.email.value)
      }
    }

    "batchLoadGroupEmail" - {
      "batch load multiple group emails" in {
        val group1 = emptyWorkbenchGroup("group1")
        val group2 = emptyWorkbenchGroup("group2")

        dao.createGroup(group1, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(group2, samRequestContext = samRequestContext).unsafeRunSync()

        dao.batchLoadGroupEmail(Set(group1.id, group2.id), samRequestContext).unsafeRunSync() should contain theSameElementsAs Set(group1, group2).map(group => (group.id, group.email))
      }
    }

    "removeGroupMember" - {
      "remove groups from other groups" in {
        val subGroup = emptyWorkbenchGroup("subGroup")
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(subGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.addGroupMember(defaultGroup.id, subGroup.id, samRequestContext).unsafeRunSync() shouldBe true
        val afterAdd = dao.loadGroup(defaultGroup.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"failed to load group ${defaultGroup.id}"))
        afterAdd.members should contain theSameElementsAs Set(subGroup.id)
        dao.removeGroupMember(defaultGroup.id, subGroup.id, samRequestContext).unsafeRunSync() shouldBe true

        val afterRemove = dao.loadGroup(defaultGroup.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"failed to load group ${defaultGroup.id}"))
        afterRemove.members shouldBe empty
      }

      "remove users from groups" in {
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()

        dao.addGroupMember(defaultGroup.id, defaultUser.id, samRequestContext).unsafeRunSync() shouldBe true
        val afterAdd = dao.loadGroup(defaultGroup.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"failed to load group ${defaultGroup.id}"))
        afterAdd.members should contain theSameElementsAs Set(defaultUser.id)
        dao.removeGroupMember(defaultGroup.id, defaultUser.id, samRequestContext).unsafeRunSync() shouldBe true

        val afterRemove = dao.loadGroup(defaultGroup.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"failed to load group ${defaultGroup.id}"))
        afterRemove.members shouldBe empty
      }

      "remove policies from groups" in {
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()
        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(defaultPolicy, samRequestContext).unsafeRunSync()

        dao.addGroupMember(defaultGroup.id, defaultPolicy.id, samRequestContext).unsafeRunSync() shouldBe true
        val afterAdd = dao.loadGroup(defaultGroup.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"failed to load group ${defaultGroup.id}"))
        afterAdd.members should contain theSameElementsAs Set(defaultPolicy.id)
        dao.removeGroupMember(defaultGroup.id, defaultPolicy.id, samRequestContext).unsafeRunSync() shouldBe true

        val afterRemove = dao.loadGroup(defaultGroup.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"failed to load group ${defaultGroup.id}"))
        afterRemove.members shouldBe empty
      }

      "remove groups from policies" in {
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        dao.createGroup(defaultGroup.copy(members = Set(defaultUser.id)), samRequestContext = samRequestContext).unsafeRunSync()
        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(defaultPolicy, samRequestContext).unsafeRunSync()

        dao.addGroupMember(defaultPolicy.id, defaultGroup.id, samRequestContext).unsafeRunSync()
        val afterAdd = policyDAO.loadPolicy(defaultPolicy.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"s'failed to load policy ${defaultPolicy.id}"))
        afterAdd.members should contain theSameElementsAs Set(defaultGroup.id)
        policyDAO.listFlattenedPolicyMembers(defaultPolicy.id, samRequestContext).unsafeRunSync() should contain theSameElementsAs Set(defaultUser)
        dao.removeGroupMember(defaultPolicy.id, defaultGroup.id, samRequestContext).unsafeRunSync()

        val afterRemove = policyDAO.loadPolicy(defaultPolicy.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"s'failed to load policy ${defaultPolicy.id}"))
        afterRemove.members shouldBe empty
        policyDAO.listFlattenedPolicyMembers(defaultPolicy.id, samRequestContext).unsafeRunSync() shouldBe empty
      }

      "remove users from policies" in {
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(defaultPolicy, samRequestContext).unsafeRunSync()

        dao.addGroupMember(defaultPolicy.id, defaultUser.id, samRequestContext).unsafeRunSync()
        dao.removeGroupMember(defaultPolicy.id, defaultUser.id, samRequestContext).unsafeRunSync()

        val loadedPolicy = policyDAO.loadPolicy(defaultPolicy.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"s'failed to load policy ${defaultPolicy.id}"))
        loadedPolicy.members shouldBe empty
      }

      "remove policies from other policies" in {
        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(defaultPolicy, samRequestContext).unsafeRunSync()
        val memberPolicy = defaultPolicy.copy(id = defaultPolicy.id.copy(accessPolicyName = AccessPolicyName("memberPolicy")), email = WorkbenchEmail("copied@policy.com"))
        policyDAO.createPolicy(memberPolicy, samRequestContext).unsafeRunSync()

        dao.addGroupMember(defaultPolicy.id, memberPolicy.id, samRequestContext).unsafeRunSync()
        dao.removeGroupMember(defaultPolicy.id, memberPolicy.id, samRequestContext).unsafeRunSync()

        val loadedPolicy = policyDAO.loadPolicy(defaultPolicy.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"s'failed to load policy ${defaultPolicy.id}"))
        loadedPolicy.members shouldBe empty
      }
    }

    "createUser" - {
      "create a user" in {
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync() shouldEqual defaultUser
        val loadedUser = dao.loadUser(defaultUser.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"failed to load user ${defaultUser.id}"))
        loadedUser shouldEqual defaultUser
      }
    }

    "loadUser" - {
      "load a user without a google subject id" in {
        dao.createUser(defaultUser.copy(googleSubjectId = None), samRequestContext).unsafeRunSync()
        dao.loadUser(defaultUser.id, samRequestContext).unsafeRunSync().map(user => user.googleSubjectId shouldBe None)
      }
    }

    "deleteUser" - {
      "delete users" in {
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync() shouldEqual defaultUser
        val loadedUser = dao.loadUser(defaultUser.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"failed to load user ${defaultUser.id}"))
        loadedUser shouldEqual defaultUser
        dao.deleteUser(defaultUser.id, samRequestContext).unsafeRunSync()
        dao.loadUser(defaultUser.id, samRequestContext).unsafeRunSync() shouldBe None
      }

      "delete a user that is still a member of a group" in {
        val user = defaultUser
        val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parentGroup"), Set(user.id), WorkbenchEmail("bar@baz.com"))

        dao.createUser(user, samRequestContext).unsafeRunSync()
        dao.createGroup(parentGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.deleteUser(user.id, samRequestContext).unsafeRunSync()
        dao.loadUser(user.id, samRequestContext).unsafeRunSync() shouldEqual None
      }
    }

    "listUsersGroups" - {
      "list all of the groups a user is in" in {
        val subGroupId = WorkbenchGroupName("subGroup")
        val subGroup = BasicWorkbenchGroup(subGroupId, Set(defaultUser.id), WorkbenchEmail("subGroup@foo.com"))
        val parentGroupId = WorkbenchGroupName("parentGroup")
        val parentGroup = BasicWorkbenchGroup(parentGroupId, Set(defaultUser.id, subGroupId), WorkbenchEmail("parentGroup@foo.com"))

        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        dao.createGroup(subGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(parentGroup, samRequestContext = samRequestContext).unsafeRunSync()

        val usersGroups = dao.listUsersGroups(defaultUser.id, samRequestContext).unsafeRunSync()
        usersGroups should contain theSameElementsAs Set(subGroupId, parentGroupId)
      }

      "list all of the policies a user is in" in {
        val subPolicy = defaultPolicy.copy(id = defaultPolicy.id.copy(accessPolicyName = AccessPolicyName("sp")), email = WorkbenchEmail("sp@policy.com"), members = Set(defaultUser.id))
        val parentPolicy = defaultPolicy.copy(id = defaultPolicy.id.copy(accessPolicyName = AccessPolicyName("pp")), email = WorkbenchEmail("pp@policy.com"), members = Set(subPolicy.id))

        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(subPolicy, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(parentPolicy, samRequestContext).unsafeRunSync()

        dao.listUsersGroups(defaultUser.id, samRequestContext).unsafeRunSync() should contain theSameElementsAs Set(subPolicy.id, parentPolicy.id)
      }
    }

    "createPetServiceAccount" - {
      "create pet service accounts" in {
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        dao.createPetServiceAccount(defaultPetSA, samRequestContext).unsafeRunSync() shouldBe defaultPetSA
      }
    }

    "loadPetServiceAccount" - {
      "load pet service accounts" in {
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        dao.createPetServiceAccount(defaultPetSA, samRequestContext).unsafeRunSync()

        dao.loadPetServiceAccount(defaultPetSA.id, samRequestContext).unsafeRunSync() shouldBe Some(defaultPetSA)
      }

      "return None for nonexistent pet service accounts" in {
        dao.loadPetServiceAccount(defaultPetSA.id, samRequestContext).unsafeRunSync() shouldBe None
      }
    }

    "deletePetServiceAccount" - {
      "delete pet service accounts" in {
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        dao.createPetServiceAccount(defaultPetSA, samRequestContext).unsafeRunSync()

        dao.loadPetServiceAccount(defaultPetSA.id, samRequestContext).unsafeRunSync() shouldBe Some(defaultPetSA)

        dao.deletePetServiceAccount(defaultPetSA.id, samRequestContext).unsafeRunSync()

        dao.loadPetServiceAccount(defaultPetSA.id, samRequestContext).unsafeRunSync() shouldBe None
      }

      "throw an exception when trying to delete a nonexistent pet service account" in {
        assertThrows[WorkbenchException] {
          dao.deletePetServiceAccount(defaultPetSA.id, samRequestContext).unsafeRunSync()
        }
      }
    }

    "getAllPetServiceAccountsForUser" - {
      "get all pet service accounts for user" in {
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()

        val petSA1 = PetServiceAccount(PetServiceAccountId(defaultUser.id, GoogleProject("testProject1")), ServiceAccount(ServiceAccountSubjectId("testGoogleSubjectId1"), WorkbenchEmail("test1@pet.co"), ServiceAccountDisplayName("whoCares")))
        dao.createPetServiceAccount(petSA1, samRequestContext).unsafeRunSync()

        val petSA2 = PetServiceAccount(PetServiceAccountId(defaultUser.id, GoogleProject("testProject2")), ServiceAccount(ServiceAccountSubjectId("testGoogleSubjectId2"), WorkbenchEmail("test2@pet.co"), ServiceAccountDisplayName("whoCares")))
        dao.createPetServiceAccount(petSA2, samRequestContext).unsafeRunSync()

        dao.getAllPetServiceAccountsForUser(defaultUser.id, samRequestContext).unsafeRunSync() should contain theSameElementsAs Seq(petSA1, petSA2)
      }
    }

    "getUserFromPetServiceAccount" - {
      "get user from pet service account subject ID" in {
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()

        dao.createPetServiceAccount(defaultPetSA, samRequestContext).unsafeRunSync()

        dao.getUserFromPetServiceAccount(defaultPetSA.serviceAccount.subjectId, samRequestContext).unsafeRunSync() shouldBe Some(defaultUser)
      }
    }

    "updatePetServiceAccount" - {
      "update a pet service account" in {
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()

        dao.createPetServiceAccount(defaultPetSA, samRequestContext).unsafeRunSync()

        val updatedPetSA = defaultPetSA.copy(serviceAccount = ServiceAccount(ServiceAccountSubjectId("updatedTestGoogleSubjectId"), WorkbenchEmail("new@pet.co"), ServiceAccountDisplayName("whoCares")))
        dao.updatePetServiceAccount(updatedPetSA, samRequestContext).unsafeRunSync() shouldBe updatedPetSA

        dao.loadPetServiceAccount(updatedPetSA.id, samRequestContext).unsafeRunSync() shouldBe Some(updatedPetSA)
      }

      "throw an exception when updating a nonexistent pet SA" in {
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()

        val updatedPetSA = defaultPetSA.copy(serviceAccount = ServiceAccount(ServiceAccountSubjectId("updatedTestGoogleSubjectId"), WorkbenchEmail("new@pet.co"), ServiceAccountDisplayName("whoCares")))
        assertThrows[WorkbenchException] {
          dao.updatePetServiceAccount(updatedPetSA, samRequestContext).unsafeRunSync() shouldBe updatedPetSA
        }
      }
    }

    "getManagedGroupAccessInstructions" - {
      "get managed group access instructions" in {
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.getManagedGroupAccessInstructions(defaultGroupName, samRequestContext).unsafeRunSync() shouldBe None
      }
    }

    "setManagedGroupAccessInstructions" - {
      "set managed group access instructions" in {
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.setManagedGroupAccessInstructions(defaultGroupName, "testinstructions", samRequestContext).unsafeRunSync()

        dao.getManagedGroupAccessInstructions(defaultGroupName, samRequestContext).unsafeRunSync() shouldBe Some("testinstructions")
      }
    }

    "isGroupMember" - {
      "return true when member is in sub group" in {
        val subGroup1 = defaultGroup
        val subGroup2 = BasicWorkbenchGroup(WorkbenchGroupName("subGroup2"), Set(subGroup1.id), WorkbenchEmail("bar@baz.com"))
        val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parentGroup"), Set(subGroup2.id), WorkbenchEmail("baz@qux.com"))

        dao.createGroup(subGroup1, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(subGroup2, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(parentGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.isGroupMember(parentGroup.id, subGroup1.id, samRequestContext).unsafeRunSync() should be (true)
      }

      "return false when member is not in sub group" in {
        val subGroup1 = defaultGroup
        val subGroup2 = BasicWorkbenchGroup(WorkbenchGroupName("subGroup2"), Set(subGroup1.id), WorkbenchEmail("bar@baz.com"))
        val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parentGroup"), Set.empty, WorkbenchEmail("baz@qux.com"))

        dao.createGroup(subGroup1, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(subGroup2, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(parentGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.isGroupMember(parentGroup.id, subGroup1.id, samRequestContext).unsafeRunSync() should be (false)
      }

      "return true when user is in sub group" in {
        val user = defaultUser
        val subGroup = defaultGroup.copy(members = Set(user.id))
        val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parentGroup"), Set(subGroup.id), WorkbenchEmail("parent@group.com"))

        dao.createUser(user, samRequestContext).unsafeRunSync()
        dao.createGroup(subGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(parentGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.isGroupMember(parentGroup.id, user.id, samRequestContext).unsafeRunSync() shouldBe true
      }

      // https://broadworkbench.atlassian.net/browse/CA-600
      "return true when user is in multiple sub groups" in {
        val user = defaultUser
        val subGroup1 = defaultGroup.copy(members = Set(user.id))
        val subGroup2 = BasicWorkbenchGroup(WorkbenchGroupName("subGroup2"), Set(user.id), WorkbenchEmail("group2@foo.com"))
        val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parentGroup"), Set(subGroup1.id, subGroup2.id), WorkbenchEmail("baz@qux.com"))

        dao.createUser(user, samRequestContext).unsafeRunSync()
        dao.createGroup(subGroup1, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(subGroup2, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(parentGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.isGroupMember(parentGroup.id, user.id, samRequestContext).unsafeRunSync() should be (true)
      }

      "return false when user is not in sub group" in {
        val user = defaultUser
        val subGroup = defaultGroup.copy(members = Set(user.id))
        val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parentGroup"), Set.empty, WorkbenchEmail("parent@group.com"))

        dao.createUser(user, samRequestContext).unsafeRunSync()
        dao.createGroup(subGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(parentGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.isGroupMember(parentGroup.id, user.id, samRequestContext).unsafeRunSync() shouldBe false
      }

      "return true when user is in policy" in {
        val user = defaultUser
        val policy = defaultPolicy.copy(members = Set(user.id))

        dao.createUser(user, samRequestContext).unsafeRunSync()
        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(policy, samRequestContext).unsafeRunSync()

        dao.isGroupMember(policy.id, user.id, samRequestContext).unsafeRunSync() shouldBe true
      }

      "return false when user is not in policy" in {
        val user = defaultUser
        val policy = defaultPolicy

        dao.createUser(user, samRequestContext).unsafeRunSync()
        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(policy, samRequestContext).unsafeRunSync()

        dao.isGroupMember(policy.id, user.id, samRequestContext).unsafeRunSync() shouldBe false
      }

      "return true when policy is in policy" in {
        val memberPolicy = defaultPolicy.copy(id = defaultPolicy.id.copy(accessPolicyName = AccessPolicyName("memberPolicy")), email = WorkbenchEmail("copied@policy.com"))
        val policy = defaultPolicy.copy(members = Set(memberPolicy.id))

        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(memberPolicy, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(policy, samRequestContext).unsafeRunSync()

        dao.isGroupMember(policy.id, memberPolicy.id, samRequestContext).unsafeRunSync() shouldBe true
      }

      "return false when policy is not in policy" in {
        val memberPolicy = defaultPolicy.copy(id = defaultPolicy.id.copy(accessPolicyName = AccessPolicyName("memberPolicy")), email = WorkbenchEmail("copied@policy.com"))
        val policy = defaultPolicy

        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(memberPolicy, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(policy, samRequestContext).unsafeRunSync()

        dao.isGroupMember(policy.id, memberPolicy.id, samRequestContext).unsafeRunSync() shouldBe false
      }

      "return true when policy is in group" in {
        val memberPolicy = defaultPolicy
        val group = defaultGroup.copy(members = Set(memberPolicy.id))

        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(memberPolicy, samRequestContext).unsafeRunSync()
        dao.createGroup(group, samRequestContext = samRequestContext).unsafeRunSync()

        dao.isGroupMember(group.id, memberPolicy.id, samRequestContext).unsafeRunSync() shouldBe true
      }

      "return false when policy is not in group" in {
        val memberPolicy = defaultPolicy
        val group = defaultGroup

        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(memberPolicy, samRequestContext).unsafeRunSync()
        dao.createGroup(group, samRequestContext = samRequestContext).unsafeRunSync()

        dao.isGroupMember(group.id, memberPolicy.id, samRequestContext).unsafeRunSync() shouldBe false
      }

      "return true when group is in policy" in {
        val memberGroup = defaultGroup
        val policy = defaultPolicy.copy(members = Set(memberGroup.id))

        dao.createGroup(memberGroup, samRequestContext = samRequestContext).unsafeRunSync()
        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(policy, samRequestContext).unsafeRunSync()

        dao.isGroupMember(policy.id, memberGroup.id, samRequestContext).unsafeRunSync() shouldBe true
      }

      "return false when group is not in policy" in {
        val memberGroup = defaultGroup
        val policy = defaultPolicy

        dao.createGroup(memberGroup, samRequestContext = samRequestContext).unsafeRunSync()
        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(policy, samRequestContext).unsafeRunSync()

        dao.isGroupMember(policy.id, memberGroup.id, samRequestContext).unsafeRunSync() shouldBe false
      }
    }

    "listIntersectionGroupUsers" - {
      // DV: I have tried this up to 100 groups to intersect locally with no functional issue, performance seems linear
      "intersect groups" in {
        for (groupCount <- 1 to 3) {
          beforeEach()
          val inAllGroups = Generator.genWorkbenchUserGoogle.sample.get
          dao.createUser(inAllGroups, samRequestContext).unsafeRunSync()

          val allGroups = for (i <- 1 to groupCount) yield {
            // create a group with 1 user and 1 subgroup, subgroup with "allgroups" users and another user
            val userInGroup = Generator.genWorkbenchUserGoogle.sample.get
            val userInSubGroup = Generator.genWorkbenchUserGoogle.sample.get
            val subGroup = BasicWorkbenchGroup(WorkbenchGroupName(s"subgroup$i"), Set(inAllGroups.id, userInSubGroup.id), WorkbenchEmail(s"subgroup$i"))
            val group = BasicWorkbenchGroup(WorkbenchGroupName(s"group$i"), Set(userInGroup.id, subGroup.id), WorkbenchEmail(s"group$i"))
            dao.createUser(userInSubGroup, samRequestContext).unsafeRunSync()
            dao.createUser(userInGroup, samRequestContext).unsafeRunSync()
            dao.createGroup(subGroup, samRequestContext = samRequestContext).unsafeRunSync()
            dao.createGroup(group, samRequestContext = samRequestContext).unsafeRunSync()
          }

          if (groupCount == 1) {
            val intersection = dao.listIntersectionGroupUsers(allGroups.map(_.id).toSet, samRequestContext).unsafeRunSync()
            intersection should contain oneElementOf Set(inAllGroups.id)
            intersection.size shouldBe 3
          } else {
            dao.listIntersectionGroupUsers(allGroups.map(_.id).toSet, samRequestContext).unsafeRunSync() should contain theSameElementsAs Set(inAllGroups.id)
          }
        }
      }

      "intersect lots of groups with lots of dups and overlaps" in {
        val groupCount = 40
        val userCount = 50

        // create a user and a group containing that single user
        val allUserGroups = for (i <- 1 to userCount) yield {
          val user = dao.createUser(Generator.genWorkbenchUserGoogle.sample.get.copy(id = WorkbenchUserId(s"user$i")), samRequestContext).unsafeRunSync()
          val group = BasicWorkbenchGroup(WorkbenchGroupName(s"usergroup$i"), Set(user.id), WorkbenchEmail(s"usergroup$i"))
          dao.createGroup(group, samRequestContext = samRequestContext).unsafeRunSync()
        }

        val allUserGroupNames: Set[WorkbenchSubject] = allUserGroups.map(_.id).toSet
        val allUserIds = allUserGroups.map(_.members.head)

        // create groupCount groups each containing all single user groups
        val allSubGroups = for (i <- 1 to groupCount) yield {
          val group = BasicWorkbenchGroup(WorkbenchGroupName(s"subgroup$i"), allUserGroupNames, WorkbenchEmail(s"subgroup$i"))
          dao.createGroup(group, samRequestContext = samRequestContext).unsafeRunSync()
        }

        // create groupCount groups each containing all subGroups
        val topGroups = for (i <- 1 to groupCount) yield {
          // create a group with 1 user and 1 subgroup, subgroup with "allgroups" users and another user
          val group = BasicWorkbenchGroup(WorkbenchGroupName(s"group$i"), allSubGroups.map(_.id).toSet, WorkbenchEmail(s"group$i"))
          dao.createGroup(group, samRequestContext = samRequestContext).unsafeRunSync()
        }

        // intersect all top groups
        dao.listIntersectionGroupUsers(topGroups.map(_.id).toSet, samRequestContext).unsafeRunSync() should contain theSameElementsAs allUserIds
      }
    }

    "enableIdentity and disableIdentity" - {
      "can enable and disable users" in {
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        dao.isEnabled(defaultUser.id, samRequestContext).unsafeRunSync() shouldBe false

        dao.enableIdentity(defaultUser.id, samRequestContext).unsafeRunSync()
        dao.isEnabled(defaultUser.id, samRequestContext).unsafeRunSync() shouldBe true

        dao.disableIdentity(defaultUser.id, samRequestContext).unsafeRunSync()
        dao.isEnabled(defaultUser.id, samRequestContext).unsafeRunSync() shouldBe false
      }

      "cannot enable and disable pet service accounts" in {
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        dao.createPetServiceAccount(defaultPetSA, samRequestContext).unsafeRunSync()
        val initialEnabledStatus = dao.isEnabled(defaultPetSA.id, samRequestContext).unsafeRunSync()

        dao.disableIdentity(defaultPetSA.id, samRequestContext).unsafeRunSync()
        dao.isEnabled(defaultPetSA.id, samRequestContext).unsafeRunSync() shouldBe initialEnabledStatus

        dao.enableIdentity(defaultPetSA.id, samRequestContext).unsafeRunSync()
        dao.isEnabled(defaultPetSA.id, samRequestContext).unsafeRunSync() shouldBe initialEnabledStatus
      }

      "cannot enable and disable groups" in {
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()
        val initialEnabledStatus = dao.isEnabled(defaultGroup.id, samRequestContext).unsafeRunSync()

        dao.disableIdentity(defaultGroup.id, samRequestContext).unsafeRunSync()
        dao.isEnabled(defaultGroup.id, samRequestContext).unsafeRunSync() shouldBe initialEnabledStatus

        dao.enableIdentity(defaultGroup.id, samRequestContext).unsafeRunSync()
        dao.isEnabled(defaultGroup.id, samRequestContext).unsafeRunSync() shouldBe initialEnabledStatus
      }

      "cannot enable and disable policies" in {
        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(defaultPolicy, samRequestContext).unsafeRunSync()
        val initialEnabledStatus = dao.isEnabled(defaultPolicy.id, samRequestContext).unsafeRunSync()

        dao.disableIdentity(defaultPolicy.id, samRequestContext).unsafeRunSync()
        dao.isEnabled(defaultPolicy.id, samRequestContext).unsafeRunSync() shouldBe initialEnabledStatus

        dao.enableIdentity(defaultPolicy.id, samRequestContext).unsafeRunSync()
        dao.isEnabled(defaultPolicy.id, samRequestContext).unsafeRunSync() shouldBe initialEnabledStatus
      }
    }

    "isEnabled" - {
      "gets a user's enabled status" in {
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()

        dao.disableIdentity(defaultUser.id, samRequestContext).unsafeRunSync()
        dao.isEnabled(defaultUser.id, samRequestContext).unsafeRunSync() shouldBe false

        dao.enableIdentity(defaultUser.id, samRequestContext).unsafeRunSync()
        dao.isEnabled(defaultUser.id, samRequestContext).unsafeRunSync() shouldBe true
      }

      "gets a pet's user's enabled status" in {
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        dao.createPetServiceAccount(defaultPetSA, samRequestContext).unsafeRunSync()

        dao.disableIdentity(defaultUser.id, samRequestContext).unsafeRunSync()
        dao.isEnabled(defaultPetSA.id, samRequestContext).unsafeRunSync() shouldBe false

        dao.enableIdentity(defaultUser.id, samRequestContext).unsafeRunSync()
        dao.isEnabled(defaultPetSA.id, samRequestContext).unsafeRunSync() shouldBe true
      }

      "returns false for groups" in {
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.isEnabled(defaultGroup.id, samRequestContext).unsafeRunSync() shouldBe false
        dao.enableIdentity(defaultGroup.id, samRequestContext).unsafeRunSync()
        dao.isEnabled(defaultGroup.id, samRequestContext).unsafeRunSync() shouldBe false
      }

      "returns false for policies" in {
        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(defaultPolicy, samRequestContext).unsafeRunSync()

        dao.isEnabled(defaultPolicy.id, samRequestContext).unsafeRunSync() shouldBe false
        dao.enableIdentity(defaultPolicy.id, samRequestContext).unsafeRunSync()
        dao.isEnabled(defaultPolicy.id, samRequestContext).unsafeRunSync() shouldBe false
      }
    }

    "listUserDirectMemberships" - {
      "lists all groups that a user is in directly" in {
        val subSubGroup = BasicWorkbenchGroup(WorkbenchGroupName("ssg"), Set(defaultUser.id), WorkbenchEmail("ssg@groups.r.us"))
        val subGroup = BasicWorkbenchGroup(WorkbenchGroupName("sg"), Set(defaultUser.id, subSubGroup.id), WorkbenchEmail("sg@groups.r.us"))
        val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("pg"), Set(subGroup.id), WorkbenchEmail("pg@groups.r.us"))

        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        dao.createGroup(subSubGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(subGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(parentGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.listUserDirectMemberships(defaultUser.id, samRequestContext).unsafeRunSync() should contain theSameElementsAs Set(subGroup.id, subSubGroup.id)
      }

      "lists all policies that a user is in directly" in {
        // disclaimer: not sure this ever happens in actual sam usage, but it should still work
        val subSubPolicy = defaultPolicy.copy(id = defaultPolicy.id.copy(accessPolicyName = AccessPolicyName("ssp")), email = WorkbenchEmail("ssp@policy.com"), members = Set(defaultUser.id))
        val subPolicy = defaultPolicy.copy(id = defaultPolicy.id.copy(accessPolicyName = AccessPolicyName("sp")), email = WorkbenchEmail("sp@policy.com"), members = Set(subSubPolicy.id, defaultUser.id))
        val parentPolicy = defaultPolicy.copy(id = defaultPolicy.id.copy(accessPolicyName = AccessPolicyName("pp")), email = WorkbenchEmail("pp@policy.com"), members = Set(subPolicy.id))

        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(subSubPolicy, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(subPolicy, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(parentPolicy, samRequestContext).unsafeRunSync()

        dao.listUserDirectMemberships(defaultUser.id, samRequestContext).unsafeRunSync() should contain theSameElementsAs Set(subSubPolicy.id, subPolicy.id)
      }
    }

    "listAncestorGroups" - {
      "list all of the groups a group is in" in {
        val subSubGroup = BasicWorkbenchGroup(WorkbenchGroupName("ssg"), Set.empty, WorkbenchEmail("ssg@groups.r.us"))
        val subGroup = BasicWorkbenchGroup(WorkbenchGroupName("sg"), Set(subSubGroup.id), WorkbenchEmail("sg@groups.r.us"))
        val directParentGroup = BasicWorkbenchGroup(WorkbenchGroupName("dpg"), Set(subGroup.id, subSubGroup.id), WorkbenchEmail("dpg@groups.r.us"))
        val indirectParentGroup = BasicWorkbenchGroup(WorkbenchGroupName("ipg"), Set(subGroup.id), WorkbenchEmail("ipg@groups.r.us"))

        dao.createGroup(subSubGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(subGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(directParentGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(indirectParentGroup, samRequestContext = samRequestContext).unsafeRunSync()

        val ancestorGroups = dao.listAncestorGroups(subSubGroup.id, samRequestContext).unsafeRunSync()
        ancestorGroups should contain theSameElementsAs Set(subGroup.id, directParentGroup.id, indirectParentGroup.id)
      }

      "list all of the policies a group is in" in {
        val subPolicy = defaultPolicy.copy(id = defaultPolicy.id.copy(accessPolicyName = AccessPolicyName("sp")), email = WorkbenchEmail("sp@policy.com"), members = Set(defaultGroup.id))
        val parentPolicy = defaultPolicy.copy(id = defaultPolicy.id.copy(accessPolicyName = AccessPolicyName("pp")), email = WorkbenchEmail("pp@policy.com"), members = Set(subPolicy.id))

        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()
        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(subPolicy, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(parentPolicy, samRequestContext).unsafeRunSync()

        dao.listAncestorGroups(defaultGroup.id, samRequestContext).unsafeRunSync() should contain theSameElementsAs Set(subPolicy.id, parentPolicy.id)
      }

      "list all of the groups a policy is in" in {
        val subGroup = BasicWorkbenchGroup(WorkbenchGroupName("sg"), Set(defaultPolicy.id), WorkbenchEmail("sg@groups.r.us"))
        val directParentGroup = BasicWorkbenchGroup(WorkbenchGroupName("dpg"), Set(subGroup.id, defaultPolicy.id), WorkbenchEmail("dpg@groups.r.us"))
        val indirectParentGroup = BasicWorkbenchGroup(WorkbenchGroupName("ipg"), Set(subGroup.id), WorkbenchEmail("ipg@groups.r.us"))

        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(defaultPolicy, samRequestContext).unsafeRunSync()
        dao.createGroup(subGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(directParentGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createGroup(indirectParentGroup, samRequestContext = samRequestContext).unsafeRunSync()

        val ancestorGroups = dao.listAncestorGroups(defaultPolicy.id, samRequestContext).unsafeRunSync()
        ancestorGroups should contain theSameElementsAs Set(subGroup.id, directParentGroup.id, indirectParentGroup.id)
      }

      "list all of the policies a policy is in" in {
        val subPolicy = defaultPolicy.copy(id = defaultPolicy.id.copy(accessPolicyName = AccessPolicyName("sp")), email = WorkbenchEmail("sp@policy.com"), members = Set(defaultPolicy.id))
        val directParentPolicy = defaultPolicy.copy(id = defaultPolicy.id.copy(accessPolicyName = AccessPolicyName("pp")), email = WorkbenchEmail("pp@policy.com"), members = Set(subPolicy.id, defaultPolicy.id))
        val indirectParentPolicy = defaultPolicy.copy(id = defaultPolicy.id.copy(accessPolicyName = AccessPolicyName("ssp")), email = WorkbenchEmail("ssp@policy.com"), members = Set(subPolicy.id))


        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(defaultPolicy, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(subPolicy, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(directParentPolicy, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(indirectParentPolicy, samRequestContext).unsafeRunSync()

        val ancestorGroups = dao.listAncestorGroups(defaultPolicy.id, samRequestContext).unsafeRunSync()
        ancestorGroups should contain theSameElementsAs Set(subPolicy.id, directParentPolicy.id, indirectParentPolicy.id)
      }
    }

    "getSynchronizedEmail" - {
      "load the email for a group" in {
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.getSynchronizedEmail(defaultGroup.id, samRequestContext).unsafeRunSync() shouldEqual Option(defaultGroup.email)
      }

      "load the email for a policy" in {
        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(defaultPolicy, samRequestContext).unsafeRunSync()

        dao.getSynchronizedEmail(defaultPolicy.id, samRequestContext).unsafeRunSync() shouldEqual Option(defaultPolicy.email)
      }
    }

    "getSynchronizedDate" - {
      "load the synchronized date for a group" in {
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.updateSynchronizedDate(defaultGroup.id, samRequestContext).unsafeRunSync()

        val loadedDate = dao.getSynchronizedDate(defaultGroup.id, samRequestContext).unsafeRunSync().getOrElse(fail("failed to load date"))
        loadedDate.getTime() should equal (new Date().getTime +- 2.seconds.toMillis)
      }

      "load the synchronized date for a policy" in {
        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(defaultPolicy, samRequestContext).unsafeRunSync()

        dao.updateSynchronizedDate(defaultPolicy.id, samRequestContext).unsafeRunSync()

        val loadedDate = dao.getSynchronizedDate(defaultPolicy.id, samRequestContext).unsafeRunSync().getOrElse(fail("failed to load date"))
        loadedDate.getTime() should equal (new Date().getTime +- 2.seconds.toMillis)
      }
    }

    "setGoogleSubjectId" - {
      "update the googleSubjectId for a user" in {
        val newGoogleSubjectId = GoogleSubjectId("newGoogleSubjectId")
        dao.createUser(defaultUser.copy(googleSubjectId = None), samRequestContext).unsafeRunSync()

        dao.loadUser(defaultUser.id, samRequestContext).unsafeRunSync().flatMap(_.googleSubjectId) shouldBe None
        dao.setGoogleSubjectId(defaultUser.id, newGoogleSubjectId, samRequestContext).unsafeRunSync()

        dao.loadUser(defaultUser.id, samRequestContext).unsafeRunSync().flatMap(_.googleSubjectId) shouldBe Option(newGoogleSubjectId)
      }

      "throw an exception when trying to overwrite an existing googleSubjectId" in {
        val newGoogleSubjectId = GoogleSubjectId("newGoogleSubjectId")
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()

        assertThrows[WorkbenchException] {
          dao.setGoogleSubjectId(defaultUser.id, newGoogleSubjectId, samRequestContext).unsafeRunSync()
        }
      }
    }

    "loadSubjectFromEmail" - {
      "load a user subject from their email" in {
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()

        dao.loadSubjectFromEmail(defaultUser.email, samRequestContext).unsafeRunSync() shouldBe Some(defaultUser.id)
      }

      "load a user subject from their email case insensitive" in {
        val email = WorkbenchEmail("Mixed.Case.Email@foo.com")
        dao.createUser(defaultUser.copy(email = email), samRequestContext).unsafeRunSync()

        dao.loadSubjectFromEmail(new WorkbenchEmail(email.value.toLowerCase()), samRequestContext).unsafeRunSync() shouldBe Some(defaultUser.id)
      }

      "load a group subject from its email" in {
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.loadSubjectFromEmail(defaultGroup.email, samRequestContext).unsafeRunSync() shouldBe Some(defaultGroupName)
      }

      "load a pet service account subject from its email" in {
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        dao.createPetServiceAccount(defaultPetSA, samRequestContext).unsafeRunSync()

        dao.loadSubjectFromEmail(defaultPetSA.serviceAccount.email, samRequestContext).unsafeRunSync() shouldBe Some(defaultPetSA.id)
      }

      "load a policy subject from its email" in {
        val memberPolicy = defaultPolicy

        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(memberPolicy, samRequestContext).unsafeRunSync()

        dao.loadSubjectFromEmail(defaultPolicy.email, samRequestContext).unsafeRunSync() shouldBe Some(defaultPolicy.id)
      }

      "throw an exception when an email refers to more than one subject" in {
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        dao.createPetServiceAccount(defaultPetSA.copy(serviceAccount = defaultPetSA.serviceAccount.copy(email = defaultUser.email)), samRequestContext).unsafeRunSync()

        assertThrows[WorkbenchException] {
          dao.loadSubjectFromEmail(defaultUser.email, samRequestContext).unsafeRunSync() shouldBe Some(defaultPetSA.id)
        }
      }
    }

    "loadSubjectFromGoogleSubjectId" - {
      "load a user subject from their google subject id" in {
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()

        dao.loadSubjectFromGoogleSubjectId(defaultUser.googleSubjectId.get, samRequestContext).unsafeRunSync() shouldBe Some(defaultUser.id)
      }

      "load a pet service account subject from its google subject id" in {
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        dao.createPetServiceAccount(defaultPetSA, samRequestContext).unsafeRunSync()

        dao.loadSubjectFromGoogleSubjectId(GoogleSubjectId(defaultPetSA.serviceAccount.subjectId.value), samRequestContext).unsafeRunSync() shouldBe Some(defaultPetSA.id)
      }
    }

    "loadSubjectEmail" - {
      "load the email for a user" in {
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()

        dao.loadSubjectEmail(defaultUser.id, samRequestContext).unsafeRunSync() shouldBe Some(defaultUser.email)
      }

      "load the email for a group" in {
        dao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.loadSubjectEmail(defaultGroup.id, samRequestContext).unsafeRunSync() shouldBe Some(defaultGroup.email)
      }

      "load the email for a pet service account" in {
        dao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        dao.createPetServiceAccount(defaultPetSA, samRequestContext).unsafeRunSync()

        dao.loadSubjectEmail(defaultPetSA.id, samRequestContext).unsafeRunSync() shouldBe Some(defaultPetSA.serviceAccount.email)
      }

      "load the email for a policy" in {
        val memberPolicy = defaultPolicy

        policyDAO.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        policyDAO.createResource(defaultResource, samRequestContext).unsafeRunSync()
        policyDAO.createPolicy(memberPolicy, samRequestContext).unsafeRunSync()

        dao.loadSubjectEmail(defaultPolicy.id, samRequestContext).unsafeRunSync() shouldBe Some(defaultPolicy.email)
      }
    }

  }
}
