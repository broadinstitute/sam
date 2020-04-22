package org.broadinstitute.dsde.workbench.sam.openam

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.db.PSQLStateExtensions
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.directory._
import org.broadinstitute.dsde.workbench.sam.openam.LoadResourceAuthDomainResult.{Constrained, NotConstrained, ResourceNotFound}
import org.postgresql.util.PSQLException
import org.scalatest.{BeforeAndAfterEach, FreeSpec, Matchers}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

class PostgresAccessPolicyDAOSpec extends FreeSpec with Matchers with BeforeAndAfterEach {
  implicit val cs = IO.contextShift(scala.concurrent.ExecutionContext.global)
  val dao = new PostgresAccessPolicyDAO(TestSupport.dbRef, TestSupport.blockingEc)
  val dirDao = new PostgresDirectoryDAO(TestSupport.dbRef, TestSupport.blockingEc)

  override protected def beforeEach(): Unit = {
    TestSupport.truncateAll
    super.beforeEach()
  }

  "PostgresAccessPolicyDAO" - {
    val resourceTypeName = ResourceTypeName("awesomeType")

    val actionPatterns = Set(ResourceActionPattern("write", "description of pattern1", false),
                             ResourceActionPattern("read", "description of pattern2", false))

    val writeAction = ResourceAction("write")
    val readAction = ResourceAction("read")

    val ownerRoleName = ResourceRoleName("owner")

    val ownerRole = ResourceRole(ownerRoleName, Set(writeAction, readAction))
    val readerRole = ResourceRole(ResourceRoleName("reader"), Set(readAction))
    val actionlessRole = ResourceRole(ResourceRoleName("cantDoNuthin"), Set()) // yeah, it's a double negative, sue me!

    val roles = Set(ownerRole, readerRole, actionlessRole)
    val resourceType = ResourceType(resourceTypeName, actionPatterns, roles, ownerRoleName, false)

    "createResourceType" - {
      "succeeds" in {
        dao.createResourceType(resourceType).unsafeRunSync() shouldEqual resourceType
      }

      "succeeds" - {
        "when there are no action patterns" in {
          val myResourceType = resourceType.copy(actionPatterns = Set.empty)
          dao.createResourceType(myResourceType).unsafeRunSync() shouldEqual myResourceType
        }

        "when there are no roles" in {
          val myResourceType = resourceType.copy(roles = Set.empty)
          dao.createResourceType(myResourceType).unsafeRunSync() shouldEqual myResourceType
        }

        "when there is exactly one Role that has no actions" in {
          val myResourceType = resourceType.copy(roles = Set(actionlessRole))
          dao.createResourceType(myResourceType).unsafeRunSync() shouldEqual myResourceType
        }

        // This test is hard to write at the moment.  We don't have an easy way to guarantee the race condition at exactly the right time.  Nor do
        // we have a good way to check if the data that was saved is what we intended.   This spec class could implement DatabaseSupport.  Or the
        // createResourceType could minimally return the ResourceTypePK in its results.  Or we need some way to get all
        // of the ResourceTypes from the DB and compare them to what we were trying to save.
        "and only creates 1 ResourceType when trying to create multiple identical ResourceTypes at the same time" in {

          pending

          // Since we can't directly force a collision at exactly the right time, kick off a bunch of inserts in parallel
          // and hope for the best.  <- That's how automated testing is supposed to work right?  Just cross your fingers?
          val allMyFutures = 0.to(20).map { _ =>
            dao.createResourceType(resourceType).unsafeToFuture()
          }

          Await.result(Future.sequence(allMyFutures), 5 seconds)
          // This is the part where I would want to assert that the database contains only one ResourceType
        }
      }

      "overwriting a ResourceType with the same name" - {
        "succeeds" - {
          "when the new ResourceType" - {
            "is identical" in {
              dao.createResourceType(resourceType).unsafeRunSync()
              dao.createResourceType(resourceType).unsafeRunSync() shouldEqual resourceType
            }

            "adds new" - {
              "ActionPatterns" in {
                val myActionPatterns = actionPatterns + ResourceActionPattern("coolNewPattern", "I am the coolest pattern EVER!  Mwahaha", true)
                val myResourceType = resourceType.copy(actionPatterns = myActionPatterns)

                dao.createResourceType(resourceType).unsafeRunSync()
                dao.createResourceType(myResourceType).unsafeRunSync() shouldEqual myResourceType
              }

              "Roles" in {
                val myRoles = roles + ResourceRole(ResourceRoleName("blindWriter"), Set(writeAction))
                val myResourceType = resourceType.copy(roles = myRoles)

                dao.createResourceType(resourceType).unsafeRunSync()
                dao.createResourceType(myResourceType).unsafeRunSync() shouldEqual myResourceType
              }

              "Role Actions" in {
                val myReaderRole = readerRole.copy(actions = Set(readAction, writeAction))
                val myRoles = Set(myReaderRole, ownerRole, actionlessRole)
                val myResourceType = resourceType.copy(roles = myRoles)

                dao.createResourceType(resourceType).unsafeRunSync()
                dao.createResourceType(myResourceType).unsafeRunSync() shouldEqual myResourceType
              }
            }

            "has the same ActionPatterns with modified descriptions" in {
              val myActionPatterns = actionPatterns + ResourceActionPattern("coolNewPattern", "I am the coolest pattern EVER!  Mwahaha", true)
              val myResourceType = resourceType.copy(actionPatterns = myActionPatterns)

              val myActionPatternsNew = actionPatterns + ResourceActionPattern("coolNewPattern", "I am the NEWEST pattern EVER!  Mwahaha", true)

              val myUpdatedResourceType = myResourceType.copy(actionPatterns = myActionPatternsNew)

              dao.createResourceType(myResourceType).unsafeRunSync() shouldEqual myResourceType

              dao.createResourceType(myUpdatedResourceType).unsafeRunSync() shouldEqual myUpdatedResourceType
            }

            "is removing at least one" - {
              "ActionPattern" in {
                val removeActionPattern = resourceType.copy(actionPatterns = actionPatterns.tail)
                dao.createResourceType(resourceType).unsafeRunSync()
                dao.createResourceType(removeActionPattern).unsafeRunSync() shouldEqual removeActionPattern
              }

              "Role" in {
                val removeRole = resourceType.copy(roles = roles.tail)
                dao.createResourceType(resourceType).unsafeRunSync()
                dao.createResourceType(removeRole).unsafeRunSync() shouldEqual removeRole
              }

              "of its role's Actions" in {
                val readerlessReader = readerRole.copy(actions = Set.empty)
                val newRoles = Set(ownerRole, readerlessReader, actionlessRole)
                val removeAction = resourceType.copy(roles = newRoles)
                dao.createResourceType(resourceType).unsafeRunSync()
                dao.createResourceType(removeAction).unsafeRunSync() shouldEqual removeAction
              }
            }
          }
        }

        "and removing a role" - {
          "doesn't affect existing policies with the role, but prevents using the role in new policies" in {
            val initialRoles = Set(ownerRole, readerRole, actionlessRole)
            val initialResourceType = resourceType.copy(roles = initialRoles)

            val newRoles = Set(ownerRole, actionlessRole)
            val overwriteResourceType = initialResourceType.copy(roles = newRoles)

            val resource = Resource(initialResourceType.name, ResourceId("resource"), Set.empty)
            val beforeOverwritePolicy = AccessPolicy(FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("willHaveDeprecatedRole")), Set.empty, WorkbenchEmail("allowed@policy.com"), Set(readerRole.roleName, actionlessRole.roleName), Set.empty, false)
            val afterOverwritePolicy = AccessPolicy(FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("cannotHaveDeprecatedRole")), Set.empty, WorkbenchEmail("not_allowed@policy.com"), Set(readerRole.roleName, actionlessRole.roleName), Set.empty, false)

            dao.createResourceType(initialResourceType).unsafeRunSync()
            dao.createResource(resource).unsafeRunSync()
            dao.createPolicy(beforeOverwritePolicy).unsafeRunSync()

            dao.createResourceType(overwriteResourceType).unsafeRunSync()
            assertThrows[WorkbenchException] {
              dao.createPolicy(afterOverwritePolicy).unsafeRunSync()
            }

            dao.loadPolicy(beforeOverwritePolicy.id).unsafeRunSync() shouldBe Option(beforeOverwritePolicy)
            dao.loadPolicy(afterOverwritePolicy.id).unsafeRunSync() shouldBe None
          }
        }

        "and removing an action" - {
          "from all roles will not affect policies that use the action" in {
            val initialRoles = Set(ownerRole, readerRole, actionlessRole)
            val initialResourceType = resourceType.copy(roles = initialRoles)

            val readingOwner = ownerRole.copy(actions = Set(readAction))
            val newRoles = Set(readingOwner, readerRole, actionlessRole)
            val overwriteResourceType = initialResourceType.copy(roles = newRoles)

            val resource = Resource(initialResourceType.name, ResourceId("resource"), Set.empty)
            val beforeOverwritePolicy = AccessPolicy(FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("willHaveDeprecatedRole")), Set.empty, WorkbenchEmail("allowed@policy.com"), Set(ownerRole.roleName, actionlessRole.roleName), Set.empty, false)
            val afterOverwritePolicy = AccessPolicy(FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("cannotHaveDeprecatedRole")), Set.empty, WorkbenchEmail("not_allowed@policy.com"), Set(readingOwner.roleName, actionlessRole.roleName), Set(writeAction), false)

            dao.createResourceType(initialResourceType).unsafeRunSync()
            dao.createResource(resource).unsafeRunSync()
            dao.createPolicy(beforeOverwritePolicy).unsafeRunSync()

            dao.createResourceType(overwriteResourceType).unsafeRunSync()
            dao.createPolicy(afterOverwritePolicy).unsafeRunSync()

            dao.loadPolicy(beforeOverwritePolicy.id).unsafeRunSync() shouldBe Option(beforeOverwritePolicy)
            dao.loadPolicy(afterOverwritePolicy.id).unsafeRunSync() shouldBe Option(afterOverwritePolicy)
          }

