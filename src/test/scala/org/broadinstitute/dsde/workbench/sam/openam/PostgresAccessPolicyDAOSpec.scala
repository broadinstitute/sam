package org.broadinstitute.dsde.workbench.sam.openam

import akka.http.scaladsl.model.StatusCodes
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.directory._
import org.broadinstitute.dsde.workbench.sam.openam.LoadResourceAuthDomainResult.{Constrained, NotConstrained, ResourceNotFound}
import org.scalatest.{BeforeAndAfterEach, FreeSpec, Matchers}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

class PostgresAccessPolicyDAOSpec extends FreeSpec with Matchers with BeforeAndAfterEach {
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

    val ownerRoleName = ResourceRoleName("role1")

    val ownerRole = ResourceRole(ownerRoleName, Set(writeAction, readAction))
    val readerRole = ResourceRole(ResourceRoleName("role2"), Set(readAction))
    val actionlessRole = ResourceRole(ResourceRoleName("cantDoNuthin"), Set()) // yeah, it's a double negative, sue me!

    val roles = Set(ownerRole, readerRole, actionlessRole)
    val resourceType = ResourceType(resourceTypeName, actionPatterns, roles, ownerRoleName, false)

    "createResourceType" - {
      "succeeds" in {
        dao.createResourceType(resourceType).unsafeRunSync() shouldEqual resourceType
      }

      "succeeds when there is exactly one Role that has no actions" in {
        val myResourceType = resourceType.copy(roles = Set(actionlessRole))
        dao.createResourceType(myResourceType).unsafeRunSync() shouldEqual myResourceType
      }

      // This test is hard to write at the moment.  We don't have an easy way to guarantee the race condition at exactly the right time.  Nor do
      // we have a good way to check if the data that was saved is what we intended.   This spec class could implement DatabaseSupport.  Or the
      // createResourceType could minimally return the ResourceTypePK in its results.  Or we need some way to get all
      // of the ResourceTypes from the DB and compare them to what we were trying to save.
      "succeeds and only creates 1 ResourceType when trying to create multiple identical ResourceTypes at the same time" in {

        pending

        // Since we can't directly force a collision at exactly the right time, kick off a bunch of inserts in parallel
        // and hope for the best.  <- That's how automated testing is supposed to work right?  Just cross your fingers?
        val allMyFutures = 0.to(20).map { _ =>
          dao.createResourceType(resourceType).unsafeToFuture()
        }

        Await.result(Future.sequence(allMyFutures), 5 seconds)
        // This is the part where I would want to assert that the database contains only one ResourceType
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

            "has the same ActionPatterns with modified descriptions" in pending
          }
        }

        "fails" - {
          "when the new ResourceType" - {
            "is removing at least one" - {
              "ActionPattern" in pending
              "Role" in pending
              "Role Action" in pending
            }

            // Not sure if we need this next test or not.  I don't want AuthDomain functionality in general to break if
            // we change the isConstrainable value on one of our patterns
            "removes an Auth Domain Constraint from an ActionPattern" in pending
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

      "raises an error when the ResourceType does not exist" is pending

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
    val defaultUser = WorkbenchUser(defaultUserId, Option(GoogleSubjectId("testGoogleSubject")), WorkbenchEmail("user@foo.com"))
    "createPolicy" - {
      "creates a policy" in {
        dao.createResourceType(resourceType).unsafeRunSync()
        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        dao.createResource(resource).unsafeRunSync()

        val policy = AccessPolicy(FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("policyName")), Set.empty, WorkbenchEmail("policy@email.com"), resourceType.roles.map(_.roleName), Set(readAction, writeAction), false)
        dao.createPolicy(policy).unsafeRunSync()
        dao.loadPolicy(policy.id).unsafeRunSync() shouldEqual Option(policy)
      }

      "creates a policy with users and groups as members and loads those members" in {
        dao.createResourceType(resourceType).unsafeRunSync()
        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        dao.createResource(resource).unsafeRunSync()

        dirDao.createGroup(defaultGroup).unsafeRunSync()
        dirDao.createUser(defaultUser).unsafeRunSync()

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
        // ideally we'd check that the associated group was deleted,
        // but there's no way to load the associated group without its name,
        // and we can't get its name without the resource's primary key which is not exposed anywhere,
        // so we aren't checking that the associated group was deleted
        dao.createResourceType(resourceType).unsafeRunSync()
        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        dao.createResource(resource).unsafeRunSync()

        dirDao.createGroup(defaultGroup).unsafeRunSync()
        dirDao.createUser(defaultUser).unsafeRunSync()

        val policy = AccessPolicy(FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("policyName")), Set(defaultGroup.id, defaultUser.id), WorkbenchEmail("policy@email.com"), resourceType.roles.map(_.roleName), Set(readAction, writeAction), false)
        dao.createPolicy(policy).unsafeRunSync()
        dao.loadPolicy(policy.id).unsafeRunSync() shouldBe Option(policy)
        dao.deletePolicy(policy.id).unsafeRunSync()
        dao.loadPolicy(policy.id).unsafeRunSync() shouldBe None
      }
    }
  }
}