          "from a resource type's role will remove that action from all policies with that role" in {
            val initialRoles = Set(ownerRole, readerRole, actionlessRole)
            val initialResourceType = resourceType.copy(roles = initialRoles)

            val readerlessReader = readerRole.copy(actions = Set.empty)
            val newRoles = Set(ownerRole, readerlessReader, actionlessRole)
            val overwriteResourceType = initialResourceType.copy(roles = newRoles)

            val resource = Resource(initialResourceType.name, ResourceId("resource"), Set.empty)
            val beforeOverwritePolicy = AccessPolicy(FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("willHaveDeprecatedRole")), Set.empty, WorkbenchEmail("allowed@policy.com"), Set(readerRole.roleName, actionlessRole.roleName), Set.empty, false)
            val afterOverwritePolicy = AccessPolicy(FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("cannotHaveDeprecatedRole")), Set.empty, WorkbenchEmail("not_allowed@policy.com"), Set(readerlessReader.roleName, actionlessRole.roleName), Set.empty, false)

            dao.createResourceType(initialResourceType).unsafeRunSync()
            dao.createResource(resource).unsafeRunSync()
            dao.createPolicy(beforeOverwritePolicy).unsafeRunSync()

            dao.createResourceType(overwriteResourceType).unsafeRunSync()
            dao.createPolicy(afterOverwritePolicy).unsafeRunSync()

            dao.loadPolicy(beforeOverwritePolicy.id).unsafeRunSync() shouldBe Option(beforeOverwritePolicy)
            dao.loadPolicy(afterOverwritePolicy.id).unsafeRunSync() shouldBe Option(afterOverwritePolicy)
          }
        }
      }
    }

    "createResource" - {
      val resource = Resource(resourceType.name, ResourceId("verySpecialResource"), Set.empty)

      "succeeds when resource type exists" in {
        dao.createResourceType(resourceType).unsafeRunSync()
        dao.createResource(resource).unsafeRunSync() shouldEqual resource
      }

      "returns a WorkbenchExceptionWithErrorReport when a resource with the same name and type already exists" in {
        dao.createResourceType(resourceType).unsafeRunSync()
        dao.createResource(resource).unsafeRunSync()

        val exception = intercept[WorkbenchExceptionWithErrorReport] {
          dao.createResource(resource).unsafeRunSync()
        }
        exception.errorReport.statusCode should equal (Some(StatusCodes.Conflict))
      }

      "raises an error when the ResourceType does not exist" in {
        val exception = intercept[PSQLException] {
          dao.createResource(resource).unsafeRunSync()
        }

        exception.getSQLState shouldEqual PSQLStateExtensions.NULL_CONSTRAINT_VIOLATION
      }

      "can add a resource that has at least 1 Auth Domain" in {
        val authDomainGroupName1 = WorkbenchGroupName("authDomain1")
        val authDomainGroup1 = BasicWorkbenchGroup(authDomainGroupName1, Set(), WorkbenchEmail("authDomain1@foo.com"))
        val authDomainGroupName2 = WorkbenchGroupName("authDomain2")
        val authDomainGroup2 = BasicWorkbenchGroup(authDomainGroupName2, Set(), WorkbenchEmail("authDomain2@foo.com"))

        dirDao.createGroup(authDomainGroup1).unsafeRunSync()
        dirDao.createGroup(authDomainGroup2).unsafeRunSync()
        dao.createResourceType(resourceType).unsafeRunSync()

        val resourceWithAuthDomain = Resource(resourceType.name, ResourceId("authDomainResource"), Set(authDomainGroupName1, authDomainGroupName2))
        dao.createResource(resourceWithAuthDomain).unsafeRunSync() shouldEqual resourceWithAuthDomain
      }

      "raises an error when AuthDomain does not exist" in {
        val authDomainGroupName1 = WorkbenchGroupName("authDomain1")
        val authDomainGroup1 = BasicWorkbenchGroup(authDomainGroupName1, Set(), WorkbenchEmail("authDomain1@foo.com"))
        val authDomainGroupName2 = WorkbenchGroupName("authDomain2")

        dirDao.createGroup(authDomainGroup1).unsafeRunSync()
        dao.createResourceType(resourceType).unsafeRunSync()
        val exception = intercept[PSQLException] {
          val resourceWithAuthDomain = Resource(resourceType.name, ResourceId("authDomainResource"), Set(authDomainGroupName1, authDomainGroupName2))
          dao.createResource(resourceWithAuthDomain).unsafeRunSync() shouldEqual resourceWithAuthDomain
        }

        exception.getSQLState shouldEqual PSQLStateExtensions.NULL_CONSTRAINT_VIOLATION
      }
    }

    "loadResourceAuthDomain" - {
      "ResourceNotFound" in {
        dao.createResourceType(resourceType).unsafeRunSync()
        dao.loadResourceAuthDomain(FullyQualifiedResourceId(resourceType.name, ResourceId("missing"))).unsafeRunSync() should be (ResourceNotFound)
      }

      "NotConstrained" in {
        dao.createResourceType(resourceType).unsafeRunSync()
        val resource = Resource(resourceType.name, ResourceId("verySpecialResource"), Set.empty)
        dao.createResource(resource).unsafeRunSync()
        dao.loadResourceAuthDomain(resource.fullyQualifiedId).unsafeRunSync() should be (NotConstrained)
      }

      "Constrained" in {
        val authDomainGroupName1 = WorkbenchGroupName("authDomain1")
        val authDomainGroup1 = BasicWorkbenchGroup(authDomainGroupName1, Set(), WorkbenchEmail("authDomain1@foo.com"))
        val authDomainGroupName2 = WorkbenchGroupName("authDomain2")
        val authDomainGroup2 = BasicWorkbenchGroup(authDomainGroupName2, Set(), WorkbenchEmail("authDomain2@foo.com"))

        dirDao.createGroup(authDomainGroup1).unsafeRunSync()
        dirDao.createGroup(authDomainGroup2).unsafeRunSync()
        dao.createResourceType(resourceType).unsafeRunSync()

        val resourceWithAuthDomain = Resource(resourceType.name, ResourceId("authDomainResource"), Set(authDomainGroupName1, authDomainGroupName2))
        dao.createResource(resourceWithAuthDomain).unsafeRunSync() shouldEqual resourceWithAuthDomain

        dao.loadResourceAuthDomain(resourceWithAuthDomain.fullyQualifiedId).unsafeRunSync() match {
          case Constrained(authDomain) => authDomain.toList should contain theSameElementsAs Set(authDomainGroupName1, authDomainGroupName2)
          case wrong => fail(s"result was $wrong, not Constrained")
        }
      }
    }

    "listResourceWithAuthdomains" - {
      "loads a resource with its auth domain" in {
        val authDomain = BasicWorkbenchGroup(WorkbenchGroupName("aufthDomain"), Set.empty, WorkbenchEmail("authDomain@groups.com"))
        dirDao.createGroup(authDomain).unsafeRunSync()
        dao.createResourceType(resourceType).unsafeRunSync()

        val resource = Resource(resourceType.name, ResourceId("resource"), Set(authDomain.id))
        dao.createResource(resource).unsafeRunSync()

        dao.listResourceWithAuthdomains(resource.fullyQualifiedId).unsafeRunSync() shouldEqual Option(resource)
      }

      "loads a resource even if its unconstrained" in {
        dao.createResourceType(resourceType).unsafeRunSync()

        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        dao.createResource(resource).unsafeRunSync()

        dao.listResourceWithAuthdomains(resource.fullyQualifiedId).unsafeRunSync() shouldEqual Option(resource)
      }

      "loads the correct resource if different resource types have a resource with a common name" in {
        val authDomain1 = BasicWorkbenchGroup(WorkbenchGroupName("authDomain1"), Set.empty, WorkbenchEmail("authDomain1@groups.com"))
        dirDao.createGroup(authDomain1).unsafeRunSync()
        val authDomain2 = BasicWorkbenchGroup(WorkbenchGroupName("authDomain2"), Set.empty, WorkbenchEmail("authDomain2@groups.com"))
        dirDao.createGroup(authDomain2).unsafeRunSync()

        dao.createResourceType(resourceType).unsafeRunSync()
        val secondResourceTypeName = ResourceTypeName("superAwesomeType")
        dao.createResourceType(resourceType.copy(name = secondResourceTypeName)).unsafeRunSync()

        val resource = Resource(resourceType.name, ResourceId("resource"), Set(authDomain1.id))
        val otherResource = Resource(secondResourceTypeName, ResourceId("resource"), Set(authDomain2.id))

        dao.createResource(resource).unsafeRunSync()
        dao.createResource(otherResource).unsafeRunSync()

        dao.listResourceWithAuthdomains(resource.fullyQualifiedId).unsafeRunSync() shouldEqual Option(resource)
      }

      "returns None when resource isn't found" in {
        dao.listResourceWithAuthdomains(FullyQualifiedResourceId(resourceTypeName, ResourceId("terribleResource"))).unsafeRunSync() shouldBe None
      }
    }

    "listResourcesWithAuthdomains" - {
      "finds the auth domains for the provided resources" in {
        val authDomain1 = BasicWorkbenchGroup(WorkbenchGroupName("authDomain1"), Set.empty, WorkbenchEmail("authDomain1@groups.com"))
        dirDao.createGroup(authDomain1).unsafeRunSync()
        val authDomain2 = BasicWorkbenchGroup(WorkbenchGroupName("authDomain2"), Set.empty, WorkbenchEmail("authDomain2@groups.com"))
        dirDao.createGroup(authDomain2).unsafeRunSync()

        dao.createResourceType(resourceType).unsafeRunSync()

        val resource1 = Resource(resourceType.name, ResourceId("resource1"), Set(authDomain1.id))
        val resource2 = Resource(resourceType.name, ResourceId("resource2"), Set(authDomain2.id))

        dao.createResource(resource1).unsafeRunSync()
        dao.createResource(resource2).unsafeRunSync()

        dao.listResourcesWithAuthdomains(resourceType.name, Set(resource1.resourceId, resource2.resourceId)).unsafeRunSync() shouldEqual Set(resource1, resource2)
      }

      "only returns actual resources" in {
        dao.createResourceType(resourceType).unsafeRunSync()
        dao.listResourcesWithAuthdomains(resourceType.name, Set(ResourceId("reallyAwfulResource"))).unsafeRunSync() shouldEqual Set.empty

        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        dao.createResource(resource).unsafeRunSync()

        dao.listResourcesWithAuthdomains(resourceType.name, Set(resource.resourceId, ResourceId("possiblyWorseResource"))).unsafeRunSync() shouldEqual Set(resource)
      }
    }

    "deleteResource" - {
      "deletes a resource" in {
        dao.createResourceType(resourceType).unsafeRunSync()
        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        dao.createResource(resource).unsafeRunSync()

        dao.listResourceWithAuthdomains(resource.fullyQualifiedId).unsafeRunSync() shouldEqual Option(resource)

        dao.deleteResource(resource.fullyQualifiedId).unsafeRunSync()

        dao.listResourceWithAuthdomains(resource.fullyQualifiedId).unsafeRunSync() shouldEqual None
      }
    }

    "listResourcesConstrainedByGroup" - {
      "can find all resources with a group in its auth domain" in {
        dao.createResourceType(resourceType).unsafeRunSync()
        val secondResourceType = resourceType.copy(name = ResourceTypeName("superAwesomeResourceType"))
        dao.createResourceType(secondResourceType).unsafeRunSync()

        val sharedAuthDomain = BasicWorkbenchGroup(WorkbenchGroupName("authDomain"), Set.empty, WorkbenchEmail("authDomain@very-secure.biz"))
        val otherGroup = BasicWorkbenchGroup(WorkbenchGroupName("notShared"), Set.empty, WorkbenchEmail("selfish@very-secure.biz"))
        dirDao.createGroup(sharedAuthDomain).unsafeRunSync()
        dirDao.createGroup(otherGroup).unsafeRunSync()

        val resource1 = Resource(resourceType.name, ResourceId("resource1"), Set(sharedAuthDomain.id))
        val resource2 = Resource(secondResourceType.name, ResourceId("resource2"), Set(sharedAuthDomain.id, otherGroup.id))
        dao.createResource(resource1).unsafeRunSync()
        dao.createResource(resource2).unsafeRunSync()

        dao.listResourcesConstrainedByGroup(sharedAuthDomain.id).unsafeRunSync() should contain theSameElementsAs Set(resource1, resource2)
      }

      "returns an empty list if group is not used in an auth domain" in {
        val group = BasicWorkbenchGroup(WorkbenchGroupName("boringGroup"), Set.empty, WorkbenchEmail("notAnAuthDomain@insecure.biz"))
        dirDao.createGroup(group).unsafeRunSync()

        dao.listResourcesConstrainedByGroup(group.id).unsafeRunSync() shouldEqual Set.empty
      }

      "returns an empty list if group doesn't exist" in {
        dao.listResourcesConstrainedByGroup(WorkbenchGroupName("notEvenReal")).unsafeRunSync() shouldEqual Set.empty
      }
    }
    val defaultGroupName = WorkbenchGroupName("group")
    val defaultGroup = BasicWorkbenchGroup(defaultGroupName, Set.empty, WorkbenchEmail("foo@bar.com"))
    val defaultUserId = WorkbenchUserId("testUser")
    val defaultUser = WorkbenchUser(defaultUserId, Option(GoogleSubjectId("testGoogleSubject")), WorkbenchEmail("user@foo.com"), Option(IdentityConcentratorId("testICId")))
    "createPolicy" - {
      "creates a policy" in {
        dao.createResourceType(resourceType).unsafeRunSync()
        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        dao.createResource(resource).unsafeRunSync()

        val policy = AccessPolicy(FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("policyName")), Set.empty, WorkbenchEmail("policy@email.com"), resourceType.roles.map(_.roleName), Set(readAction, writeAction), false)
        dao.createPolicy(policy).unsafeRunSync()
        dao.loadPolicy(policy.id).unsafeRunSync() shouldEqual Option(policy)
      }

      "detects duplicate policy" in {
        dao.createResourceType(resourceType).unsafeRunSync()
        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        dao.createResource(resource).unsafeRunSync()

        val policy = AccessPolicy(FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("policyName")), Set.empty, WorkbenchEmail("policy@email.com"), resourceType.roles.map(_.roleName), Set(readAction, writeAction), false)
        dao.createPolicy(policy).unsafeRunSync()

        val dupException = intercept[WorkbenchExceptionWithErrorReport] {
          dao.createPolicy(policy).unsafeRunSync()
        }

        dupException.errorReport.statusCode shouldEqual Some(StatusCodes.Conflict)
      }

      "can recreate a deleted policy" in {
        dao.createResourceType(resourceType).unsafeRunSync()
        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        dao.createResource(resource).unsafeRunSync()

        val policy = AccessPolicy(FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("policyName")), Set.empty, WorkbenchEmail("policy@email.com"), resourceType.roles.map(_.roleName), Set(readAction, writeAction), false)
        dao.createPolicy(policy).unsafeRunSync()
        dao.deletePolicy(policy.id).unsafeRunSync()
        dao.createPolicy(policy).unsafeRunSync()
      }

      "creates a policy with actions that don't already exist" in {
        dao.createResourceType(resourceType).unsafeRunSync()
        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        dao.createResource(resource).unsafeRunSync()

        val newAction = ResourceAction("new")
        val policy = AccessPolicy(FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("policyName")), Set.empty, WorkbenchEmail("policy@email.com"), resourceType.roles.map(_.roleName), Set(readAction, writeAction, newAction), false)
        dao.createPolicy(policy).unsafeRunSync()
        dao.loadPolicy(policy.id).unsafeRunSync() shouldEqual Option(policy)
      }

      "creates a policy with users and groups as members and loads those members" in {
        dao.createResourceType(resourceType).unsafeRunSync()
        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        dao.createResource(resource).unsafeRunSync()

        dirDao.createGroup(defaultGroup).unsafeRunSync()
        dirDao.createUser(defaultUser, samRequestContext).unsafeRunSync()

        val policy = AccessPolicy(FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("policyName")), Set(defaultGroup.id, defaultUser.id), WorkbenchEmail("policy@email.com"), resourceType.roles.map(_.roleName), Set(readAction, writeAction), false)
        dao.createPolicy(policy).unsafeRunSync()
        dao.loadPolicy(policy.id).unsafeRunSync() shouldEqual Option(policy)
      }
    }

    "loadPolicy" - {
      "returns None for a nonexistent policy" in {
        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        dao.loadPolicy(FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("fakePolicy"))).unsafeRunSync() shouldBe None
      }
    }

    "deletePolicy" - {
      "deletes a policy" in {
        dao.createResourceType(resourceType).unsafeRunSync()
        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        dao.createResource(resource).unsafeRunSync()

        dirDao.createGroup(defaultGroup).unsafeRunSync()
        dirDao.createUser(defaultUser, samRequestContext).unsafeRunSync()

        val policy = AccessPolicy(FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("policyName")), Set(defaultGroup.id, defaultUser.id), WorkbenchEmail("policy@email.com"), resourceType.roles.map(_.roleName), Set(readAction, writeAction), false)
        dao.createPolicy(policy).unsafeRunSync()
        dao.loadPolicy(policy.id).unsafeRunSync() shouldBe Option(policy)
        dao.deletePolicy(policy.id).unsafeRunSync()
        dao.loadPolicy(policy.id).unsafeRunSync() shouldBe None
        dirDao.loadGroup(WorkbenchGroupName(s"${resourceType.name}_${resource.resourceId}_${policy.id.accessPolicyName}")).unsafeRunSync() shouldBe None
      }

      "can handle deleting a policy that has already been deleted" in {
        dao.createResourceType(resourceType).unsafeRunSync()
        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        dao.createResource(resource).unsafeRunSync()

        dirDao.createGroup(defaultGroup).unsafeRunSync()
        dirDao.createUser(defaultUser, samRequestContext).unsafeRunSync()

        val policy = AccessPolicy(FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("policyName")), Set(defaultGroup.id, defaultUser.id), WorkbenchEmail("policy@email.com"), resourceType.roles.map(_.roleName), Set(readAction, writeAction), false)
        dao.createPolicy(policy).unsafeRunSync()
        dao.loadPolicy(policy.id).unsafeRunSync() shouldBe Option(policy)
        dao.deletePolicy(policy.id).unsafeRunSync()
        dao.loadPolicy(policy.id).unsafeRunSync() shouldBe None
        dao.deletePolicy(policy.id).unsafeRunSync()
      }
    }

    "listPublicAccessPolicies" - {
      "lists the public access policies for a given resource type" in {
        dao.createResourceType(resourceType).unsafeRunSync()
        val resourceId = ResourceId("resource")
        val resource = Resource(resourceType.name, resourceId, Set.empty)
        dao.createResource(resource).unsafeRunSync()

        val privatePolicyId = FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("privatePolicyName"))
        val publicPolicy1Id = FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("publicPolicy1Name"))
        val publicPolicy2Id = FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("publicPolicy2Name"))

        val privatePolicy = AccessPolicy(privatePolicyId, Set.empty, WorkbenchEmail("privatePolicy@email.com"), resourceType.roles.map(_.roleName), Set(readAction, writeAction), false)
        val publicPolicy1 = AccessPolicy(publicPolicy1Id, Set.empty, WorkbenchEmail("publicPolicy1@email.com"), resourceType.roles.map(_.roleName), Set(readAction, writeAction), true)
        val publicPolicy2 = AccessPolicy(publicPolicy2Id, Set.empty, WorkbenchEmail("publicPolicy2@email.com"), resourceType.roles.map(_.roleName), Set(readAction, writeAction), true)

        dao.createPolicy(privatePolicy).unsafeRunSync()
        dao.createPolicy(publicPolicy1).unsafeRunSync()
        dao.createPolicy(publicPolicy2).unsafeRunSync()

        val expectedResults = Set(ResourceIdAndPolicyName(resourceId, publicPolicy1.id.accessPolicyName),
          ResourceIdAndPolicyName(resourceId, publicPolicy2.id.accessPolicyName))

        dao.listPublicAccessPolicies(resourceTypeName).unsafeRunSync() should contain theSameElementsAs expectedResults
      }
    }

    "listPublicAccessPoliciesWithoutMembers" - {
      "lists the public access policies on a resource" in {
        dao.createResourceType(resourceType).unsafeRunSync()
        val resourceId = ResourceId("resource")
        val resource = Resource(resourceType.name, resourceId, Set.empty)
        dao.createResource(resource).unsafeRunSync()

        val wrongResource = Resource(resourceType.name, ResourceId("wrongResource"), Set.empty)
        dao.createResource(wrongResource).unsafeRunSync()

        val privatePolicyId = FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("privatePolicyName"))
        val publicPolicy1Id = FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("publicPolicy1Name"))
        val publicPolicy2Id = FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("publicPolicy2Name"))
        val wrongPublicPolicyId = FullyQualifiedPolicyId(wrongResource.fullyQualifiedId, AccessPolicyName("wrongPolicyName"))

        val privatePolicy = AccessPolicy(privatePolicyId, Set.empty, WorkbenchEmail("privatePolicy@email.com"), resourceType.roles.map(_.roleName), Set(readAction, writeAction), false)
        val publicPolicy1 = AccessPolicy(publicPolicy1Id, Set.empty, WorkbenchEmail("publicPolicy1@email.com"), resourceType.roles.map(_.roleName), Set(readAction, writeAction), true)
        val publicPolicy2 = AccessPolicy(publicPolicy2Id, Set.empty, WorkbenchEmail("publicPolicy2@email.com"), resourceType.roles.map(_.roleName), Set(readAction, writeAction), true)
        val wrongPublicPolicy = AccessPolicy(wrongPublicPolicyId, Set.empty, WorkbenchEmail("wrong@email.com"), resourceType.roles.map(_.roleName), Set(readAction, writeAction), true)

        dao.createPolicy(privatePolicy).unsafeRunSync()
        dao.createPolicy(publicPolicy1).unsafeRunSync()
        dao.createPolicy(publicPolicy2).unsafeRunSync()
        dao.createPolicy(wrongPublicPolicy).unsafeRunSync()

        val expectedResults = Set(publicPolicy1, publicPolicy2).map(policy =>
          AccessPolicyWithoutMembers(policy.id, policy.email, policy.roles, policy.actions, policy.public))

        dao.listPublicAccessPolicies(resource.fullyQualifiedId).unsafeRunSync() should contain theSameElementsAs expectedResults
      }
    }

    "listFlattenedPolicyMembers" - {
      "lists all members of a policy" in {
        val directMember = WorkbenchUser(WorkbenchUserId("direct"), None, WorkbenchEmail("direct@member.biz"), None)
        val subGroupMember = WorkbenchUser(WorkbenchUserId("indirect"), Option(GoogleSubjectId("googley")), WorkbenchEmail("subGroup@member.edu.biz"), Option(IdentityConcentratorId("icy")))
        val subSubGroupMember = WorkbenchUser(WorkbenchUserId("veryIndirect"), None, WorkbenchEmail("very@indirect.net"), None)
        val inTwoGroupsMember = WorkbenchUser(WorkbenchUserId("multipleGroups"), None, WorkbenchEmail("member@members.com"), None)
        val allMembers = Set(directMember, subGroupMember, subSubGroupMember, inTwoGroupsMember)

        val subSubGroup = BasicWorkbenchGroup(WorkbenchGroupName("subSubGroup"), Set(subSubGroupMember.id), WorkbenchEmail("subSub@groups.com"))
        val subGroup = BasicWorkbenchGroup(WorkbenchGroupName("subGroup"), Set(subSubGroup.id, subGroupMember.id, inTwoGroupsMember.id), WorkbenchEmail("sub@groups.com"))
        val secondGroup = BasicWorkbenchGroup(WorkbenchGroupName("secondGroup"), Set(inTwoGroupsMember.id), WorkbenchEmail("second@groups.com"))

        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        val policy = AccessPolicy(FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("policy")), Set(subGroup.id, secondGroup.id, directMember.id), WorkbenchEmail("policy@policy.com"), resourceType.roles.map(_.roleName), Set(readAction, writeAction), false)

        allMembers.map(((user) => dirDao.createUser(user, samRequestContext)).unsafeRunSync())
        Set(subSubGroup, subGroup, secondGroup).map(dirDao.createGroup(_).unsafeRunSync())

        dao.createResourceType(resourceType).unsafeRunSync()
        dao.createResource(resource).unsafeRunSync()
        dao.createPolicy(policy).unsafeRunSync()

        dao.listFlattenedPolicyMembers(policy.id).unsafeRunSync() should contain theSameElementsAs allMembers
      }
    }

    "listAccessPoliciesForUser" - {
      "lists the access policies on a resource that a user is a member of" in {
        val user = WorkbenchUser(WorkbenchUserId("user"), None, WorkbenchEmail("user@user.edu"), None)

        val subGroup = BasicWorkbenchGroup(WorkbenchGroupName("subGroup"), Set(user.id), WorkbenchEmail("sub@groups.com"))
        val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parent"), Set(subGroup.id), WorkbenchEmail("parent@groups.com"))

        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        val indirectPolicy = AccessPolicy(FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("indirect")), Set(parentGroup.id), WorkbenchEmail("indirect@policy.com"), resourceType.roles.map(_.roleName), Set(readAction, writeAction), false)
        val directPolicy = AccessPolicy(FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("direct")), Set(user.id), WorkbenchEmail("direct@policy.com"), resourceType.roles.map(_.roleName), Set(readAction, writeAction), false)
        val allPolicies = Set(indirectPolicy, directPolicy)
        val expectedResults = allPolicies.map(policy =>
          AccessPolicyWithoutMembers(policy.id, policy.email, policy.roles, policy.actions, policy.public))

        dirDao.createUser(user, samRequestContext).unsafeRunSync()
        dirDao.createGroup(subGroup).unsafeRunSync()
        dirDao.createGroup(parentGroup).unsafeRunSync()

        dao.createResourceType(resourceType).unsafeRunSync()
        dao.createResource(resource).unsafeRunSync()
        allPolicies.map(dao.createPolicy(_).unsafeRunSync())

        dao.listAccessPoliciesForUser(resource.fullyQualifiedId, user.id).unsafeRunSync() should contain theSameElementsAs expectedResults
      }

      "does not list policies on other resources the user is a member of" in {
        val user = WorkbenchUser(WorkbenchUserId("user"), None, WorkbenchEmail("user@user.edu"), None)

        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        val policy = AccessPolicy(FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("thisOne")), Set(user.id), WorkbenchEmail("correct@policy.com"), resourceType.roles.map(_.roleName), Set(readAction, writeAction), false)
        val otherResource = Resource(resourceType.name, ResourceId("notThisResource"), Set.empty)
        val otherPolicy = AccessPolicy(FullyQualifiedPolicyId(otherResource.fullyQualifiedId, AccessPolicyName("notThisOne")), Set(user.id), WorkbenchEmail("wrong@policy.com"), resourceType.roles.map(_.roleName), Set(readAction, writeAction), false)
        val allPolicies = Set(otherPolicy, policy)
        val expectedResults = Set(AccessPolicyWithoutMembers(policy.id, policy.email, policy.roles, policy.actions, policy.public))

        dirDao.createUser(user, samRequestContext).unsafeRunSync()

        dao.createResourceType(resourceType).unsafeRunSync()
        dao.createResource(resource).unsafeRunSync()
        dao.createResource(otherResource).unsafeRunSync()
        allPolicies.map(dao.createPolicy(_).unsafeRunSync())

        dao.listAccessPoliciesForUser(resource.fullyQualifiedId, user.id).unsafeRunSync() should contain theSameElementsAs expectedResults
      }
    }

    "setPolicyIsPublic" - {
      "can change whether a policy is public or private" in {
        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        val policy = AccessPolicy(FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("private")), Set.empty, WorkbenchEmail("private@policy.com"), resourceType.roles.map(_.roleName), Set(readAction, writeAction), false)

        dao.createResourceType(resourceType).unsafeRunSync()
        dao.createResource(resource).unsafeRunSync()
        dao.createPolicy(policy).unsafeRunSync()
        dao.loadPolicy(policy.id).unsafeRunSync().getOrElse(fail(s"failed to load policy ${policy.id}")).public shouldBe false

        dao.setPolicyIsPublic(policy.id, true).unsafeRunSync()
        dao.loadPolicy(policy.id).unsafeRunSync().getOrElse(fail(s"failed to load policy ${policy.id}")).public shouldBe true

        dao.setPolicyIsPublic(policy.id, false).unsafeRunSync()
        dao.loadPolicy(policy.id).unsafeRunSync().getOrElse(fail(s"failed to load policy ${policy.id}")).public shouldBe false
      }
    }

    "listAccessPolicies" - {
      "lists all the access policy names with their resource names that a user is in for a given resource type" in {
        val resource1 = Resource(resourceType.name, ResourceId("resource1"), Set.empty)
        val policy1 = AccessPolicy(FullyQualifiedPolicyId(resource1.fullyQualifiedId, AccessPolicyName("one")), Set(defaultUser.id), WorkbenchEmail("one@policy.com"), resourceType.roles.map(_.roleName), Set(readAction, writeAction), false)
        val resource2 = Resource(resourceType.name, ResourceId("resource2"), Set.empty)
        val policy2 = AccessPolicy(FullyQualifiedPolicyId(resource2.fullyQualifiedId, AccessPolicyName("two")), Set(defaultUser.id), WorkbenchEmail("two@policy.com"), resourceType.roles.map(_.roleName), Set(readAction, writeAction), false)

        dirDao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        dao.createResourceType(resourceType).unsafeRunSync()
        dao.createResource(resource1).unsafeRunSync()
        dao.createResource(resource2).unsafeRunSync()
        dao.createPolicy(policy1).unsafeRunSync()
        dao.createPolicy(policy2).unsafeRunSync()

        dao.listAccessPolicies(resourceType.name, defaultUser.id).unsafeRunSync() should contain theSameElementsAs Set(ResourceIdAndPolicyName(resource1.resourceId, policy1.id.accessPolicyName), ResourceIdAndPolicyName(resource2.resourceId, policy2.id.accessPolicyName))
      }

      "lists the access policies for a resource" in {
        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        val owner = AccessPolicy(FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("owner")), Set.empty, WorkbenchEmail("owner@policy.com"), resourceType.roles.map(_.roleName), Set(readAction, writeAction), false)
        val reader = AccessPolicy(FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("reader")), Set.empty, WorkbenchEmail("reader@policy.com"), resourceType.roles.map(_.roleName), Set(readAction, writeAction), false)

        dao.createResourceType(resourceType).unsafeRunSync()
        dao.createResource(resource).unsafeRunSync()
        dao.createPolicy(owner).unsafeRunSync()
        dao.createPolicy(reader).unsafeRunSync()

        dao.listAccessPolicies(resource.fullyQualifiedId).unsafeRunSync() should contain theSameElementsAs Set(owner, reader)
      }
    }

    "overwritePolicyMembers" - {
      "overwrites a policy's members" in {
        dao.createResourceType(resourceType).unsafeRunSync()
        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        dao.createResource(resource).unsafeRunSync()


        val secondUser = defaultUser.copy(id = WorkbenchUserId("foo"), googleSubjectId = Some(GoogleSubjectId("blablabla")), email = WorkbenchEmail("bar@baz.com"), identityConcentratorId = Some(IdentityConcentratorId("ooo")))
        dirDao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        dirDao.createUser(secondUser, samRequestContext).unsafeRunSync()

        val policy = AccessPolicy(FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("policyName")), Set(defaultUserId), WorkbenchEmail("policy@email.com"), resourceType.roles.map(_.roleName), Set(readAction, writeAction), false)
        dao.createPolicy(policy).unsafeRunSync()
        dao.loadPolicy(policy.id).unsafeRunSync() shouldEqual Option(policy)

        dao.listFlattenedPolicyMembers(policy.id).unsafeRunSync() shouldBe Set(defaultUser)

        dao.overwritePolicyMembers(policy.id, Set(secondUser.id)).unsafeRunSync()

        dao.listFlattenedPolicyMembers(policy.id).unsafeRunSync() shouldBe Set(secondUser)
      }

      "overwrites a policy's members when starting membership is empty" in {
        dao.createResourceType(resourceType).unsafeRunSync()
        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        dao.createResource(resource).unsafeRunSync()

        val secondUser = defaultUser.copy(id = WorkbenchUserId("foo"), googleSubjectId = Some(GoogleSubjectId("blablabla")), email = WorkbenchEmail("bar@baz.com"), identityConcentratorId = Some(IdentityConcentratorId("ooo")))

        dirDao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        dirDao.createUser(secondUser, samRequestContext).unsafeRunSync()

        val policy = AccessPolicy(FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("policyName")), Set.empty, WorkbenchEmail("policy@email.com"), resourceType.roles.map(_.roleName), Set(readAction, writeAction), false)
        dao.createPolicy(policy).unsafeRunSync()
        dao.loadPolicy(policy.id).unsafeRunSync() shouldEqual Option(policy)

        dao.listFlattenedPolicyMembers(policy.id).unsafeRunSync() shouldBe Set.empty

        dao.overwritePolicyMembers(policy.id, Set(secondUser.id)).unsafeRunSync()

        dao.listFlattenedPolicyMembers(policy.id).unsafeRunSync() shouldBe Set(secondUser)
      }
    }

    "overwritePolicy" - {
      "overwrites a policy" in {
        dao.createResourceType(resourceType).unsafeRunSync()
        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        dao.createResource(resource).unsafeRunSync()

        val secondUser = defaultUser.copy(id = WorkbenchUserId("foo"), googleSubjectId = Some(GoogleSubjectId("blablabla")), email = WorkbenchEmail("bar@baz.com"), identityConcentratorId = Some(IdentityConcentratorId("ooo")))

        dirDao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        dirDao.createUser(secondUser, samRequestContext).unsafeRunSync()

        val policy = AccessPolicy(FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("policyName")), Set(defaultUserId), WorkbenchEmail("policy@email.com"), resourceType.roles.map(_.roleName), Set(readAction, writeAction), false)
        dao.createPolicy(policy).unsafeRunSync()
        dao.loadPolicy(policy.id).unsafeRunSync() shouldEqual Option(policy)

        val newPolicy = policy.copy(members = Set(defaultUserId, secondUser.id), actions = Set(readAction), roles = Set.empty, public = true)
        dao.overwritePolicy(newPolicy).unsafeRunSync()

        dao.loadPolicy(policy.id).unsafeRunSync() shouldEqual Option(newPolicy)
      }

      "will not overwrite a policy if any of the new members don't exist" in {
        dao.createResourceType(resourceType).unsafeRunSync()
        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        dao.createResource(resource).unsafeRunSync()

        val secondUser = defaultUser.copy(id = WorkbenchUserId("foo"), googleSubjectId = Some(GoogleSubjectId("blablabla")), email = WorkbenchEmail("bar@baz.com"), identityConcentratorId = Some(IdentityConcentratorId("ooo")))

        dirDao.createUser(defaultUser, samRequestContext).unsafeRunSync()

        val policy = AccessPolicy(FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("policyName")), Set(defaultUserId), WorkbenchEmail("policy@email.com"), resourceType.roles.map(_.roleName), Set(readAction, writeAction), false)
        dao.createPolicy(policy).unsafeRunSync()
        dao.loadPolicy(policy.id).unsafeRunSync() shouldEqual Option(policy)

        val newPolicy = policy.copy(members = Set(defaultUserId, secondUser.id), actions = Set(ResourceAction("fakeAction")), roles = Set.empty, public = true)

        assertThrows[PSQLException] {
          dao.overwritePolicy(newPolicy).unsafeRunSync()
        }
        dao.loadPolicy(policy.id).unsafeRunSync() shouldEqual Option(policy)
      }
    }
  }
}
