package org.broadinstitute.dsde.workbench.sam.dataAccess

import java.util.UUID
import akka.http.scaladsl.model.StatusCodes
import cats.effect.unsafe.implicits.global
import com.typesafe.config.ConfigFactory
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.{Generator, TestSupport}
import org.broadinstitute.dsde.workbench.sam.TestSupport.{databaseEnabled, samRequestContext}
import org.broadinstitute.dsde.workbench.sam.config.AppConfig
import org.broadinstitute.dsde.workbench.sam.dataAccess.LoadResourceAuthDomainResult.{Constrained, NotConstrained, ResourceNotFound}
import org.broadinstitute.dsde.workbench.sam.model._
import org.postgresql.util.PSQLException
import org.scalatest.BeforeAndAfterEach

import scala.concurrent.ExecutionContext.Implicits.{global => globalEc}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class PostgresAccessPolicyDAOSpec extends AnyFreeSpec with Matchers with BeforeAndAfterEach {
  val dao = new PostgresAccessPolicyDAO(TestSupport.dbRef, TestSupport.dbRef)
  val dirDao = new PostgresDirectoryDAO(TestSupport.dbRef, TestSupport.dbRef)

  override protected def beforeEach(): Unit = {
    TestSupport.truncateAll
    super.beforeEach()
  }

  "PostgresAccessPolicyDAO" - {
    val resourceTypeName = ResourceTypeName("awesomeType")
    val otherRsourceTypeName = ResourceTypeName("lessAwesomeType")

    val actionPatterns = Set(ResourceActionPattern("write", "description of pattern1", false), ResourceActionPattern("read", "description of pattern2", false))

    val writeAction = ResourceAction("write")
    val readAction = ResourceAction("read")

    val ownerRoleName = ResourceRoleName("owner")

    val ownerRole = ResourceRole(ownerRoleName, Set(writeAction, readAction))
    val readerRole = ResourceRole(ResourceRoleName("reader"), Set(readAction))
    val actionlessRole = ResourceRole(ResourceRoleName("cantDoNuthin"), Set()) // yeah, it's a double negative, sue me!

    val roles = Set(ownerRole, readerRole, actionlessRole)
    val resourceType = ResourceType(resourceTypeName, actionPatterns, roles, ownerRoleName, false)
    val otherResourceType = ResourceType(otherRsourceTypeName, actionPatterns, roles, ownerRoleName, false)

    "upsertResourceTypes" - {
      "creates resource types in config and is idempotent" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        val config = ConfigFactory.load()
        val appConfig = AppConfig.readConfig(config)
        dao.upsertResourceTypes(appConfig.resourceTypes, samRequestContext).unsafeRunSync() should contain theSameElementsAs (appConfig.resourceTypes.map(
          _.name
        ))
        dao.upsertResourceTypes(appConfig.resourceTypes, samRequestContext).unsafeRunSync() shouldBe empty
      }

      "only updates resource types that have changed" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        dao.upsertResourceTypes(Set(resourceType, otherResourceType), samRequestContext).unsafeRunSync() should contain theSameElementsAs (Set(
          resourceType.name,
          otherResourceType.name
        ))
        val updatedResourceType = resourceType.copy(reuseIds = !resourceType.reuseIds)
        dao.upsertResourceTypes(Set(updatedResourceType, otherResourceType), samRequestContext).unsafeRunSync() should contain theSameElementsAs (Set(
          updatedResourceType.name
        ))
        dao.loadResourceTypes(Set(updatedResourceType.name), samRequestContext).unsafeRunSync() should contain theSameElementsAs (Set(updatedResourceType))
      }
    }

    "createResourceType" - {
      "succeeds" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync() shouldEqual resourceType
        dao.loadResourceTypes(Set(resourceType.name), samRequestContext).unsafeRunSync() shouldEqual Set(resourceType)
      }

      "succeeds" - {
        "when there are no action patterns" in {
          assume(databaseEnabled, "-- skipping tests that talk to a real database")

          val myResourceType = resourceType.copy(actionPatterns = Set.empty)
          dao.createResourceType(myResourceType, samRequestContext).unsafeRunSync() shouldEqual myResourceType
          dao.loadResourceTypes(Set(myResourceType.name), samRequestContext).unsafeRunSync() shouldEqual Set(myResourceType)
        }

        "when there are no roles" in {
          assume(databaseEnabled, "-- skipping tests that talk to a real database")

          val myResourceType = resourceType.copy(roles = Set.empty)
          dao.createResourceType(myResourceType, samRequestContext).unsafeRunSync() shouldEqual myResourceType
          dao.loadResourceTypes(Set(myResourceType.name), samRequestContext).unsafeRunSync() shouldEqual Set(myResourceType)
        }

        "when there is exactly one Role that has no actions" in {
          assume(databaseEnabled, "-- skipping tests that talk to a real database")

          val myResourceType = resourceType.copy(roles = Set(actionlessRole))
          dao.createResourceType(myResourceType, samRequestContext).unsafeRunSync() shouldEqual myResourceType
          dao.loadResourceTypes(Set(myResourceType.name), samRequestContext).unsafeRunSync() shouldEqual Set(myResourceType)
        }

        // This test is hard to write at the moment.  We don't have an easy way to guarantee the race condition at exactly the right time.  Nor do
        // we have a good way to check if the data that was saved is what we intended.   This spec class could implement DatabaseSupport.  Or the
        // createResourceType could minimally return the ResourceTypePK in its results.  Or we need some way to get all
        // of the ResourceTypes from the DB and compare them to what we were trying to save.
        "and only creates 1 ResourceType when trying to create multiple identical ResourceTypes at the same time" in {
          assume(databaseEnabled, "-- skipping tests that talk to a real database")

          pending

          // Since we can't directly force a collision at exactly the right time, kick off a bunch of inserts in parallel
          // and hope for the best.  <- That's how automated testing is supposed to work right?  Just cross your fingers?
          val allMyFutures = 0.to(20).map { _ =>
            dao.createResourceType(resourceType, samRequestContext).unsafeToFuture()
          }

          Await.result(Future.sequence(allMyFutures), 5 seconds)
          // This is the part where I would want to assert that the database contains only one ResourceType
        }
      }

      "overwriting a ResourceType with the same name" - {
        "succeeds" - {
          "when the new ResourceType" - {
            "is identical" in {
              assume(databaseEnabled, "-- skipping tests that talk to a real database")

              dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
              dao.createResourceType(resourceType, samRequestContext).unsafeRunSync() shouldEqual resourceType
              dao.loadResourceTypes(Set(resourceType.name), samRequestContext).unsafeRunSync() shouldEqual Set(resourceType)
            }

            "adds new" - {
              "ActionPatterns" in {
                assume(databaseEnabled, "-- skipping tests that talk to a real database")

                val myActionPatterns = actionPatterns + ResourceActionPattern("coolNewPattern", "I am the coolest pattern EVER!  Mwahaha", true)
                val myResourceType = resourceType.copy(actionPatterns = myActionPatterns)

                dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
                dao.createResourceType(myResourceType, samRequestContext).unsafeRunSync() shouldEqual myResourceType
                dao.loadResourceTypes(Set(myResourceType.name), samRequestContext).unsafeRunSync() shouldEqual Set(myResourceType)
              }

              "Roles" in {
                assume(databaseEnabled, "-- skipping tests that talk to a real database")

                val myRoles = roles + ResourceRole(ResourceRoleName("blindWriter"), Set(writeAction))
                val myResourceType = resourceType.copy(roles = myRoles)

                dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
                dao.createResourceType(myResourceType, samRequestContext).unsafeRunSync() shouldEqual myResourceType
                dao.loadResourceTypes(Set(myResourceType.name), samRequestContext).unsafeRunSync() shouldEqual Set(myResourceType)
              }

              "Role Actions" in {
                assume(databaseEnabled, "-- skipping tests that talk to a real database")

                val myReaderRole = readerRole.copy(actions = Set(readAction, writeAction))
                val myRoles = Set(myReaderRole, ownerRole, actionlessRole)
                val myResourceType = resourceType.copy(roles = myRoles)

                dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
                dao.createResourceType(myResourceType, samRequestContext).unsafeRunSync() shouldEqual myResourceType
                dao.loadResourceTypes(Set(myResourceType.name), samRequestContext).unsafeRunSync() shouldEqual Set(myResourceType)
              }
            }

            "has the same ActionPatterns with modified descriptions" in {
              assume(databaseEnabled, "-- skipping tests that talk to a real database")

              val myActionPatterns = actionPatterns + ResourceActionPattern("coolNewPattern", "I am the coolest pattern EVER!  Mwahaha", true)
              val myResourceType = resourceType.copy(actionPatterns = myActionPatterns)

              val myActionPatternsNew = actionPatterns + ResourceActionPattern("coolNewPattern", "I am the NEWEST pattern EVER!  Mwahaha", true)

              val myUpdatedResourceType = myResourceType.copy(actionPatterns = myActionPatternsNew)

              dao.createResourceType(myResourceType, samRequestContext).unsafeRunSync() shouldEqual myResourceType

              dao.createResourceType(myUpdatedResourceType, samRequestContext).unsafeRunSync() shouldEqual myUpdatedResourceType
              dao.loadResourceTypes(Set(myUpdatedResourceType.name), samRequestContext).unsafeRunSync() shouldEqual Set(myUpdatedResourceType)
            }

            "is removing at least one" - {
              "ActionPattern" in {
                assume(databaseEnabled, "-- skipping tests that talk to a real database")

                val removeActionPattern = resourceType.copy(actionPatterns = actionPatterns.tail)
                dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
                dao.createResourceType(removeActionPattern, samRequestContext).unsafeRunSync() shouldEqual removeActionPattern
                dao.loadResourceTypes(Set(removeActionPattern.name), samRequestContext).unsafeRunSync() shouldEqual Set(removeActionPattern)
              }

              "Role" in {
                assume(databaseEnabled, "-- skipping tests that talk to a real database")

                val removeRole = resourceType.copy(roles = roles.tail)
                dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
                dao.createResourceType(removeRole, samRequestContext).unsafeRunSync() shouldEqual removeRole
                dao.loadResourceTypes(Set(removeRole.name), samRequestContext).unsafeRunSync() shouldEqual Set(removeRole)
              }

              "of its role's Actions" in {
                assume(databaseEnabled, "-- skipping tests that talk to a real database")

                val readerlessReader = readerRole.copy(actions = Set.empty)
                val newRoles = Set(ownerRole, readerlessReader, actionlessRole)
                val removeAction = resourceType.copy(roles = newRoles)
                dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
                dao.createResourceType(removeAction, samRequestContext).unsafeRunSync() shouldEqual removeAction
                dao.loadResourceTypes(Set(removeAction.name), samRequestContext).unsafeRunSync() shouldEqual Set(removeAction)
              }
            }
          }
        }

        "and removing a role" - {
          "doesn't affect existing policies with the role, but prevents using the role in new policies" in {
            assume(databaseEnabled, "-- skipping tests that talk to a real database")

            val initialRoles = Set(ownerRole, readerRole, actionlessRole)
            val initialResourceType = resourceType.copy(roles = initialRoles)

            val newRoles = Set(ownerRole, actionlessRole)
            val overwriteResourceType = initialResourceType.copy(roles = newRoles)

            val resource = Resource(initialResourceType.name, ResourceId("resource"), Set.empty)
            val beforeOverwritePolicy = AccessPolicy(
              FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("willHaveDeprecatedRole")),
              Set.empty,
              WorkbenchEmail("allowed@policy.com"),
              Set(readerRole.roleName, actionlessRole.roleName),
              Set.empty,
              Set.empty,
              false
            )
            val afterOverwritePolicy = AccessPolicy(
              FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("cannotHaveDeprecatedRole")),
              Set.empty,
              WorkbenchEmail("not_allowed@policy.com"),
              Set(readerRole.roleName, actionlessRole.roleName),
              Set.empty,
              Set.empty,
              false
            )

            dao.createResourceType(initialResourceType, samRequestContext).unsafeRunSync()
            dao.createResource(resource, samRequestContext).unsafeRunSync()
            dao.createPolicy(beforeOverwritePolicy, samRequestContext).unsafeRunSync()

            dao.createResourceType(overwriteResourceType, samRequestContext).unsafeRunSync()
            assertThrows[WorkbenchException] {
              dao.createPolicy(afterOverwritePolicy, samRequestContext).unsafeRunSync()
            }

            dao.loadPolicy(beforeOverwritePolicy.id, samRequestContext).unsafeRunSync() shouldBe Option(beforeOverwritePolicy)
            dao.loadPolicy(afterOverwritePolicy.id, samRequestContext).unsafeRunSync() shouldBe None
          }
        }

        "and removing an action" - {
          "from all roles will not affect policies that use the action" in {
            assume(databaseEnabled, "-- skipping tests that talk to a real database")

            val initialRoles = Set(ownerRole, readerRole, actionlessRole)
            val initialResourceType = resourceType.copy(roles = initialRoles)

            val readingOwner = ownerRole.copy(actions = Set(readAction))
            val newRoles = Set(readingOwner, readerRole, actionlessRole)
            val overwriteResourceType = initialResourceType.copy(roles = newRoles)

            val resource = Resource(initialResourceType.name, ResourceId("resource"), Set.empty)
            val beforeOverwritePolicy = AccessPolicy(
              FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("willHaveDeprecatedRole")),
              Set.empty,
              WorkbenchEmail("allowed@policy.com"),
              Set(ownerRole.roleName, actionlessRole.roleName),
              Set.empty,
              Set.empty,
              false
            )
            val afterOverwritePolicy = AccessPolicy(
              FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("cannotHaveDeprecatedRole")),
              Set.empty,
              WorkbenchEmail("not_allowed@policy.com"),
              Set(readingOwner.roleName, actionlessRole.roleName),
              Set(writeAction),
              Set.empty,
              false
            )

            dao.createResourceType(initialResourceType, samRequestContext).unsafeRunSync()
            dao.createResource(resource, samRequestContext).unsafeRunSync()
            dao.createPolicy(beforeOverwritePolicy, samRequestContext).unsafeRunSync()

            dao.createResourceType(overwriteResourceType, samRequestContext).unsafeRunSync()
            dao.createPolicy(afterOverwritePolicy, samRequestContext).unsafeRunSync()

            dao.loadPolicy(beforeOverwritePolicy.id, samRequestContext).unsafeRunSync() shouldBe Option(beforeOverwritePolicy)
            dao.loadPolicy(afterOverwritePolicy.id, samRequestContext).unsafeRunSync() shouldBe Option(afterOverwritePolicy)
          }

          "from a resource type's role will remove that action from all policies with that role" in {
            assume(databaseEnabled, "-- skipping tests that talk to a real database")

            val initialRoles = Set(ownerRole, readerRole, actionlessRole)
            val initialResourceType = resourceType.copy(roles = initialRoles)

            val readerlessReader = readerRole.copy(actions = Set.empty)
            val newRoles = Set(ownerRole, readerlessReader, actionlessRole)
            val overwriteResourceType = initialResourceType.copy(roles = newRoles)

            val resource = Resource(initialResourceType.name, ResourceId("resource"), Set.empty)
            val beforeOverwritePolicy = AccessPolicy(
              FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("willHaveDeprecatedRole")),
              Set.empty,
              WorkbenchEmail("allowed@policy.com"),
              Set(readerRole.roleName, actionlessRole.roleName),
              Set.empty,
              Set.empty,
              false
            )
            val afterOverwritePolicy = AccessPolicy(
              FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("cannotHaveDeprecatedRole")),
              Set.empty,
              WorkbenchEmail("not_allowed@policy.com"),
              Set(readerlessReader.roleName, actionlessRole.roleName),
              Set.empty,
              Set.empty,
              false
            )

            dao.createResourceType(initialResourceType, samRequestContext).unsafeRunSync()
            dao.createResource(resource, samRequestContext).unsafeRunSync()
            dao.createPolicy(beforeOverwritePolicy, samRequestContext).unsafeRunSync()

            dao.createResourceType(overwriteResourceType, samRequestContext).unsafeRunSync()
            dao.createPolicy(afterOverwritePolicy, samRequestContext).unsafeRunSync()

            dao.loadPolicy(beforeOverwritePolicy.id, samRequestContext).unsafeRunSync() shouldBe Option(beforeOverwritePolicy)
            dao.loadPolicy(afterOverwritePolicy.id, samRequestContext).unsafeRunSync() shouldBe Option(afterOverwritePolicy)
          }
        }
      }
    }

    "createResource" - {
      val resource = Resource(resourceType.name, ResourceId("verySpecialResource"), Set.empty)

      "succeeds when resource type exists" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        dao.createResource(resource, samRequestContext).unsafeRunSync() shouldEqual resource
      }

      "returns a WorkbenchExceptionWithErrorReport when a resource with the same name and type already exists" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        dao.createResource(resource, samRequestContext).unsafeRunSync()

        val exception = intercept[WorkbenchExceptionWithErrorReport] {
          dao.createResource(resource, samRequestContext).unsafeRunSync()
        }
        exception.errorReport.statusCode should equal(Some(StatusCodes.Conflict))
      }

      "raises an error when the ResourceType does not exist" in {
        val exception = intercept[Exception] {
          dao.createResource(resource, samRequestContext).unsafeRunSync()
        }
      }

      "can add a resource that has at least 1 Auth Domain" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        val authDomainGroupName1 = WorkbenchGroupName("authDomain1")
        val authDomainGroup1 = BasicWorkbenchGroup(authDomainGroupName1, Set(), WorkbenchEmail("authDomain1@foo.com"))
        val authDomainGroupName2 = WorkbenchGroupName("authDomain2")
        val authDomainGroup2 = BasicWorkbenchGroup(authDomainGroupName2, Set(), WorkbenchEmail("authDomain2@foo.com"))

        dirDao.createGroup(authDomainGroup1, samRequestContext = samRequestContext).unsafeRunSync()
        dirDao.createGroup(authDomainGroup2, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()

        val resourceWithAuthDomain = Resource(resourceType.name, ResourceId("authDomainResource"), Set(authDomainGroupName1, authDomainGroupName2))
        dao.createResource(resourceWithAuthDomain, samRequestContext).unsafeRunSync() shouldEqual resourceWithAuthDomain
      }

      "raises an error when AuthDomain does not exist" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        val authDomainGroupName1 = WorkbenchGroupName("authDomain1")
        val authDomainGroup1 = BasicWorkbenchGroup(authDomainGroupName1, Set(), WorkbenchEmail("authDomain1@foo.com"))
        val authDomainGroupName2 = WorkbenchGroupName("authDomain2")

        dirDao.createGroup(authDomainGroup1, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        intercept[WorkbenchException] {
          val resourceWithAuthDomain = Resource(resourceType.name, ResourceId("authDomainResource"), Set(authDomainGroupName1, authDomainGroupName2))
          dao.createResource(resourceWithAuthDomain, samRequestContext).unsafeRunSync() shouldEqual resourceWithAuthDomain
        }
      }

      "creates resource with parent" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        val child = Resource(resourceType.name, ResourceId("child"), Set.empty, parent = Option(resource.fullyQualifiedId))
        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        dao.createResource(resource, samRequestContext).unsafeRunSync() shouldEqual resource
        dao.createResource(child, samRequestContext).unsafeRunSync() shouldEqual child
        dao.getResourceParent(child.fullyQualifiedId, samRequestContext).unsafeRunSync() shouldBe Option(resource.fullyQualifiedId)
      }

      "raises error when parent does not exist" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        val child = Resource(resourceType.name, ResourceId("child"), Set.empty, parent = Option(resource.fullyQualifiedId))
        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        val exception = intercept[WorkbenchException] {
          dao.createResource(child, samRequestContext).unsafeRunSync()
        }
      }
    }

    "loadResourceAuthDomain" - {
      "ResourceNotFound" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        dao.loadResourceAuthDomain(FullyQualifiedResourceId(resourceType.name, ResourceId("missing")), samRequestContext).unsafeRunSync() should be(
          ResourceNotFound
        )
      }

      "NotConstrained" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        val resource = Resource(resourceType.name, ResourceId("verySpecialResource"), Set.empty)
        dao.createResource(resource, samRequestContext).unsafeRunSync()
        dao.loadResourceAuthDomain(resource.fullyQualifiedId, samRequestContext).unsafeRunSync() should be(NotConstrained)
      }

      "Constrained" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        val authDomainGroupName1 = WorkbenchGroupName("authDomain1")
        val authDomainGroup1 = BasicWorkbenchGroup(authDomainGroupName1, Set(), WorkbenchEmail("authDomain1@foo.com"))
        val authDomainGroupName2 = WorkbenchGroupName("authDomain2")
        val authDomainGroup2 = BasicWorkbenchGroup(authDomainGroupName2, Set(), WorkbenchEmail("authDomain2@foo.com"))

        dirDao.createGroup(authDomainGroup1, samRequestContext = samRequestContext).unsafeRunSync()
        dirDao.createGroup(authDomainGroup2, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()

        val resourceWithAuthDomain = Resource(resourceType.name, ResourceId("authDomainResource"), Set(authDomainGroupName1, authDomainGroupName2))
        dao.createResource(resourceWithAuthDomain, samRequestContext).unsafeRunSync() shouldEqual resourceWithAuthDomain

        dao.loadResourceAuthDomain(resourceWithAuthDomain.fullyQualifiedId, samRequestContext).unsafeRunSync() match {
          case Constrained(authDomain) => authDomain.toList should contain theSameElementsAs Set(authDomainGroupName1, authDomainGroupName2)
          case wrong => fail(s"result was $wrong, not Constrained")
        }
      }
    }

    "listResourceWithAuthdomains" - {
      "loads a resource with its auth domain" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        val authDomain = BasicWorkbenchGroup(WorkbenchGroupName("aufthDomain"), Set.empty, WorkbenchEmail("authDomain@groups.com"))
        dirDao.createGroup(authDomain, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()

        val resource = Resource(resourceType.name, ResourceId("resource"), Set(authDomain.id))
        dao.createResource(resource, samRequestContext).unsafeRunSync()

        dao.listResourceWithAuthdomains(resource.fullyQualifiedId, samRequestContext).unsafeRunSync() shouldEqual Option(resource)
      }

      "loads a resource even if its unconstrained" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()

        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        dao.createResource(resource, samRequestContext).unsafeRunSync()

        dao.listResourceWithAuthdomains(resource.fullyQualifiedId, samRequestContext).unsafeRunSync() shouldEqual Option(resource)
      }

      "loads the correct resource if different resource types have a resource with a common name" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        val authDomain1 = BasicWorkbenchGroup(WorkbenchGroupName("authDomain1"), Set.empty, WorkbenchEmail("authDomain1@groups.com"))
        dirDao.createGroup(authDomain1, samRequestContext = samRequestContext).unsafeRunSync()
        val authDomain2 = BasicWorkbenchGroup(WorkbenchGroupName("authDomain2"), Set.empty, WorkbenchEmail("authDomain2@groups.com"))
        dirDao.createGroup(authDomain2, samRequestContext = samRequestContext).unsafeRunSync()

        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        val secondResourceTypeName = ResourceTypeName("superAwesomeType")
        dao.createResourceType(resourceType.copy(name = secondResourceTypeName), samRequestContext).unsafeRunSync()

        val resource = Resource(resourceType.name, ResourceId("resource"), Set(authDomain1.id))
        val otherResource = Resource(secondResourceTypeName, ResourceId("resource"), Set(authDomain2.id))

        dao.createResource(resource, samRequestContext).unsafeRunSync()
        dao.createResource(otherResource, samRequestContext).unsafeRunSync()

        dao.listResourceWithAuthdomains(resource.fullyQualifiedId, samRequestContext).unsafeRunSync() shouldEqual Option(resource)
      }

      "returns None when resource isn't found" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        dao
          .listResourceWithAuthdomains(FullyQualifiedResourceId(resourceTypeName, ResourceId("terribleResource")), samRequestContext)
          .unsafeRunSync() shouldBe None
      }
    }

    "listResourcesWithAuthdomains" - {
      "finds the auth domains for the provided resources" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        val authDomain1 = BasicWorkbenchGroup(WorkbenchGroupName("authDomain1"), Set.empty, WorkbenchEmail("authDomain1@groups.com"))
        dirDao.createGroup(authDomain1, samRequestContext = samRequestContext).unsafeRunSync()
        val authDomain2 = BasicWorkbenchGroup(WorkbenchGroupName("authDomain2"), Set.empty, WorkbenchEmail("authDomain2@groups.com"))
        dirDao.createGroup(authDomain2, samRequestContext = samRequestContext).unsafeRunSync()

        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()

        val resource1 = Resource(resourceType.name, ResourceId("resource1"), Set(authDomain1.id))
        val resource2 = Resource(resourceType.name, ResourceId("resource2"), Set(authDomain2.id))

        dao.createResource(resource1, samRequestContext).unsafeRunSync()
        dao.createResource(resource2, samRequestContext).unsafeRunSync()

        dao.listResourcesWithAuthdomains(resourceType.name, Set(resource1.resourceId, resource2.resourceId), samRequestContext).unsafeRunSync() shouldEqual Set(
          resource1,
          resource2
        )
      }

      "only returns actual resources" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        dao.listResourcesWithAuthdomains(resourceType.name, Set(ResourceId("reallyAwfulResource")), samRequestContext).unsafeRunSync() shouldEqual Set.empty

        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        dao.createResource(resource, samRequestContext).unsafeRunSync()

        dao
          .listResourcesWithAuthdomains(resourceType.name, Set(resource.resourceId, ResourceId("possiblyWorseResource")), samRequestContext)
          .unsafeRunSync() shouldEqual Set(resource)
      }
    }

    "deleteResource" - {
      "deletes a resource" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        dao.createResource(resource, samRequestContext).unsafeRunSync()

        dao.listResourceWithAuthdomains(resource.fullyQualifiedId, samRequestContext).unsafeRunSync() shouldEqual Option(resource)

        dao.deleteResource(resource.fullyQualifiedId, samRequestContext).unsafeRunSync()

        dao.listResourceWithAuthdomains(resource.fullyQualifiedId, samRequestContext).unsafeRunSync() shouldEqual None
      }
    }

    "listSyncedAccessPolicyIdsOnResourcesConstrainedByGroup" - {
      "can find all synced policies for resources with the group in its auth domain" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        val secondResourceType = resourceType.copy(name = ResourceTypeName("superAwesomeResourceType"))
        dao.createResourceType(secondResourceType, samRequestContext).unsafeRunSync()

        val sharedAuthDomain = BasicWorkbenchGroup(WorkbenchGroupName("authDomain"), Set.empty, WorkbenchEmail("authDomain@very-secure.biz"))
        val otherGroup = BasicWorkbenchGroup(WorkbenchGroupName("notShared"), Set.empty, WorkbenchEmail("selfish@very-secure.biz"))
        dirDao.createGroup(sharedAuthDomain, samRequestContext = samRequestContext).unsafeRunSync()
        dirDao.createGroup(otherGroup, samRequestContext = samRequestContext).unsafeRunSync()

        val resource1FullyQualifiedId = FullyQualifiedResourceId(resourceType.name, ResourceId("resource1"))
        val resource2FullyQualifiedId = FullyQualifiedResourceId(secondResourceType.name, ResourceId("resource2"))
        val policy1 = AccessPolicy(
          FullyQualifiedPolicyId(resource1FullyQualifiedId, AccessPolicyName("policyName1")),
          Set.empty,
          WorkbenchEmail("policy1@email.com"),
          resourceType.roles.map(_.roleName),
          Set(readAction, writeAction),
          Set.empty,
          false
        )
        val policy2 = AccessPolicy(
          FullyQualifiedPolicyId(resource1FullyQualifiedId, AccessPolicyName("policyName2")),
          Set.empty,
          WorkbenchEmail("policy2@email.com"),
          resourceType.roles.map(_.roleName),
          Set(readAction, writeAction),
          Set.empty,
          false
        )
        val policy3 = AccessPolicy(
          FullyQualifiedPolicyId(resource2FullyQualifiedId, AccessPolicyName("policyName3")),
          Set.empty,
          WorkbenchEmail("policy3@email.com"),
          secondResourceType.roles.map(_.roleName),
          Set(readAction, writeAction),
          Set.empty,
          false
        )
        val resource1 =
          Resource(resource1FullyQualifiedId.resourceTypeName, resource1FullyQualifiedId.resourceId, Set(sharedAuthDomain.id), Set(policy1, policy2))
        val resource2 =
          Resource(resource2FullyQualifiedId.resourceTypeName, resource2FullyQualifiedId.resourceId, Set(sharedAuthDomain.id, otherGroup.id), Set(policy3))
        dao.createResource(resource1, samRequestContext).unsafeRunSync()
        dao.createResource(resource2, samRequestContext).unsafeRunSync()

        dirDao.updateSynchronizedDate(policy1.id, samRequestContext).unsafeRunSync()
        dirDao.updateSynchronizedDate(policy3.id, samRequestContext).unsafeRunSync()

        dao.listSyncedAccessPolicyIdsOnResourcesConstrainedByGroup(sharedAuthDomain.id, samRequestContext).unsafeRunSync() should contain theSameElementsAs Set(
          policy1.id,
          policy3.id
        )
      }

      "returns an empty list if group is not used in an auth domain" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        val group = BasicWorkbenchGroup(WorkbenchGroupName("boringGroup"), Set.empty, WorkbenchEmail("notAnAuthDomain@insecure.biz"))
        dirDao.createGroup(group, samRequestContext = samRequestContext).unsafeRunSync()

        dao.listSyncedAccessPolicyIdsOnResourcesConstrainedByGroup(group.id, samRequestContext).unsafeRunSync() shouldEqual Set.empty
      }

      "returns an empty list if group doesn't exist" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        dao.listSyncedAccessPolicyIdsOnResourcesConstrainedByGroup(WorkbenchGroupName("notEvenReal"), samRequestContext).unsafeRunSync() shouldEqual Set.empty
      }
    }

    val defaultGroupName = WorkbenchGroupName("group")
    val defaultGroup = BasicWorkbenchGroup(defaultGroupName, Set.empty, WorkbenchEmail("foo@bar.com"))
    val defaultUser = Generator.genWorkbenchUserBoth.sample.get
    "createPolicy" - {
      "creates a policy" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        dao.createResource(resource, samRequestContext).unsafeRunSync()

        val policy = AccessPolicy(
          FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("policyName")),
          Set.empty,
          WorkbenchEmail("policy@email.com"),
          resourceType.roles.map(_.roleName),
          Set(readAction, writeAction),
          Set.empty,
          false
        )
        dao.createPolicy(policy, samRequestContext).unsafeRunSync()
        dao.loadPolicy(policy.id, samRequestContext).unsafeRunSync() shouldEqual Option(policy)
      }

      "detects duplicate policy" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        dao.createResource(resource, samRequestContext).unsafeRunSync()

        val policy = AccessPolicy(
          FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("policyName")),
          Set.empty,
          WorkbenchEmail("policy@email.com"),
          resourceType.roles.map(_.roleName),
          Set(readAction, writeAction),
          Set.empty,
          false
        )
        dao.createPolicy(policy, samRequestContext).unsafeRunSync()

        val dupException = intercept[WorkbenchExceptionWithErrorReport] {
          dao.createPolicy(policy, samRequestContext).unsafeRunSync()
        }

        dupException.errorReport.statusCode shouldEqual Some(StatusCodes.Conflict)
      }

      "can recreate a deleted policy" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        dao.createResource(resource, samRequestContext).unsafeRunSync()

        val policy = AccessPolicy(
          FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("policyName")),
          Set.empty,
          WorkbenchEmail("policy@email.com"),
          resourceType.roles.map(_.roleName),
          Set(readAction, writeAction),
          Set.empty,
          false
        )
        dao.createPolicy(policy, samRequestContext).unsafeRunSync()
        dao.deletePolicy(policy.id, samRequestContext).unsafeRunSync()
        dao.createPolicy(policy, samRequestContext).unsafeRunSync()
      }

      "creates a policy with actions that don't already exist" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        dao.createResource(resource, samRequestContext).unsafeRunSync()

        val newAction = ResourceAction("new")
        val policy = AccessPolicy(
          FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("policyName")),
          Set.empty,
          WorkbenchEmail("policy@email.com"),
          resourceType.roles.map(_.roleName),
          Set(readAction, writeAction, newAction),
          Set.empty,
          false
        )
        dao.createPolicy(policy, samRequestContext).unsafeRunSync()
        dao.loadPolicy(policy.id, samRequestContext).unsafeRunSync() shouldEqual Option(policy)
      }

      "creates a policy with users and groups as members and loads those members" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        dao.createResource(resource, samRequestContext).unsafeRunSync()

        dirDao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dirDao.createUser(defaultUser, samRequestContext).unsafeRunSync()

        val policy = AccessPolicy(
          FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("policyName")),
          Set(defaultGroup.id, defaultUser.id),
          WorkbenchEmail("policy@email.com"),
          resourceType.roles.map(_.roleName),
          Set(readAction, writeAction),
          Set.empty,
          false
        )
        dao.createPolicy(policy, samRequestContext).unsafeRunSync()
        dao.loadPolicy(policy.id, samRequestContext).unsafeRunSync() shouldEqual Option(policy)
      }

      "creates descendant actions and roles" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        val otherResourceType = resourceType.copy(name = ResourceTypeName("otherResourceType"))
        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        val sameResourceTypeDescendant = AccessPolicyDescendantPermissions(resource.resourceTypeName, Set(writeAction), Set(ownerRoleName))
        val otherResourceTypeDescendant = AccessPolicyDescendantPermissions(otherResourceType.name, Set(readAction), Set(actionlessRole.roleName))
        val descendantPermissions = Set(sameResourceTypeDescendant, otherResourceTypeDescendant)
        val policy = AccessPolicy(
          FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("policyName")),
          Set.empty,
          WorkbenchEmail("policy@email.com"),
          Set(readerRole.roleName),
          Set.empty,
          descendantPermissions,
          false
        )

        val testResult = for {
          _ <- dao.createResourceType(resourceType, samRequestContext)
          _ <- dao.createResourceType(otherResourceType, samRequestContext)
          _ <- dao.createResource(resource, samRequestContext)
          _ <- dao.createPolicy(policy, samRequestContext)
          loadedPolicy <- dao.loadPolicy(policy.id, samRequestContext)
        } yield loadedPolicy shouldEqual Option(policy)

        testResult.unsafeRunSync()
      }
    }

    "loadPolicy" - {
      "returns None for a nonexistent policy" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        dao.loadPolicy(FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("fakePolicy")), samRequestContext).unsafeRunSync() shouldBe None
      }
    }

    "deletePolicy" - {
      "deletes a policy" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        dao.createResource(resource, samRequestContext).unsafeRunSync()

        dirDao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dirDao.createUser(defaultUser, samRequestContext).unsafeRunSync()

        val policy = AccessPolicy(
          FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("policyName")),
          Set(defaultGroup.id, defaultUser.id),
          WorkbenchEmail("policy@email.com"),
          resourceType.roles.map(_.roleName),
          Set(readAction, writeAction),
          Set.empty,
          false
        )
        dao.createPolicy(policy, samRequestContext).unsafeRunSync()
        dao.loadPolicy(policy.id, samRequestContext).unsafeRunSync() shouldBe Option(policy)
        dao.deletePolicy(policy.id, samRequestContext).unsafeRunSync()
        dao.loadPolicy(policy.id, samRequestContext).unsafeRunSync() shouldBe None
        dirDao
          .loadGroup(WorkbenchGroupName(s"${resourceType.name}_${resource.resourceId}_${policy.id.accessPolicyName}"), samRequestContext)
          .unsafeRunSync() shouldBe None
      }

      "can handle deleting a policy that has already been deleted" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        dao.createResource(resource, samRequestContext).unsafeRunSync()

        dirDao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dirDao.createUser(defaultUser, samRequestContext).unsafeRunSync()

        val policy = AccessPolicy(
          FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("policyName")),
          Set(defaultGroup.id, defaultUser.id),
          WorkbenchEmail("policy@email.com"),
          resourceType.roles.map(_.roleName),
          Set(readAction, writeAction),
          Set.empty,
          false
        )
        dao.createPolicy(policy, samRequestContext).unsafeRunSync()
        dao.loadPolicy(policy.id, samRequestContext).unsafeRunSync() shouldBe Option(policy)
        dao.deletePolicy(policy.id, samRequestContext).unsafeRunSync()
        dao.loadPolicy(policy.id, samRequestContext).unsafeRunSync() shouldBe None
        dao.deletePolicy(policy.id, samRequestContext).unsafeRunSync()
      }
    }

    "listPublicAccessPolicies" - {
      "lists the public access policies for a given resource type" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        val resourceId = ResourceId("resource")
        val resource = Resource(resourceType.name, resourceId, Set.empty)
        dao.createResource(resource, samRequestContext).unsafeRunSync()

        val privatePolicyId = FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("privatePolicyName"))
        val publicPolicy1Id = FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("publicPolicy1Name"))
        val publicPolicy2Id = FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("publicPolicy2Name"))

        val privatePolicy = AccessPolicy(
          privatePolicyId,
          Set.empty,
          WorkbenchEmail("privatePolicy@email.com"),
          resourceType.roles.map(_.roleName),
          Set(readAction, writeAction),
          Set.empty,
          false
        )
        val publicPolicy1 = AccessPolicy(
          publicPolicy1Id,
          Set.empty,
          WorkbenchEmail("publicPolicy1@email.com"),
          resourceType.roles.map(_.roleName),
          Set(readAction, writeAction),
          Set.empty,
          true
        )
        val publicPolicy2 = AccessPolicy(
          publicPolicy2Id,
          Set.empty,
          WorkbenchEmail("publicPolicy2@email.com"),
          resourceType.roles.map(_.roleName),
          Set(readAction, writeAction),
          Set.empty,
          true
        )

        dao.createPolicy(privatePolicy, samRequestContext).unsafeRunSync()
        dao.createPolicy(publicPolicy1, samRequestContext).unsafeRunSync()
        dao.createPolicy(publicPolicy2, samRequestContext).unsafeRunSync()

        val expectedResults =
          Set(ResourceIdAndPolicyName(resourceId, publicPolicy1.id.accessPolicyName), ResourceIdAndPolicyName(resourceId, publicPolicy2.id.accessPolicyName))

        dao.listPublicAccessPolicies(resourceTypeName, samRequestContext).unsafeRunSync() should contain theSameElementsAs expectedResults
      }
    }

    "listPublicAccessPoliciesWithoutMembers" - {
      "lists the public access policies on a resource" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        val resourceId = ResourceId("resource")
        val resource = Resource(resourceType.name, resourceId, Set.empty)
        dao.createResource(resource, samRequestContext).unsafeRunSync()

        val wrongResource = Resource(resourceType.name, ResourceId("wrongResource"), Set.empty)
        dao.createResource(wrongResource, samRequestContext).unsafeRunSync()

        val privatePolicyId = FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("privatePolicyName"))
        val publicPolicy1Id = FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("publicPolicy1Name"))
        val publicPolicy2Id = FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("publicPolicy2Name"))
        val wrongPublicPolicyId = FullyQualifiedPolicyId(wrongResource.fullyQualifiedId, AccessPolicyName("wrongPolicyName"))

        val privatePolicy = AccessPolicy(
          privatePolicyId,
          Set.empty,
          WorkbenchEmail("privatePolicy@email.com"),
          resourceType.roles.map(_.roleName),
          Set(readAction, writeAction),
          Set.empty,
          false
        )
        val publicPolicy1 = AccessPolicy(
          publicPolicy1Id,
          Set.empty,
          WorkbenchEmail("publicPolicy1@email.com"),
          resourceType.roles.map(_.roleName),
          Set(readAction, writeAction),
          Set.empty,
          true
        )
        val publicPolicy2 = AccessPolicy(
          publicPolicy2Id,
          Set.empty,
          WorkbenchEmail("publicPolicy2@email.com"),
          resourceType.roles.map(_.roleName),
          Set(readAction, writeAction),
          Set.empty,
          true
        )
        val wrongPublicPolicy = AccessPolicy(
          wrongPublicPolicyId,
          Set.empty,
          WorkbenchEmail("wrong@email.com"),
          resourceType.roles.map(_.roleName),
          Set(readAction, writeAction),
          Set.empty,
          true
        )

        dao.createPolicy(privatePolicy, samRequestContext).unsafeRunSync()
        dao.createPolicy(publicPolicy1, samRequestContext).unsafeRunSync()
        dao.createPolicy(publicPolicy2, samRequestContext).unsafeRunSync()
        dao.createPolicy(wrongPublicPolicy, samRequestContext).unsafeRunSync()

        val expectedResults =
          Set(publicPolicy1, publicPolicy2).map(policy => AccessPolicyWithoutMembers(policy.id, policy.email, policy.roles, policy.actions, policy.public))

        dao.listPublicAccessPolicies(resource.fullyQualifiedId, samRequestContext).unsafeRunSync() should contain theSameElementsAs expectedResults
      }
    }

    "listFlattenedPolicyMembers" - {
      "lists all members of a policy" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        val directMember = Generator.genWorkbenchUserGoogle.sample.get
        val subGroupMember = Generator.genWorkbenchUserBoth.sample.get
        val subSubGroupMember = Generator.genWorkbenchUserGoogle.sample.get
        val inTwoGroupsMember = Generator.genWorkbenchUserGoogle.sample.get
        val allMembers = Set(directMember, subGroupMember, subSubGroupMember, inTwoGroupsMember)

        val subSubGroup = BasicWorkbenchGroup(WorkbenchGroupName("subSubGroup"), Set(subSubGroupMember.id), WorkbenchEmail("subSub@groups.com"))
        val subGroup =
          BasicWorkbenchGroup(WorkbenchGroupName("subGroup"), Set(subSubGroup.id, subGroupMember.id, inTwoGroupsMember.id), WorkbenchEmail("sub@groups.com"))
        val secondGroup = BasicWorkbenchGroup(WorkbenchGroupName("secondGroup"), Set(inTwoGroupsMember.id), WorkbenchEmail("second@groups.com"))

        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        val policy = AccessPolicy(
          FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("policy")),
          Set(subGroup.id, secondGroup.id, directMember.id),
          WorkbenchEmail("policy@policy.com"),
          resourceType.roles.map(_.roleName),
          Set(readAction, writeAction),
          Set.empty,
          false
        )

        // control user/group to make sure fuction excludes something
        val inSomeOtherGroup = Generator.genWorkbenchUserGoogle.sample.get
        val someOtherGroup = BasicWorkbenchGroup(WorkbenchGroupName("someOtherGroup"), Set(inSomeOtherGroup.id), WorkbenchEmail("someOtherGroup@groups.com"))

        (allMembers + inSomeOtherGroup).map(user => dirDao.createUser(user, samRequestContext).unsafeRunSync())
        Set(subSubGroup, subGroup, secondGroup, someOtherGroup).map(group => dirDao.createGroup(group, samRequestContext = samRequestContext).unsafeRunSync())

        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        dao.createResource(resource, samRequestContext).unsafeRunSync()
        dao.createPolicy(policy, samRequestContext).unsafeRunSync()

        dao.listFlattenedPolicyMembers(policy.id, samRequestContext).unsafeRunSync() should contain theSameElementsAs allMembers
      }
    }

    "listAccessPoliciesForUser" - {
      "lists the access policies on a resource that a user is a member of" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        val user = Generator.genWorkbenchUserGoogle.sample.get

        val subGroup = BasicWorkbenchGroup(WorkbenchGroupName("subGroup"), Set(user.id), WorkbenchEmail("sub@groups.com"))
        val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parent"), Set(subGroup.id), WorkbenchEmail("parent@groups.com"))

        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        val indirectPolicy = AccessPolicy(
          FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("indirect")),
          Set(parentGroup.id),
          WorkbenchEmail("indirect@policy.com"),
          resourceType.roles.map(_.roleName),
          Set(readAction, writeAction),
          Set.empty,
          false
        )
        val directPolicy = AccessPolicy(
          FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("direct")),
          Set(user.id),
          WorkbenchEmail("direct@policy.com"),
          resourceType.roles.map(_.roleName),
          Set(readAction, writeAction),
          Set.empty,
          false
        )
        val allPolicies = Set(indirectPolicy, directPolicy)
        val expectedResults = allPolicies.map(policy => AccessPolicyWithoutMembers(policy.id, policy.email, policy.roles, policy.actions, policy.public))

        dirDao.createUser(user, samRequestContext).unsafeRunSync()
        dirDao.createGroup(subGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dirDao.createGroup(parentGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        dao.createResource(resource, samRequestContext).unsafeRunSync()
        allPolicies.map(policy => dao.createPolicy(policy, samRequestContext).unsafeRunSync())

        dao.listAccessPoliciesForUser(resource.fullyQualifiedId, user.id, samRequestContext).unsafeRunSync() should contain theSameElementsAs expectedResults
      }

      "does not list policies on other resources the user is a member of" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        val user = Generator.genWorkbenchUserGoogle.sample.get

        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        val policy = AccessPolicy(
          FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("thisOne")),
          Set(user.id),
          WorkbenchEmail("correct@policy.com"),
          resourceType.roles.map(_.roleName),
          Set(readAction, writeAction),
          Set.empty,
          false
        )
        val otherResource = Resource(resourceType.name, ResourceId("notThisResource"), Set.empty)
        val otherPolicy = AccessPolicy(
          FullyQualifiedPolicyId(otherResource.fullyQualifiedId, AccessPolicyName("notThisOne")),
          Set(user.id),
          WorkbenchEmail("wrong@policy.com"),
          resourceType.roles.map(_.roleName),
          Set(readAction, writeAction),
          Set.empty,
          false
        )
        val allPolicies = Set(otherPolicy, policy)
        val expectedResults = Set(AccessPolicyWithoutMembers(policy.id, policy.email, policy.roles, policy.actions, policy.public))

        dirDao.createUser(user, samRequestContext).unsafeRunSync()

        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        dao.createResource(resource, samRequestContext).unsafeRunSync()
        dao.createResource(otherResource, samRequestContext).unsafeRunSync()
        allPolicies.map(policy => dao.createPolicy(policy, samRequestContext).unsafeRunSync())

        dao.listAccessPoliciesForUser(resource.fullyQualifiedId, user.id, samRequestContext).unsafeRunSync() should contain theSameElementsAs expectedResults
      }
    }

    "listUserResourceActions" - {
      "lists the actions on a resource that a user is a member of" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        val user = Generator.genWorkbenchUserGoogle.sample.get

        val subGroup = BasicWorkbenchGroup(WorkbenchGroupName("subGroup"), Set(user.id), WorkbenchEmail("sub@groups.com"))
        val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parent"), Set(subGroup.id), WorkbenchEmail("parent@groups.com"))

        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        // backgroundPolicy makes sure there is something in the database that is excluded by the query
        val backgroundPolicy = AccessPolicy(
          FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("background")),
          Set.empty,
          WorkbenchEmail("background@policy.com"),
          Set(ownerRole.roleName),
          Set.empty,
          Set.empty,
          false
        )

        dirDao.createUser(user, samRequestContext).unsafeRunSync()
        dirDao.createGroup(subGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dirDao.createGroup(parentGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        dao.createResource(resource, samRequestContext).unsafeRunSync()
        dao.createPolicy(backgroundPolicy, samRequestContext).unsafeRunSync()

        val probePolicies = List(
          // user with role
          AccessPolicy(
            FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("probe")),
            Set(user.id),
            WorkbenchEmail("probe@policy.com"),
            Set(readerRole.roleName),
            Set.empty,
            Set.empty,
            false
          ),

          // user with action
          AccessPolicy(
            FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("probe")),
            Set(user.id),
            WorkbenchEmail("probe@policy.com"),
            Set.empty,
            Set(readAction),
            Set.empty,
            false
          ),

          // public with role
          AccessPolicy(
            FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("probe")),
            Set.empty,
            WorkbenchEmail("probe@policy.com"),
            Set(readerRole.roleName),
            Set.empty,
            Set.empty,
            true
          ),

          // public with action
          AccessPolicy(
            FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("probe")),
            Set.empty,
            WorkbenchEmail("probe@policy.com"),
            Set.empty,
            Set(readAction),
            Set.empty,
            true
          ),

          // group with role
          AccessPolicy(
            FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("probe")),
            Set(parentGroup.id),
            WorkbenchEmail("probe@policy.com"),
            Set(readerRole.roleName),
            Set.empty,
            Set.empty,
            false
          ),

          // group with action
          AccessPolicy(
            FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("probe")),
            Set(parentGroup.id),
            WorkbenchEmail("probe@policy.com"),
            Set.empty,
            Set(readAction),
            Set.empty,
            false
          )
        )

        probePolicies.foreach { probePolicy =>
          (for {
            _ <- dao.deletePolicy(probePolicy.id, samRequestContext)
            _ <- dao.createPolicy(probePolicy, samRequestContext)
            result <- dao.listUserResourceActions(resource.fullyQualifiedId, user.id, samRequestContext)
          } yield withClue(probePolicy) {
            result should contain theSameElementsAs Set(readAction)
          }).unsafeRunSync()
        }
      }

      "lists the actions on a resource that a user is a member of via ancestor" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        val user = Generator.genWorkbenchUserGoogle.sample.get

        val subGroup = BasicWorkbenchGroup(WorkbenchGroupName("subGroup"), Set(user.id), WorkbenchEmail("sub@groups.com"))
        val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parent"), Set(subGroup.id), WorkbenchEmail("parent@groups.com"))

        val parentResource = Resource(resourceType.name, ResourceId("parentResource"), Set.empty)
        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        // backgroundPolicy makes sure there is something in the database that is excluded by the query
        val backgroundPolicy = AccessPolicy(
          FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("background")),
          Set.empty,
          WorkbenchEmail("background@policy.com"),
          Set(ownerRole.roleName),
          Set.empty,
          Set.empty,
          false
        )

        dirDao.createUser(user, samRequestContext).unsafeRunSync()
        dirDao.createGroup(subGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dirDao.createGroup(parentGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        dao.createResource(parentResource, samRequestContext).unsafeRunSync()
        dao.createResource(resource, samRequestContext).unsafeRunSync()
        dao.setResourceParent(resource.fullyQualifiedId, parentResource.fullyQualifiedId, samRequestContext).unsafeRunSync()
        dao.createPolicy(backgroundPolicy, samRequestContext).unsafeRunSync()

        val probePolicies = List(
          // user with role
          AccessPolicy(
            FullyQualifiedPolicyId(parentResource.fullyQualifiedId, AccessPolicyName("probe")),
            Set(user.id),
            WorkbenchEmail("probe@policy.com"),
            Set.empty,
            Set.empty,
            Set(AccessPolicyDescendantPermissions(resourceType.name, Set.empty, Set(readerRole.roleName))),
            false
          ),

          // user with action
          AccessPolicy(
            FullyQualifiedPolicyId(parentResource.fullyQualifiedId, AccessPolicyName("probe")),
            Set(user.id),
            WorkbenchEmail("probe@policy.com"),
            Set.empty,
            Set.empty,
            Set(AccessPolicyDescendantPermissions(resourceType.name, Set(readAction), Set.empty)),
            false
          ),

          // public with role
          AccessPolicy(
            FullyQualifiedPolicyId(parentResource.fullyQualifiedId, AccessPolicyName("probe")),
            Set.empty,
            WorkbenchEmail("probe@policy.com"),
            Set.empty,
            Set.empty,
            Set(AccessPolicyDescendantPermissions(resourceType.name, Set.empty, Set(readerRole.roleName))),
            true
          ),

          // public with action
          AccessPolicy(
            FullyQualifiedPolicyId(parentResource.fullyQualifiedId, AccessPolicyName("probe")),
            Set.empty,
            WorkbenchEmail("probe@policy.com"),
            Set.empty,
            Set.empty,
            Set(AccessPolicyDescendantPermissions(resourceType.name, Set(readAction), Set.empty)),
            true
          ),

          // group with role
          AccessPolicy(
            FullyQualifiedPolicyId(parentResource.fullyQualifiedId, AccessPolicyName("probe")),
            Set(parentGroup.id),
            WorkbenchEmail("probe@policy.com"),
            Set.empty,
            Set.empty,
            Set(AccessPolicyDescendantPermissions(resourceType.name, Set.empty, Set(readerRole.roleName))),
            false
          ),

          // group with action
          AccessPolicy(
            FullyQualifiedPolicyId(parentResource.fullyQualifiedId, AccessPolicyName("probe")),
            Set(parentGroup.id),
            WorkbenchEmail("probe@policy.com"),
            Set.empty,
            Set.empty,
            Set(AccessPolicyDescendantPermissions(resourceType.name, Set(readAction), Set.empty)),
            false
          )
        )

        probePolicies.foreach { probePolicy =>
          (for {
            _ <- dao.deletePolicy(probePolicy.id, samRequestContext)
            _ <- dao.createPolicy(probePolicy, samRequestContext)
            childResult <- dao.listUserResourceActions(resource.fullyQualifiedId, user.id, samRequestContext)
            parentResult <- dao.listUserResourceActions(parentResource.fullyQualifiedId, user.id, samRequestContext)
          } yield withClue(probePolicy) {
            childResult should contain theSameElementsAs Set(readAction)
            parentResult shouldBe empty
          }).unsafeRunSync()
        }
      }

      "lists actions granted by nested roles on a resource a user is a member of" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        val user = Generator.genWorkbenchUserGoogle.sample.get

        val subGroup = BasicWorkbenchGroup(WorkbenchGroupName("subGroup"), Set(user.id), WorkbenchEmail("sub@groups.com"))
        val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parent"), Set(subGroup.id), WorkbenchEmail("parent@groups.com"))

        val includesRole = ResourceRole(ResourceRoleName("includes"), Set.empty, includedRoles = Set(readerRole.roleName))
        val descendsRole = ResourceRole(ResourceRoleName("descends"), Set.empty, descendantRoles = Map(resourceType.name -> Set(readerRole.roleName)))

        val nestedResourceType = resourceType.copy(name = ResourceTypeName("nested"), roles = Set(includesRole, descendsRole, readerRole))
        val includesResource = Resource(nestedResourceType.name, ResourceId("includes"), Set.empty)
        val descendsResource = Resource(resourceType.name, ResourceId("descends"), Set.empty, parent = Option(includesResource.fullyQualifiedId))
        // backgroundPolicy makes sure there is something in the database that is excluded by the query
        val includesBackgroundPolicy = AccessPolicy(
          FullyQualifiedPolicyId(includesResource.fullyQualifiedId, AccessPolicyName("background")),
          Set.empty,
          WorkbenchEmail("includesBackground@policy.com"),
          Set(includesRole.roleName),
          Set.empty,
          Set.empty,
          false
        )
        val descendsBackgroundPolicy = AccessPolicy(
          FullyQualifiedPolicyId(descendsResource.fullyQualifiedId, AccessPolicyName("background")),
          Set.empty,
          WorkbenchEmail("descendsBackground@policy.com"),
          Set(ownerRole.roleName),
          Set.empty,
          Set.empty,
          false
        )

        dirDao.createUser(user, samRequestContext).unsafeRunSync()
        dirDao.createGroup(subGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dirDao.createGroup(parentGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.upsertResourceTypes(Set(resourceType, nestedResourceType), samRequestContext).unsafeRunSync()
        dao.createResource(includesResource, samRequestContext).unsafeRunSync()
        dao.createResource(descendsResource, samRequestContext).unsafeRunSync()
        dao.createPolicy(includesBackgroundPolicy, samRequestContext).unsafeRunSync()
        dao.createPolicy(descendsBackgroundPolicy, samRequestContext).unsafeRunSync()

        val probePolicies = List(
          // user with role
          AccessPolicy(
            FullyQualifiedPolicyId(includesResource.fullyQualifiedId, AccessPolicyName("probe")),
            Set(user.id),
            WorkbenchEmail("probe@policy.com"),
            Set(includesRole.roleName, descendsRole.roleName),
            Set.empty,
            Set.empty,
            false
          ),
          // public with role
          AccessPolicy(
            FullyQualifiedPolicyId(includesResource.fullyQualifiedId, AccessPolicyName("probe")),
            Set.empty,
            WorkbenchEmail("probe@policy.com"),
            Set(includesRole.roleName, descendsRole.roleName),
            Set.empty,
            Set.empty,
            true
          ),
          // group with role
          AccessPolicy(
            FullyQualifiedPolicyId(includesResource.fullyQualifiedId, AccessPolicyName("probe")),
            Set(parentGroup.id),
            WorkbenchEmail("probe@policy.com"),
            Set(includesRole.roleName, descendsRole.roleName),
            Set.empty,
            Set.empty,
            false
          )
        )

        probePolicies.foreach { probePolicy =>
          (for {
            _ <- dao.deletePolicy(probePolicy.id, samRequestContext)
            _ <- dao.createPolicy(probePolicy, samRequestContext)
            includesResult <- dao.listUserResourceActions(includesResource.fullyQualifiedId, user.id, samRequestContext)
            descendsResult <- dao.listUserResourceActions(descendsResource.fullyQualifiedId, user.id, samRequestContext)
          } yield withClue(probePolicy) {
            includesResult should contain theSameElementsAs Set(readAction)
            descendsResult should contain theSameElementsAs Set(readAction)
          }).unsafeRunSync()
        }
      }

      "lists actions granted by nested roles on a resource a user is a member of via ancestor" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        val user = Generator.genWorkbenchUserGoogle.sample.get

        val subGroup = BasicWorkbenchGroup(WorkbenchGroupName("subGroup"), Set(user.id), WorkbenchEmail("sub@groups.com"))
        val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parent"), Set(subGroup.id), WorkbenchEmail("parent@groups.com"))

        val includesRole = ResourceRole(ResourceRoleName("includes"), Set.empty, includedRoles = Set(readerRole.roleName))
        val descendsRole = ResourceRole(ResourceRoleName("descends"), Set.empty, descendantRoles = Map(resourceType.name -> Set(readerRole.roleName)))
        val nestedResourceType = resourceType.copy(name = ResourceTypeName("nested"), roles = Set(includesRole, descendsRole, readerRole))
        val parentResource = Resource(nestedResourceType.name, ResourceId("parent"), Set.empty)
        val includesResource = Resource(nestedResourceType.name, ResourceId("includes"), Set.empty, parent = Option(parentResource.fullyQualifiedId))
        val descendsResource = Resource(resourceType.name, ResourceId("descends"), Set.empty, parent = Option(includesResource.fullyQualifiedId))
        // backgroundPolicy makes sure there is something in the database that is excluded by the query
        val includesBackgroundPolicy = AccessPolicy(
          FullyQualifiedPolicyId(includesResource.fullyQualifiedId, AccessPolicyName("background")),
          Set.empty,
          WorkbenchEmail("includesBackground@policy.com"),
          Set(includesRole.roleName),
          Set.empty,
          Set.empty,
          false
        )
        val descendsBackgroundPolicy = AccessPolicy(
          FullyQualifiedPolicyId(descendsResource.fullyQualifiedId, AccessPolicyName("background")),
          Set.empty,
          WorkbenchEmail("descendsBackground@policy.com"),
          Set(ownerRole.roleName),
          Set.empty,
          Set.empty,
          false
        )

        dirDao.createUser(user, samRequestContext).unsafeRunSync()
        dirDao.createGroup(subGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dirDao.createGroup(parentGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.upsertResourceTypes(Set(resourceType, nestedResourceType), samRequestContext).unsafeRunSync()
        dao.createResource(parentResource, samRequestContext).unsafeRunSync()
        dao.createResource(includesResource, samRequestContext).unsafeRunSync()
        dao.createResource(descendsResource, samRequestContext).unsafeRunSync()
        dao.createPolicy(includesBackgroundPolicy, samRequestContext).unsafeRunSync()
        dao.createPolicy(descendsBackgroundPolicy, samRequestContext).unsafeRunSync()

        val probePolicies = List(
          // user with role
          AccessPolicy(
            FullyQualifiedPolicyId(parentResource.fullyQualifiedId, AccessPolicyName("probe")),
            Set(user.id),
            WorkbenchEmail("probe@policy.com"),
            Set.empty,
            Set.empty,
            Set(AccessPolicyDescendantPermissions(nestedResourceType.name, Set.empty, Set(includesRole.roleName, descendsRole.roleName))),
            false
          ),
          // public with role
          AccessPolicy(
            FullyQualifiedPolicyId(parentResource.fullyQualifiedId, AccessPolicyName("probe")),
            Set.empty,
            WorkbenchEmail("probe@policy.com"),
            Set.empty,
            Set.empty,
            Set(AccessPolicyDescendantPermissions(nestedResourceType.name, Set.empty, Set(includesRole.roleName, descendsRole.roleName))),
            true
          ),
          // group with role
          AccessPolicy(
            FullyQualifiedPolicyId(parentResource.fullyQualifiedId, AccessPolicyName("probe")),
            Set(parentGroup.id),
            WorkbenchEmail("probe@policy.com"),
            Set.empty,
            Set.empty,
            Set(AccessPolicyDescendantPermissions(nestedResourceType.name, Set.empty, Set(includesRole.roleName, descendsRole.roleName))),
            false
          )
        )

        probePolicies.foreach { probePolicy =>
          (for {
            _ <- dao.deletePolicy(probePolicy.id, samRequestContext)
            _ <- dao.createPolicy(probePolicy, samRequestContext)
            includesResult <- dao.listUserResourceActions(includesResource.fullyQualifiedId, user.id, samRequestContext)
            descendsResult <- dao.listUserResourceActions(descendsResource.fullyQualifiedId, user.id, samRequestContext)
            parentResult <- dao.listUserResourceActions(parentResource.fullyQualifiedId, user.id, samRequestContext)
          } yield withClue(probePolicy) {
            includesResult should contain theSameElementsAs Set(readAction)
            descendsResult should contain theSameElementsAs Set(readAction)
            parentResult shouldBe empty
          }).unsafeRunSync()
        }
      }
    }

    "listUserResourcesWithRolesAndActions" - {
      def createResourceHierarchy(
          member: Option[WorkbenchSubject],
          actions: Set[ResourceAction],
          roles: Set[ResourceRoleName],
          public: Boolean,
          resourceType: ResourceTypeName = resourceTypeName
      ) = {
        val grandParentResource = Resource(resourceType, ResourceId(uuid), Set.empty)
        // parent is of different type to ensure it is excluded in query
        val parentResource = Resource(otherResourceType.name, ResourceId(uuid), Set.empty)
        val childResource = Resource(resourceType, ResourceId(uuid), Set.empty)

        // background policies exist to be excluded by the db query
        val backgroundPolicy = AccessPolicy(
          FullyQualifiedPolicyId(childResource.fullyQualifiedId, AccessPolicyName(uuid)),
          Set.empty,
          WorkbenchEmail(s"${uuid}@policy.com"),
          Set(ownerRole.roleName),
          Set.empty,
          Set.empty,
          false
        )
        val parentBackgroundPolicy = AccessPolicy(
          FullyQualifiedPolicyId(parentResource.fullyQualifiedId, AccessPolicyName(uuid)),
          Set.empty,
          WorkbenchEmail(s"${uuid}@policy.com"),
          Set(ownerRole.roleName),
          Set.empty,
          Set.empty,
          false
        )

        val probeRolesPolicy = AccessPolicy(
          FullyQualifiedPolicyId(grandParentResource.fullyQualifiedId, AccessPolicyName(uuid)),
          member.toSet,
          WorkbenchEmail(s"${uuid}@policy.com"),
          Set.empty,
          Set.empty,
          Set(AccessPolicyDescendantPermissions(resourceType, Set.empty, roles)),
          public
        )
        val probeActionsPolicy = AccessPolicy(
          FullyQualifiedPolicyId(grandParentResource.fullyQualifiedId, AccessPolicyName(uuid)),
          member.toSet,
          WorkbenchEmail(s"${uuid}@policy.com"),
          Set.empty,
          Set.empty,
          Set(AccessPolicyDescendantPermissions(resourceType, actions, Set.empty)),
          public
        )

        (for {
          _ <- dao.createResource(grandParentResource, samRequestContext)
          _ <- dao.createResource(parentResource, samRequestContext)
          _ <- dao.createResource(childResource, samRequestContext)
          _ <- dao.setResourceParent(childResource.fullyQualifiedId, parentResource.fullyQualifiedId, samRequestContext)
          _ <- dao.setResourceParent(parentResource.fullyQualifiedId, grandParentResource.fullyQualifiedId, samRequestContext)
          _ <- dao.createPolicy(probeRolesPolicy, samRequestContext)
          _ <- dao.createPolicy(probeActionsPolicy, samRequestContext)
          _ <- dao.createPolicy(backgroundPolicy, samRequestContext)
          _ <- dao.createPolicy(parentBackgroundPolicy, samRequestContext)
        } yield childResource).unsafeRunSync()
      }

      def createResource(
          member: Option[WorkbenchSubject],
          actions: Set[ResourceAction],
          roles: Set[ResourceRoleName],
          public: Boolean,
          resourceType: ResourceTypeName = resourceTypeName
      ) = {
        val resource = Resource(resourceType, ResourceId(uuid), Set.empty)

        // background policies exist to be excluded by the db query
        val backgroundPolicy = AccessPolicy(
          FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName(uuid)),
          Set.empty,
          WorkbenchEmail(s"${uuid}@policy.com"),
          Set(ownerRole.roleName),
          Set.empty,
          Set.empty,
          false
        )

        val probeRolesPolicy = AccessPolicy(
          FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName(uuid)),
          member.toSet,
          WorkbenchEmail(s"${uuid}@policy.com"),
          roles,
          Set.empty,
          Set.empty,
          public
        )
        val probeActionsPolicy = AccessPolicy(
          FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName(uuid)),
          member.toSet,
          WorkbenchEmail(s"${uuid}@policy.com"),
          Set.empty,
          actions,
          Set.empty,
          public
        )

        (for {
          _ <- dao.createResource(resource, samRequestContext)
          _ <- dao.createPolicy(probeRolesPolicy, samRequestContext)
          _ <- dao.createPolicy(probeActionsPolicy, samRequestContext)
          _ <- dao.createPolicy(backgroundPolicy, samRequestContext)
        } yield resource).unsafeRunSync()
      }

      "lists all a user's resources of a type" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        val user = Generator.genWorkbenchUserGoogle.sample.get

        val subGroup = BasicWorkbenchGroup(WorkbenchGroupName("subGroup"), Set(user.id), WorkbenchEmail("sub@groups.com"))
        val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parent"), Set(subGroup.id), WorkbenchEmail("parent@groups.com"))

        dirDao.createUser(user, samRequestContext).unsafeRunSync()
        dirDao.createGroup(subGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dirDao.createGroup(parentGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        dao.createResourceType(otherResourceType, samRequestContext).unsafeRunSync()

        val directAccessResource = createResource(Option(user.id), Set(writeAction), Set(readerRole.roleName), false)
        val publicResource = createResource(None, Set(writeAction), Set(readerRole.roleName), true)
        val groupAccessResource = createResource(Option(parentGroup.id), Set(writeAction), Set(readerRole.roleName), false)

        val directAccessChildResource = createResourceHierarchy(Option(user.id), Set(writeAction), Set(readerRole.roleName), false)
        val publicChildResource = createResourceHierarchy(None, Set(writeAction), Set(readerRole.roleName), true)
        val groupAccessChildResource = createResourceHierarchy(Option(parentGroup.id), Set(writeAction), Set(readerRole.roleName), false)

        // kitchenSink has inherited, direct and public-direct-via-group roles
        val kitchenSink = createResourceHierarchy(Option(user.id), Set.empty, Set(readerRole.roleName), false)
        val directProbePolicy = AccessPolicy(
          FullyQualifiedPolicyId(kitchenSink.fullyQualifiedId, AccessPolicyName(uuid)),
          Set(user.id),
          WorkbenchEmail(s"${uuid}@policy.com"),
          Set(ownerRole.roleName),
          Set.empty,
          Set.empty,
          false
        )
        val publicProbePolicy = AccessPolicy(
          FullyQualifiedPolicyId(kitchenSink.fullyQualifiedId, AccessPolicyName(uuid)),
          Set(parentGroup.id),
          WorkbenchEmail(s"${uuid}@policy.com"),
          Set(actionlessRole.roleName),
          Set.empty,
          Set.empty,
          true
        )
        dao.createPolicy(directProbePolicy, samRequestContext).unsafeRunSync()
        dao.createPolicy(publicProbePolicy, samRequestContext).unsafeRunSync()

        val expectedRolesAndActions = RolesAndActions(Set(readerRole.roleName), Set(writeAction))
        val expected = Seq(
          // inherited roles/actions
          ResourceIdWithRolesAndActions(directAccessChildResource.resourceId, RolesAndActions.empty, expectedRolesAndActions, RolesAndActions.empty),
          ResourceIdWithRolesAndActions(groupAccessChildResource.resourceId, RolesAndActions.empty, expectedRolesAndActions, RolesAndActions.empty),

          // inherited and public roles/actions
          ResourceIdWithRolesAndActions(publicChildResource.resourceId, RolesAndActions.empty, expectedRolesAndActions, expectedRolesAndActions),

          // direct roles/actions
          ResourceIdWithRolesAndActions(directAccessResource.resourceId, expectedRolesAndActions, RolesAndActions.empty, RolesAndActions.empty),
          ResourceIdWithRolesAndActions(groupAccessResource.resourceId, expectedRolesAndActions, RolesAndActions.empty, RolesAndActions.empty),

          // direct and public roles/actions
          ResourceIdWithRolesAndActions(publicResource.resourceId, expectedRolesAndActions, RolesAndActions.empty, expectedRolesAndActions),

          // direct, inherited and public roles
          ResourceIdWithRolesAndActions(
            kitchenSink.resourceId,
            RolesAndActions.fromRoles(directProbePolicy.roles ++ publicProbePolicy.roles),
            RolesAndActions.fromRoles(Set(readerRole.roleName)),
            RolesAndActions.fromPolicy(publicProbePolicy)
          )
        )

        val actual = dao.listUserResourcesWithRolesAndActions(resourceType.name, user.id, samRequestContext).unsafeRunSync()

        actual should contain theSameElementsAs expected
      }

      "lists all a user's resources of a type when granted access via nested roles" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        val user = Generator.genWorkbenchUserGoogle.sample.get
        val subGroup = BasicWorkbenchGroup(WorkbenchGroupName("subGroup"), Set(user.id), WorkbenchEmail("sub@groups.com"))
        val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parent"), Set(subGroup.id), WorkbenchEmail("parent@groups.com"))

        val includesRole = ResourceRole(ResourceRoleName("includes"), Set(writeAction), includedRoles = Set(readerRole.roleName))
        val descendsRole =
          ResourceRole(ResourceRoleName("descends"), Set(readAction, writeAction), descendantRoles = Map(resourceType.name -> Set(ownerRole.roleName)))
        val nestedResourceType =
          resourceType.copy(name = ResourceTypeName("nested"), roles = Set(includesRole, descendsRole, readerRole, ownerRole, actionlessRole))

        dirDao.createUser(user, samRequestContext).unsafeRunSync()
        dirDao.createGroup(subGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dirDao.createGroup(parentGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dao.upsertResourceTypes(Set(resourceType, nestedResourceType, otherResourceType), samRequestContext).unsafeRunSync()

        val directAccessResource =
          createResource(Option(user.id), Set(writeAction), Set(includesRole.roleName, descendsRole.roleName), false, nestedResourceType.name)
        val publicResource = createResource(None, Set(writeAction), Set(includesRole.roleName, descendsRole.roleName), true, nestedResourceType.name)
        val groupAccessResource =
          createResource(Option(parentGroup.id), Set(writeAction), Set(includesRole.roleName, descendsRole.roleName), false, nestedResourceType.name)

        val directAccessChildResource =
          createResourceHierarchy(Option(user.id), Set(writeAction), Set(includesRole.roleName, descendsRole.roleName), false, nestedResourceType.name)
        val publicChildResource =
          createResourceHierarchy(None, Set(writeAction), Set(includesRole.roleName, descendsRole.roleName), true, nestedResourceType.name)
        val groupAccessChildResource =
          createResourceHierarchy(Option(parentGroup.id), Set(writeAction), Set(includesRole.roleName, descendsRole.roleName), false, nestedResourceType.name)

        // kitchenSink has inherited, direct and public-direct-via-group roles
        val kitchenSink = createResourceHierarchy(Option(user.id), Set.empty, Set(includesRole.roleName, descendsRole.roleName), false, nestedResourceType.name)
        val directProbePolicy = AccessPolicy(
          FullyQualifiedPolicyId(kitchenSink.fullyQualifiedId, AccessPolicyName(uuid)),
          Set(user.id),
          WorkbenchEmail(s"${uuid}@policy.com"),
          Set(ownerRole.roleName),
          Set.empty,
          Set.empty,
          false
        )
        val publicProbePolicy = AccessPolicy(
          FullyQualifiedPolicyId(kitchenSink.fullyQualifiedId, AccessPolicyName(uuid)),
          Set(parentGroup.id),
          WorkbenchEmail(s"${uuid}@policy.com"),
          Set(actionlessRole.roleName),
          Set.empty,
          Set.empty,
          true
        )
        dao.createPolicy(directProbePolicy, samRequestContext).unsafeRunSync()
        dao.createPolicy(publicProbePolicy, samRequestContext).unsafeRunSync()

        // note that the reader role is only granted by being included in includesRole
        val expectedRolesAndActions = RolesAndActions(Set(readerRole.roleName, includesRole.roleName, descendsRole.roleName), Set(writeAction))
        val expected = Seq(
          // inherited roles/actions
          ResourceIdWithRolesAndActions(directAccessChildResource.resourceId, RolesAndActions.empty, expectedRolesAndActions, RolesAndActions.empty),
          ResourceIdWithRolesAndActions(groupAccessChildResource.resourceId, RolesAndActions.empty, expectedRolesAndActions, RolesAndActions.empty),

          // inherited and public roles/actions
          ResourceIdWithRolesAndActions(publicChildResource.resourceId, RolesAndActions.empty, expectedRolesAndActions, expectedRolesAndActions),

          // direct roles/actions
          ResourceIdWithRolesAndActions(directAccessResource.resourceId, expectedRolesAndActions, RolesAndActions.empty, RolesAndActions.empty),
          ResourceIdWithRolesAndActions(groupAccessResource.resourceId, expectedRolesAndActions, RolesAndActions.empty, RolesAndActions.empty),

          // direct and public roles/actions
          ResourceIdWithRolesAndActions(publicResource.resourceId, expectedRolesAndActions, RolesAndActions.empty, expectedRolesAndActions),

          // direct, inherited and public roles
          ResourceIdWithRolesAndActions(
            kitchenSink.resourceId,
            RolesAndActions.fromRoles(directProbePolicy.roles ++ publicProbePolicy.roles),
            RolesAndActions.fromRoles(Set(readerRole.roleName, includesRole.roleName, descendsRole.roleName)),
            RolesAndActions.fromPolicy(publicProbePolicy)
          )
        )

        val actual = dao.listUserResourcesWithRolesAndActions(nestedResourceType.name, user.id, samRequestContext).unsafeRunSync()
        actual should contain theSameElementsAs expected

        // create descendant resources that user has owner role on via same set of access methods above (direct, group, public, ancestry, etc.) to test descendant roles
        val descendsDirectAccessResource = Resource(resourceType.name, ResourceId(uuid), Set.empty, parent = Option(directAccessResource.fullyQualifiedId))
        val descendsPublicResource = Resource(resourceType.name, ResourceId(uuid), Set.empty, parent = Option(publicResource.fullyQualifiedId))
        val descendsGroupAccessResource = Resource(resourceType.name, ResourceId(uuid), Set.empty, parent = Option(groupAccessResource.fullyQualifiedId))
        val descendsDirectAccessChildResource =
          Resource(resourceType.name, ResourceId(uuid), Set.empty, parent = Option(directAccessChildResource.fullyQualifiedId))
        val descendsPublicChildResource = Resource(resourceType.name, ResourceId(uuid), Set.empty, parent = Option(publicChildResource.fullyQualifiedId))
        val descendsGroupAccessChildResource =
          Resource(resourceType.name, ResourceId(uuid), Set.empty, parent = Option(groupAccessChildResource.fullyQualifiedId))
        val descendsKitchenSink = Resource(resourceType.name, ResourceId(uuid), Set.empty, parent = Option(kitchenSink.fullyQualifiedId))
        val allDescendantResources = Set(
          descendsDirectAccessResource,
          descendsPublicResource,
          descendsGroupAccessResource,
          descendsDirectAccessChildResource,
          descendsPublicChildResource,
          descendsGroupAccessChildResource,
          descendsKitchenSink
        )
        allDescendantResources.map(resource => dao.createResource(resource, samRequestContext).unsafeRunSync())

        // all resources should have inherited owner roles only with exception of public resources which also list owner role among the public roles
        val expectedDescendantResult = Seq(
          // inherited roles/actions
          ResourceIdWithRolesAndActions(
            descendsDirectAccessChildResource.resourceId,
            RolesAndActions.empty,
            RolesAndActions(Set(ownerRole.roleName), Set.empty),
            RolesAndActions.empty
          ),
          ResourceIdWithRolesAndActions(
            descendsGroupAccessChildResource.resourceId,
            RolesAndActions.empty,
            RolesAndActions(Set(ownerRole.roleName), Set.empty),
            RolesAndActions.empty
          ),
          ResourceIdWithRolesAndActions(
            descendsDirectAccessResource.resourceId,
            RolesAndActions.empty,
            RolesAndActions(Set(ownerRole.roleName), Set.empty),
            RolesAndActions.empty
          ),
          ResourceIdWithRolesAndActions(
            descendsGroupAccessResource.resourceId,
            RolesAndActions.empty,
            RolesAndActions(Set(ownerRole.roleName), Set.empty),
            RolesAndActions.empty
          ),
          ResourceIdWithRolesAndActions(
            descendsKitchenSink.resourceId,
            RolesAndActions.empty,
            RolesAndActions(Set(ownerRole.roleName), Set.empty),
            RolesAndActions.empty
          ),

          // inherited and public roles/actions
          ResourceIdWithRolesAndActions(
            descendsPublicChildResource.resourceId,
            RolesAndActions.empty,
            RolesAndActions(Set(ownerRole.roleName), Set.empty),
            RolesAndActions(Set(ownerRole.roleName), Set.empty)
          ),
          ResourceIdWithRolesAndActions(
            descendsPublicResource.resourceId,
            RolesAndActions.empty,
            RolesAndActions(Set(ownerRole.roleName), Set.empty),
            RolesAndActions(Set(ownerRole.roleName), Set.empty)
          )
        )

        val actualDescendantResult = dao.listUserResourcesWithRolesAndActions(resourceType.name, user.id, samRequestContext).unsafeRunSync()
        actualDescendantResult should contain theSameElementsAs expectedDescendantResult
      }
    }

    "listUserResourceRoles" - {
      "lists the roles on a resource that a user is a member of" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        val user = Generator.genWorkbenchUserGoogle.sample.get

        val subGroup = BasicWorkbenchGroup(WorkbenchGroupName("subGroup"), Set(user.id), WorkbenchEmail("sub@groups.com"))
        val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parent"), Set(subGroup.id), WorkbenchEmail("parent@groups.com"))

        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        // backgroundPolicy makes sure there is something in the database that is excluded by the query
        val backgroundPolicy = AccessPolicy(
          FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("background")),
          Set.empty,
          WorkbenchEmail("background@policy.com"),
          Set(ownerRole.roleName),
          Set.empty,
          Set.empty,
          false
        )

        dirDao.createUser(user, samRequestContext).unsafeRunSync()
        dirDao.createGroup(subGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dirDao.createGroup(parentGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        dao.createResource(resource, samRequestContext).unsafeRunSync()
        dao.createPolicy(backgroundPolicy, samRequestContext).unsafeRunSync()

        val probePolicies = List(
          // user with role
          AccessPolicy(
            FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("probe")),
            Set(user.id),
            WorkbenchEmail("probe@policy.com"),
            Set(readerRole.roleName),
            Set.empty,
            Set.empty,
            false
          ),

          // public with role
          AccessPolicy(
            FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("probe")),
            Set.empty,
            WorkbenchEmail("probe@policy.com"),
            Set(readerRole.roleName),
            Set.empty,
            Set.empty,
            true
          ),

          // group with role
          AccessPolicy(
            FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("probe")),
            Set(parentGroup.id),
            WorkbenchEmail("probe@policy.com"),
            Set(readerRole.roleName),
            Set.empty,
            Set.empty,
            false
          )
        )

        probePolicies.foreach { probePolicy =>
          (for {
            _ <- dao.deletePolicy(probePolicy.id, samRequestContext)
            _ <- dao.createPolicy(probePolicy, samRequestContext)
            result <- dao.listUserResourceRoles(resource.fullyQualifiedId, user.id, samRequestContext)
          } yield withClue(probePolicy) {
            result should contain theSameElementsAs Set(readerRole.roleName)
          }).unsafeRunSync()
        }
      }

      "lists the roles on a resource that a user is a member of via ancestor" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        val user = Generator.genWorkbenchUserGoogle.sample.get

        val subGroup = BasicWorkbenchGroup(WorkbenchGroupName("subGroup"), Set(user.id), WorkbenchEmail("sub@groups.com"))
        val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parent"), Set(subGroup.id), WorkbenchEmail("parent@groups.com"))

        val parentResource = Resource(resourceType.name, ResourceId("parentResource"), Set.empty)
        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        // backgroundPolicy makes sure there is something in the database that is excluded by the query
        val backgroundPolicy = AccessPolicy(
          FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("background")),
          Set.empty,
          WorkbenchEmail("background@policy.com"),
          Set(ownerRole.roleName),
          Set.empty,
          Set.empty,
          false
        )

        dirDao.createUser(user, samRequestContext).unsafeRunSync()
        dirDao.createGroup(subGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dirDao.createGroup(parentGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        dao.createResource(parentResource, samRequestContext).unsafeRunSync()
        dao.createResource(resource, samRequestContext).unsafeRunSync()
        dao.setResourceParent(resource.fullyQualifiedId, parentResource.fullyQualifiedId, samRequestContext).unsafeRunSync()
        dao.createPolicy(backgroundPolicy, samRequestContext).unsafeRunSync()

        val probePolicies = List(
          // user with role
          AccessPolicy(
            FullyQualifiedPolicyId(parentResource.fullyQualifiedId, AccessPolicyName("probe")),
            Set(user.id),
            WorkbenchEmail("probe@policy.com"),
            Set.empty,
            Set.empty,
            Set(AccessPolicyDescendantPermissions(resourceType.name, Set.empty, Set(readerRole.roleName))),
            false
          ),

          // public with role
          AccessPolicy(
            FullyQualifiedPolicyId(parentResource.fullyQualifiedId, AccessPolicyName("probe")),
            Set.empty,
            WorkbenchEmail("probe@policy.com"),
            Set.empty,
            Set.empty,
            Set(AccessPolicyDescendantPermissions(resourceType.name, Set.empty, Set(readerRole.roleName))),
            true
          ),

          // group with role
          AccessPolicy(
            FullyQualifiedPolicyId(parentResource.fullyQualifiedId, AccessPolicyName("probe")),
            Set(parentGroup.id),
            WorkbenchEmail("probe@policy.com"),
            Set.empty,
            Set.empty,
            Set(AccessPolicyDescendantPermissions(resourceType.name, Set.empty, Set(readerRole.roleName))),
            false
          )
        )

        probePolicies.foreach { probePolicy =>
          (for {
            _ <- dao.deletePolicy(probePolicy.id, samRequestContext)
            _ <- dao.createPolicy(probePolicy, samRequestContext)
            childResult <- dao.listUserResourceRoles(resource.fullyQualifiedId, user.id, samRequestContext)
            parentResult <- dao.listUserResourceRoles(parentResource.fullyQualifiedId, user.id, samRequestContext)
          } yield withClue(probePolicy) {
            childResult should contain theSameElementsAs Set(readerRole.roleName)
            parentResult shouldBe empty
          }).unsafeRunSync()
        }
      }

      "lists included and descendant roles on a resource a user is a member of" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        val user = Generator.genWorkbenchUserGoogle.sample.get

        val subGroup = BasicWorkbenchGroup(WorkbenchGroupName("subGroup"), Set(user.id), WorkbenchEmail("sub@groups.com"))
        val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parent"), Set(subGroup.id), WorkbenchEmail("parent@groups.com"))

        val includesRole = ResourceRole(ResourceRoleName("includes"), Set(writeAction), includedRoles = Set(readerRole.roleName))
        val descendsRole =
          ResourceRole(ResourceRoleName("descends"), Set(readAction, writeAction), descendantRoles = Map(resourceType.name -> Set(ownerRole.roleName)))

        val nestedResourceType = resourceType.copy(name = ResourceTypeName("nested"), roles = Set(includesRole, descendsRole, readerRole))
        val includesResource = Resource(nestedResourceType.name, ResourceId("includes"), Set.empty)
        val descendsResource = Resource(resourceType.name, ResourceId("descends"), Set.empty, parent = Option(includesResource.fullyQualifiedId))
        // backgroundPolicy makes sure there is something in the database that is excluded by the query
        val includesBackgroundPolicy = AccessPolicy(
          FullyQualifiedPolicyId(includesResource.fullyQualifiedId, AccessPolicyName("background")),
          Set.empty,
          WorkbenchEmail("includesBackground@policy.com"),
          Set(includesRole.roleName),
          Set.empty,
          Set.empty,
          false
        )
        val descendsBackgroundPolicy = AccessPolicy(
          FullyQualifiedPolicyId(descendsResource.fullyQualifiedId, AccessPolicyName("background")),
          Set.empty,
          WorkbenchEmail("descendsBackground@policy.com"),
          Set(ownerRole.roleName),
          Set.empty,
          Set.empty,
          false
        )

        dirDao.createUser(user, samRequestContext).unsafeRunSync()
        dirDao.createGroup(subGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dirDao.createGroup(parentGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.upsertResourceTypes(Set(resourceType, nestedResourceType), samRequestContext).unsafeRunSync()
        dao.createResource(includesResource, samRequestContext).unsafeRunSync()
        dao.createResource(descendsResource, samRequestContext).unsafeRunSync()
        dao.createPolicy(includesBackgroundPolicy, samRequestContext).unsafeRunSync()
        dao.createPolicy(descendsBackgroundPolicy, samRequestContext).unsafeRunSync()

        val probePolicies = List(
          // user with role
          AccessPolicy(
            FullyQualifiedPolicyId(includesResource.fullyQualifiedId, AccessPolicyName("probe")),
            Set(user.id),
            WorkbenchEmail("probe@policy.com"),
            Set(includesRole.roleName, descendsRole.roleName),
            Set.empty,
            Set.empty,
            false
          ),
          // public with role
          AccessPolicy(
            FullyQualifiedPolicyId(includesResource.fullyQualifiedId, AccessPolicyName("probe")),
            Set.empty,
            WorkbenchEmail("probe@policy.com"),
            Set(includesRole.roleName, descendsRole.roleName),
            Set.empty,
            Set.empty,
            true
          ),
          // group with role
          AccessPolicy(
            FullyQualifiedPolicyId(includesResource.fullyQualifiedId, AccessPolicyName("probe")),
            Set(parentGroup.id),
            WorkbenchEmail("probe@policy.com"),
            Set(includesRole.roleName, descendsRole.roleName),
            Set.empty,
            Set.empty,
            false
          )
        )

        probePolicies.foreach { probePolicy =>
          (for {
            _ <- dao.deletePolicy(probePolicy.id, samRequestContext)
            _ <- dao.createPolicy(probePolicy, samRequestContext)
            includesResult <- dao.listUserResourceRoles(includesResource.fullyQualifiedId, user.id, samRequestContext)
            descendsResult <- dao.listUserResourceRoles(descendsResource.fullyQualifiedId, user.id, samRequestContext)
          } yield withClue(probePolicy) {
            includesResult should contain theSameElementsAs Set(includesRole.roleName, descendsRole.roleName, readerRole.roleName)
            descendsResult should contain theSameElementsAs Set(ownerRole.roleName)
          }).unsafeRunSync()
        }
      }

      "lists included and descendant roles on a resource a user is a member of via ancestor" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        val user = Generator.genWorkbenchUserGoogle.sample.get

        val subGroup = BasicWorkbenchGroup(WorkbenchGroupName("subGroup"), Set(user.id), WorkbenchEmail("sub@groups.com"))
        val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parent"), Set(subGroup.id), WorkbenchEmail("parent@groups.com"))

        val includesRole = ResourceRole(ResourceRoleName("includes"), Set(writeAction), includedRoles = Set(readerRole.roleName))
        val descendsRole =
          ResourceRole(ResourceRoleName("descends"), Set(readAction, writeAction), descendantRoles = Map(resourceType.name -> Set(ownerRole.roleName)))

        val nestedResourceType = resourceType.copy(name = ResourceTypeName("nested"), roles = Set(includesRole, descendsRole, readerRole))
        val parentResource = Resource(nestedResourceType.name, ResourceId("parent"), Set.empty)
        val includesResource = Resource(nestedResourceType.name, ResourceId("includes"), Set.empty, parent = Option(parentResource.fullyQualifiedId))
        val descendsResource = Resource(resourceType.name, ResourceId("descends"), Set.empty, parent = Option(includesResource.fullyQualifiedId))
        // backgroundPolicy makes sure there is something in the database that is excluded by the query
        val includesBackgroundPolicy = AccessPolicy(
          FullyQualifiedPolicyId(includesResource.fullyQualifiedId, AccessPolicyName("background")),
          Set.empty,
          WorkbenchEmail("includesBackground@policy.com"),
          Set(includesRole.roleName),
          Set.empty,
          Set.empty,
          false
        )
        val descendsBackgroundPolicy = AccessPolicy(
          FullyQualifiedPolicyId(descendsResource.fullyQualifiedId, AccessPolicyName("background")),
          Set.empty,
          WorkbenchEmail("descendsBackground@policy.com"),
          Set(ownerRole.roleName),
          Set.empty,
          Set.empty,
          false
        )

        dirDao.createUser(user, samRequestContext).unsafeRunSync()
        dirDao.createGroup(subGroup, samRequestContext = samRequestContext).unsafeRunSync()
        dirDao.createGroup(parentGroup, samRequestContext = samRequestContext).unsafeRunSync()

        dao.upsertResourceTypes(Set(resourceType, nestedResourceType), samRequestContext).unsafeRunSync()
        dao.createResource(parentResource, samRequestContext).unsafeRunSync()
        dao.createResource(includesResource, samRequestContext).unsafeRunSync()
        dao.createResource(descendsResource, samRequestContext).unsafeRunSync()
        dao.createPolicy(includesBackgroundPolicy, samRequestContext).unsafeRunSync()
        dao.createPolicy(descendsBackgroundPolicy, samRequestContext).unsafeRunSync()

        val probePolicies = List(
          // user with role
          AccessPolicy(
            FullyQualifiedPolicyId(parentResource.fullyQualifiedId, AccessPolicyName("probe")),
            Set(user.id),
            WorkbenchEmail("probe@policy.com"),
            Set.empty,
            Set.empty,
            Set(AccessPolicyDescendantPermissions(nestedResourceType.name, Set.empty, Set(includesRole.roleName, descendsRole.roleName))),
            false
          ),
          // public with role
          AccessPolicy(
            FullyQualifiedPolicyId(parentResource.fullyQualifiedId, AccessPolicyName("probe")),
            Set.empty,
            WorkbenchEmail("probe@policy.com"),
            Set.empty,
            Set.empty,
            Set(AccessPolicyDescendantPermissions(nestedResourceType.name, Set.empty, Set(includesRole.roleName, descendsRole.roleName))),
            true
          ),
          // group with role
          AccessPolicy(
            FullyQualifiedPolicyId(parentResource.fullyQualifiedId, AccessPolicyName("probe")),
            Set(parentGroup.id),
            WorkbenchEmail("probe@policy.com"),
            Set.empty,
            Set.empty,
            Set(AccessPolicyDescendantPermissions(nestedResourceType.name, Set.empty, Set(includesRole.roleName, descendsRole.roleName))),
            false
          )
        )

        probePolicies.foreach { probePolicy =>
          (for {
            _ <- dao.deletePolicy(probePolicy.id, samRequestContext)
            _ <- dao.createPolicy(probePolicy, samRequestContext)
            includesResult <- dao.listUserResourceRoles(includesResource.fullyQualifiedId, user.id, samRequestContext)
            descendsResult <- dao.listUserResourceRoles(descendsResource.fullyQualifiedId, user.id, samRequestContext)
            parentResult <- dao.listUserResourceRoles(parentResource.fullyQualifiedId, user.id, samRequestContext)
          } yield withClue(probePolicy) {
            includesResult should contain theSameElementsAs Set(includesRole.roleName, descendsRole.roleName, readerRole.roleName)
            descendsResult should contain theSameElementsAs Set(ownerRole.roleName)
            parentResult shouldBe empty
          }).unsafeRunSync()
        }
      }

      "should distinguish between descendant roles and direct roles when dealing with the same resource type" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        val user = Generator.genWorkbenchUserGoogle.sample.get
        val descendantRoleResourceTypeName = ResourceTypeName("descends")

        val descendantRole = ResourceRole(ResourceRoleName("descends"), Set.empty, descendantRoles = Map(descendantRoleResourceTypeName -> Set(ownerRoleName)))
        val descendantRoleResourceType = resourceType.copy(name = descendantRoleResourceTypeName, roles = Set(ownerRole, descendantRole))
        dirDao.createUser(user, samRequestContext).unsafeRunSync()
        dao.upsertResourceTypes(Set(descendantRoleResourceType), samRequestContext).unsafeRunSync()

        val parentResource = Resource(descendantRoleResourceType.name, ResourceId(uuid), Set.empty)
        val childResource = Resource(descendantRoleResourceType.name, ResourceId(uuid), Set.empty, parent = Option(parentResource.fullyQualifiedId))
        dao.createResource(parentResource, samRequestContext).unsafeRunSync()
        dao.createResource(childResource, samRequestContext).unsafeRunSync()

        val parentPolicy = AccessPolicy(
          FullyQualifiedPolicyId(parentResource.fullyQualifiedId, AccessPolicyName("parent")),
          Set(user.id),
          WorkbenchEmail("parent@policy.com"),
          Set(descendantRole.roleName),
          Set.empty,
          Set.empty,
          false
        )
        dao.createPolicy(parentPolicy, samRequestContext).unsafeRunSync()
        val parentResult = dao.listUserResourceRoles(parentResource.fullyQualifiedId, user.id, samRequestContext).unsafeRunSync()
        val childResult = dao.listUserResourceRoles(childResource.fullyQualifiedId, user.id, samRequestContext).unsafeRunSync()

        childResult should contain theSameElementsAs Set(ownerRole.roleName)
        parentResult should contain theSameElementsAs Set(descendantRole.roleName)
      }

      "list included role when it is included in inherited role" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        // parent_role on parent has child_role on child, child_role includes child_included_role
        // verify that user has child_included_role on child when granted parent_role on parent
        val user = Generator.genWorkbenchUserGoogle.sample.get
        val noAccessUser = Generator.genWorkbenchUserGoogle.sample.get

        val childRole = ResourceRoleName("child_role")
        val childIncludedRole = ResourceRoleName("child_included_role")
        val childRT = ResourceType(
          ResourceTypeName("childRT"),
          Set.empty,
          Set(ResourceRole(childRole, Set.empty, includedRoles = Set(childIncludedRole)), ResourceRole(childIncludedRole, Set.empty)),
          childRole
        )

        val parentRole = ResourceRoleName("parent_role")
        val parentRT = ResourceType(
          ResourceTypeName("parentRT"),
          Set.empty,
          Set(ResourceRole(parentRole, Set.empty, descendantRoles = Map(childRT.name -> Set(childRole)))),
          parentRole
        )

        val parentId = FullyQualifiedResourceId(parentRT.name, ResourceId("parent"))
        val parent = Resource(
          parentId.resourceTypeName,
          parentId.resourceId,
          Set.empty,
          Set(
            AccessPolicy(
              FullyQualifiedPolicyId(parentId, AccessPolicyName("policyname")),
              Set(user.id),
              WorkbenchEmail("foo@foo.com"),
              Set(parentRole),
              Set.empty,
              Set.empty,
              false
            )
          )
        )

        val childId = FullyQualifiedResourceId(childRT.name, ResourceId("child"))
        val child = Resource(childId.resourceTypeName, childId.resourceId, Set.empty, parent = Option(parent.fullyQualifiedId))

        val test = for {
          _ <- dirDao.createUser(user, samRequestContext)
          _ <- dirDao.createUser(noAccessUser, samRequestContext)
          _ <- dao.upsertResourceTypes(Set(parentRT, childRT), samRequestContext)
          _ <- dao.createResource(parent, samRequestContext)
          _ <- dao.createResource(child, samRequestContext)

          parentRoles <- dao.listUserResourceRoles(parentId, user.id, samRequestContext)
          childRoles <- dao.listUserResourceRoles(childId, user.id, samRequestContext)

          noAccessParentRoles <- dao.listUserResourceRoles(parentId, noAccessUser.id, samRequestContext)
          noAccessChildRoles <- dao.listUserResourceRoles(childId, noAccessUser.id, samRequestContext)
        } yield {
          parentRoles should contain theSameElementsAs Set(parentRole)
          childRoles should contain theSameElementsAs Set(childRole, childIncludedRole)

          noAccessParentRoles shouldBe empty
          noAccessChildRoles shouldBe empty
        }

        test.unsafeRunSync()
      }

      "list correct roles in grandparent, parent, child relationship" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        // create grand parent, parent, child structure where grand parent role includes
        // a parent role and parent role include a child role then assign user grandparent role
        // and verify user has correct role at each level
        val user = Generator.genWorkbenchUserGoogle.sample.get
        val noAccessUser = Generator.genWorkbenchUserGoogle.sample.get

        val childRole = ResourceRoleName("child_role")
        val childRT = ResourceType(ResourceTypeName("childRT"), Set.empty, Set(ResourceRole(childRole, Set.empty)), childRole)

        val parentRole = ResourceRoleName("parent_role")
        val parentRT = ResourceType(
          ResourceTypeName("parentRT"),
          Set.empty,
          Set(ResourceRole(parentRole, Set.empty, descendantRoles = Map(childRT.name -> Set(childRole)))),
          parentRole
        )

        val grantParentRole = ResourceRoleName("grand_parent_role")
        val grandParentRT = ResourceType(
          ResourceTypeName("grandparentRT"),
          Set.empty,
          Set(ResourceRole(grantParentRole, Set.empty, descendantRoles = Map(parentRT.name -> Set(parentRole)))),
          grantParentRole
        )

        val grandParentId = FullyQualifiedResourceId(grandParentRT.name, ResourceId("grandparent"))
        val grandParent = Resource(
          grandParentId.resourceTypeName,
          grandParentId.resourceId,
          Set.empty,
          Set(
            AccessPolicy(
              FullyQualifiedPolicyId(grandParentId, AccessPolicyName("policyname")),
              Set(user.id),
              WorkbenchEmail("foo@foo.com"),
              Set(grantParentRole),
              Set.empty,
              Set.empty,
              false
            )
          )
        )

        val parentId = FullyQualifiedResourceId(parentRT.name, ResourceId("parent"))
        val parent = Resource(parentId.resourceTypeName, parentId.resourceId, Set.empty, parent = Option(grandParent.fullyQualifiedId))

        val childId = FullyQualifiedResourceId(childRT.name, ResourceId("child"))
        val child = Resource(childId.resourceTypeName, childId.resourceId, Set.empty, parent = Option(parent.fullyQualifiedId))

        val test = for {
          _ <- dirDao.createUser(user, samRequestContext)
          _ <- dirDao.createUser(noAccessUser, samRequestContext)
          _ <- dao.upsertResourceTypes(Set(grandParentRT, parentRT, childRT), samRequestContext)
          _ <- dao.createResource(grandParent, samRequestContext)
          _ <- dao.createResource(parent, samRequestContext)
          _ <- dao.createResource(child, samRequestContext)

          grandParentRoles <- dao.listUserResourceRoles(grandParentId, user.id, samRequestContext)
          parentRoles <- dao.listUserResourceRoles(parentId, user.id, samRequestContext)
          childRoles <- dao.listUserResourceRoles(childId, user.id, samRequestContext)

          noAccessGrandParentRoles <- dao.listUserResourceRoles(grandParentId, noAccessUser.id, samRequestContext)
          noAccessParentRoles <- dao.listUserResourceRoles(parentId, noAccessUser.id, samRequestContext)
          noAccessChildRoles <- dao.listUserResourceRoles(childId, noAccessUser.id, samRequestContext)
        } yield {
          grandParentRoles should contain theSameElementsAs Set(grantParentRole)
          parentRoles should contain theSameElementsAs Set(parentRole)
          childRoles should contain theSameElementsAs Set(childRole)

          noAccessGrandParentRoles shouldBe empty
          noAccessParentRoles shouldBe empty
          noAccessChildRoles shouldBe empty
        }

        test.unsafeRunSync()
      }
    }

    "setPolicyIsPublic" - {
      "can change whether a policy is public or private" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        val policy = AccessPolicy(
          FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("private")),
          Set.empty,
          WorkbenchEmail("private@policy.com"),
          resourceType.roles.map(_.roleName),
          Set(readAction, writeAction),
          Set.empty,
          false
        )

        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        dao.createResource(resource, samRequestContext).unsafeRunSync()
        dao.createPolicy(policy, samRequestContext).unsafeRunSync()
        dao.loadPolicy(policy.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"failed to load policy ${policy.id}")).public shouldBe false

        dao.setPolicyIsPublic(policy.id, true, samRequestContext).unsafeRunSync()
        dao.loadPolicy(policy.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"failed to load policy ${policy.id}")).public shouldBe true

        dao.setPolicyIsPublic(policy.id, false, samRequestContext).unsafeRunSync()
        dao.loadPolicy(policy.id, samRequestContext).unsafeRunSync().getOrElse(fail(s"failed to load policy ${policy.id}")).public shouldBe false
      }
    }

    "listAccessPolicies" - {
      "lists all the access policy names with their resource names that a user is in for a given resource type" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        val resource1 = Resource(resourceType.name, ResourceId("resource1"), Set.empty)
        val policy1 = AccessPolicy(
          FullyQualifiedPolicyId(resource1.fullyQualifiedId, AccessPolicyName("one")),
          Set(defaultUser.id),
          WorkbenchEmail("one@policy.com"),
          resourceType.roles.map(_.roleName),
          Set(readAction, writeAction),
          Set.empty,
          false
        )
        val resource2 = Resource(resourceType.name, ResourceId("resource2"), Set.empty)
        val policy2 = AccessPolicy(
          FullyQualifiedPolicyId(resource2.fullyQualifiedId, AccessPolicyName("two")),
          Set(defaultUser.id),
          WorkbenchEmail("two@policy.com"),
          resourceType.roles.map(_.roleName),
          Set(readAction, writeAction),
          Set.empty,
          false
        )

        dirDao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        dao.createResource(resource1, samRequestContext).unsafeRunSync()
        dao.createResource(resource2, samRequestContext).unsafeRunSync()
        dao.createPolicy(policy1, samRequestContext).unsafeRunSync()
        dao.createPolicy(policy2, samRequestContext).unsafeRunSync()

        dao.listAccessPolicies(resourceType.name, defaultUser.id, samRequestContext).unsafeRunSync() should contain theSameElementsAs Set(
          ResourceIdAndPolicyName(resource1.resourceId, policy1.id.accessPolicyName),
          ResourceIdAndPolicyName(resource2.resourceId, policy2.id.accessPolicyName)
        )
      }

      "lists the access policies for a resource" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        dirDao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        dirDao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()

        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        val owner = AccessPolicy(
          FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("owner")),
          Set(defaultUser.id, defaultGroup.id),
          WorkbenchEmail("owner@policy.com"),
          resourceType.roles.map(_.roleName),
          Set(readAction, writeAction),
          Set.empty,
          false
        )
        val reader = AccessPolicy(
          FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("reader")),
          Set(owner.id, defaultUser.id),
          WorkbenchEmail("reader@policy.com"),
          resourceType.roles.map(_.roleName),
          Set(readAction, writeAction),
          Set.empty,
          false
        )

        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        dao.createResource(resource, samRequestContext).unsafeRunSync()
        dao.createPolicy(owner, samRequestContext).unsafeRunSync()
        dao.createPolicy(reader, samRequestContext).unsafeRunSync()

        dao.listAccessPolicies(resource.fullyQualifiedId, samRequestContext).unsafeRunSync() should contain theSameElementsAs Set(owner, reader)
      }
    }

    "listAccessPolicyMemberships" - {
      "lists the access policy memberships for a resource" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        dirDao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        dirDao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()

        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        val owner = AccessPolicy(
          FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("owner")),
          Set(defaultUser.id, defaultGroup.id),
          WorkbenchEmail("owner@policy.com"),
          resourceType.roles.map(_.roleName),
          Set(readAction, writeAction),
          Set.empty,
          false
        )
        val reader = AccessPolicy(
          FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("reader")),
          Set(owner.id, defaultUser.id),
          WorkbenchEmail("reader@policy.com"),
          resourceType.roles.map(_.roleName),
          Set(readAction, writeAction),
          Set.empty,
          false
        )

        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        dao.createResource(resource, samRequestContext).unsafeRunSync()
        dao.createPolicy(owner, samRequestContext).unsafeRunSync()
        dao.createPolicy(reader, samRequestContext).unsafeRunSync()

        val ownerPolicyWithMembership = AccessPolicyWithMembership(
          owner.id.accessPolicyName,
          AccessPolicyMembership(
            Set(defaultUser.email, defaultGroup.email),
            owner.actions,
            owner.roles,
            Option(owner.descendantPermissions),
            Option(Set.empty)
          ),
          owner.email
        )
        val readerPolicyWithMembership = AccessPolicyWithMembership(
          reader.id.accessPolicyName,
          AccessPolicyMembership(
            Set(owner.email, defaultUser.email),
            reader.actions,
            reader.roles,
            Option(reader.descendantPermissions),
            Option(Set(PolicyIdentifiers(owner.id.accessPolicyName, owner.email, owner.id.resource.resourceTypeName, owner.id.resource.resourceId)))
          ),
          reader.email
        )

        dao.listAccessPolicyMemberships(resource.fullyQualifiedId, samRequestContext).unsafeRunSync() should contain theSameElementsAs LazyList(
          ownerPolicyWithMembership,
          readerPolicyWithMembership
        )
      }
    }

    "loadPolicyMembership" - {
      "loads an access policy's membership" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        dirDao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        dirDao.createGroup(defaultGroup, samRequestContext = samRequestContext).unsafeRunSync()

        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        val owner = AccessPolicy(
          FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("owner")),
          Set(defaultUser.id, defaultGroup.id),
          WorkbenchEmail("owner@policy.com"),
          resourceType.roles.map(_.roleName),
          Set(readAction, writeAction),
          Set.empty,
          false
        )
        val reader = AccessPolicy(
          FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("reader")),
          Set(owner.id, defaultUser.id),
          WorkbenchEmail("reader@policy.com"),
          resourceType.roles.map(_.roleName),
          Set(readAction, writeAction),
          Set.empty,
          false
        )

        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        dao.createResource(resource, samRequestContext).unsafeRunSync()
        dao.createPolicy(owner, samRequestContext).unsafeRunSync()
        dao.createPolicy(reader, samRequestContext).unsafeRunSync()

        val ownerPolicyMembership =
          AccessPolicyMembership(Set(defaultUser.email, defaultGroup.email), owner.actions, owner.roles, Option(owner.descendantPermissions), Option(Set.empty))
        val readerPolicyMembership = AccessPolicyMembership(
          Set(owner.email, defaultUser.email),
          reader.actions,
          reader.roles,
          Option(reader.descendantPermissions),
          Option(Set(PolicyIdentifiers(owner.id.accessPolicyName, owner.email, owner.id.resource.resourceTypeName, owner.id.resource.resourceId)))
        )

        dao.loadPolicyMembership(reader.id, samRequestContext).unsafeRunSync() shouldBe Option(readerPolicyMembership)
        dao.loadPolicyMembership(owner.id, samRequestContext).unsafeRunSync() shouldBe Option(ownerPolicyMembership)
        dao.loadPolicyMembership(reader.id.copy(accessPolicyName = AccessPolicyName("does not exist")), samRequestContext).unsafeRunSync() shouldBe None
      }
    }

    "overwritePolicyMembers" - {
      "overwrites a policy's members" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        dao.createResource(resource, samRequestContext).unsafeRunSync()

        val secondUser = defaultUser.copy(
          id = WorkbenchUserId("foo"),
          googleSubjectId = Some(GoogleSubjectId("blablabla")),
          email = WorkbenchEmail("bar@baz.com"),
          azureB2CId = Some(AzureB2CId("ooo"))
        )
        dirDao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        dirDao.createUser(secondUser, samRequestContext).unsafeRunSync()

        val policy = AccessPolicy(
          FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("policyName")),
          Set(defaultUser.id),
          WorkbenchEmail("policy@email.com"),
          resourceType.roles.map(_.roleName),
          Set(readAction, writeAction),
          Set.empty,
          false
        )
        dao.createPolicy(policy, samRequestContext).unsafeRunSync()
        dao.loadPolicy(policy.id, samRequestContext).unsafeRunSync() shouldEqual Option(policy)

        dao.listFlattenedPolicyMembers(policy.id, samRequestContext).unsafeRunSync() shouldBe Set(defaultUser)

        dao.overwritePolicyMembers(policy.id, Set(secondUser.id), samRequestContext).unsafeRunSync()

        dao.listFlattenedPolicyMembers(policy.id, samRequestContext).unsafeRunSync() shouldBe Set(secondUser)
      }

      "overwrites a policy's members when starting membership is empty" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        dao.createResource(resource, samRequestContext).unsafeRunSync()

        val secondUser = defaultUser.copy(
          id = WorkbenchUserId("foo"),
          googleSubjectId = Some(GoogleSubjectId("blablabla")),
          email = WorkbenchEmail("bar@baz.com"),
          azureB2CId = Some(AzureB2CId("ooo"))
        )

        dirDao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        dirDao.createUser(secondUser, samRequestContext).unsafeRunSync()

        val policy = AccessPolicy(
          FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("policyName")),
          Set.empty,
          WorkbenchEmail("policy@email.com"),
          resourceType.roles.map(_.roleName),
          Set(readAction, writeAction),
          Set.empty,
          false
        )
        dao.createPolicy(policy, samRequestContext).unsafeRunSync()
        dao.loadPolicy(policy.id, samRequestContext).unsafeRunSync() shouldEqual Option(policy)

        dao.listFlattenedPolicyMembers(policy.id, samRequestContext).unsafeRunSync() shouldBe Set.empty

        dao.overwritePolicyMembers(policy.id, Set(secondUser.id), samRequestContext).unsafeRunSync()

        dao.listFlattenedPolicyMembers(policy.id, samRequestContext).unsafeRunSync() shouldBe Set(secondUser)
      }
    }

    "overwritePolicy" - {
      "overwrites a policy" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        dao.createResource(resource, samRequestContext).unsafeRunSync()

        val secondUser = defaultUser.copy(
          id = WorkbenchUserId("foo"),
          googleSubjectId = Some(GoogleSubjectId("blablabla")),
          email = WorkbenchEmail("bar@baz.com"),
          azureB2CId = Some(AzureB2CId("ooo"))
        )

        dirDao.createUser(defaultUser, samRequestContext).unsafeRunSync()
        dirDao.createUser(secondUser, samRequestContext).unsafeRunSync()

        val policy = AccessPolicy(
          FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("policyName")),
          Set(defaultUser.id),
          WorkbenchEmail("policy@email.com"),
          resourceType.roles.map(_.roleName),
          Set(readAction, writeAction),
          Set.empty,
          false
        )
        dao.createPolicy(policy, samRequestContext).unsafeRunSync()
        dao.loadPolicy(policy.id, samRequestContext).unsafeRunSync() shouldEqual Option(policy)

        val newPolicy = policy.copy(members = Set(defaultUser.id, secondUser.id), actions = Set(readAction), roles = Set.empty, public = true)
        dao.overwritePolicy(newPolicy, samRequestContext).unsafeRunSync()

        dao.loadPolicy(policy.id, samRequestContext).unsafeRunSync() shouldEqual Option(newPolicy)
      }

      "will not overwrite a policy if any of the new members don't exist" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        dao.createResourceType(resourceType, samRequestContext).unsafeRunSync()
        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        dao.createResource(resource, samRequestContext).unsafeRunSync()

        val secondUser = defaultUser.copy(
          id = WorkbenchUserId("foo"),
          googleSubjectId = Some(GoogleSubjectId("blablabla")),
          email = WorkbenchEmail("bar@baz.com"),
          azureB2CId = Some(AzureB2CId("ooo"))
        )

        dirDao.createUser(defaultUser, samRequestContext).unsafeRunSync()

        val policy = AccessPolicy(
          FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("policyName")),
          Set(defaultUser.id),
          WorkbenchEmail("policy@email.com"),
          resourceType.roles.map(_.roleName),
          Set(readAction, writeAction),
          Set.empty,
          false
        )
        dao.createPolicy(policy, samRequestContext).unsafeRunSync()
        dao.loadPolicy(policy.id, samRequestContext).unsafeRunSync() shouldEqual Option(policy)

        val newPolicy = policy.copy(members = Set(defaultUser.id, secondUser.id), actions = Set(ResourceAction("fakeAction")), roles = Set.empty, public = true)

        assertThrows[PSQLException] {
          dao.overwritePolicy(newPolicy, samRequestContext).unsafeRunSync()
        }
        dao.loadPolicy(policy.id, samRequestContext).unsafeRunSync() shouldEqual Option(policy)
      }

      "overwrites descendant actions and roles" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        val otherResourceType = resourceType.copy(name = ResourceTypeName("otherResourceType"))
        val secondUser = defaultUser.copy(
          id = WorkbenchUserId("foo"),
          googleSubjectId = Some(GoogleSubjectId("blablabla")),
          email = WorkbenchEmail("bar@baz.com"),
          azureB2CId = Some(AzureB2CId("ooo"))
        )

        val sameResourceTypeDescendant = AccessPolicyDescendantPermissions(resource.resourceTypeName, Set(writeAction), Set(ownerRoleName))
        val otherResourceTypeDescendant = AccessPolicyDescendantPermissions(otherResourceType.name, Set(readAction), Set(actionlessRole.roleName))
        val initialDescendantPermissions = Set(sameResourceTypeDescendant, otherResourceTypeDescendant)
        val updatedDescendantPermissions =
          Set(sameResourceTypeDescendant.copy(actions = Set(readAction)), otherResourceTypeDescendant.copy(roles = Set(readerRole.roleName)))
        val policy = AccessPolicy(
          FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("policyName")),
          Set(defaultUser.id),
          WorkbenchEmail("policy@email.com"),
          resourceType.roles.map(_.roleName),
          Set(readAction, writeAction),
          initialDescendantPermissions,
          false
        )
        val newPolicy = policy.copy(
          members = Set(defaultUser.id, secondUser.id),
          actions = Set(readAction),
          roles = Set.empty,
          descendantPermissions = updatedDescendantPermissions,
          public = true
        )

        val testResult = for {
          _ <- dao.createResourceType(resourceType, samRequestContext)
          _ <- dao.createResourceType(otherResourceType, samRequestContext)
          _ <- dao.createResource(resource, samRequestContext)
          _ <- dirDao.createUser(defaultUser, samRequestContext)
          _ <- dirDao.createUser(secondUser, samRequestContext)
          _ <- dao.createPolicy(policy, samRequestContext)
          loadedPolicy <- dao.loadPolicy(policy.id, samRequestContext)
          _ <- dao.overwritePolicy(newPolicy, samRequestContext)
          newLoadedPolicy <- dao.loadPolicy(policy.id, samRequestContext)
        } yield {
          loadedPolicy shouldEqual Option(policy)
          newLoadedPolicy shouldEqual Option(newPolicy)
        }

        testResult.unsafeRunSync()
      }
    }

    "getResourceParent" - {
      "gets the FullyQualifiedResourceId of the parent resource if it has been set" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        val testResult = for {
          _ <- dao.createResourceType(resourceType, samRequestContext)
          childResource = Resource(resourceType.name, ResourceId("child"), Set.empty)
          parentResource = Resource(resourceType.name, ResourceId("parent"), Set.empty)
          _ <- dao.createResource(childResource, samRequestContext)
          _ <- dao.createResource(parentResource, samRequestContext)

          _ <- dao.setResourceParent(childResource.fullyQualifiedId, parentResource.fullyQualifiedId, samRequestContext)
          resourceParentResult <- dao.getResourceParent(childResource.fullyQualifiedId, samRequestContext)
        } yield resourceParentResult shouldBe Option(parentResource.fullyQualifiedId)

        testResult.unsafeRunSync()
      }

      "returns None if no parent is set" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        val testResult = for {
          _ <- dao.createResourceType(resourceType, samRequestContext)
          childResource = Resource(resourceType.name, ResourceId("child"), Set.empty)
          _ <- dao.createResource(childResource, samRequestContext)

          resourceParentResult <- dao.getResourceParent(childResource.fullyQualifiedId, samRequestContext)
        } yield resourceParentResult shouldBe None

        testResult.unsafeRunSync()
      }
    }

    "setResourceParent" - {
      "will not create a cyclical resource hierarchy" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        val childResource = Resource(resourceType.name, ResourceId("child"), Set.empty)
        val parentResource = Resource(resourceType.name, ResourceId("parent"), Set.empty)
        val grandparentResource = Resource(resourceType.name, ResourceId("gramgram"), Set.empty)

        val testSetup = for {
          _ <- dao.createResourceType(resourceType, samRequestContext)
          _ <- dao.createResource(childResource, samRequestContext)
          _ <- dao.createResource(parentResource, samRequestContext)
          _ <- dao.createResource(grandparentResource, samRequestContext)

          _ <- dao.setResourceParent(childResource.fullyQualifiedId, parentResource.fullyQualifiedId, samRequestContext)
          _ <- dao.setResourceParent(parentResource.fullyQualifiedId, grandparentResource.fullyQualifiedId, samRequestContext)
          parentResourceResult <- dao.getResourceParent(childResource.fullyQualifiedId, samRequestContext)
          grandparentResourceResult <- dao.getResourceParent(parentResource.fullyQualifiedId, samRequestContext)
        } yield {
          parentResourceResult shouldBe Option(parentResource.fullyQualifiedId)
          grandparentResourceResult shouldBe Option(grandparentResource.fullyQualifiedId)
        }

        testSetup.unsafeRunSync()

        // try to introduce a simple cycle
        val simpleCycle = intercept[WorkbenchExceptionWithErrorReport] {
          dao.setResourceParent(parentResource.fullyQualifiedId, childResource.fullyQualifiedId, samRequestContext).unsafeRunSync()
        }
        simpleCycle.errorReport.statusCode shouldBe Option(StatusCodes.BadRequest)

        // try to introduce a cycle by setting the parent for a more distant ancestor
        val longerCycle = intercept[WorkbenchExceptionWithErrorReport] {
          dao.setResourceParent(grandparentResource.fullyQualifiedId, childResource.fullyQualifiedId, samRequestContext).unsafeRunSync()
        }
        longerCycle.errorReport.statusCode shouldBe Option(StatusCodes.BadRequest)
      }

      "cannot set a resource as its own parent" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        val testResult = for {
          _ <- dao.createResourceType(resourceType, samRequestContext)
          _ <- dao.createResource(resource, samRequestContext)
          _ <- dao.setResourceParent(resource.fullyQualifiedId, resource.fullyQualifiedId, samRequestContext)
        } yield ()

        val exception = intercept[WorkbenchExceptionWithErrorReport] {
          testResult.unsafeRunSync()
        }

        exception.errorReport.statusCode shouldBe Option(StatusCodes.BadRequest)
      }

      "errors if parent does not exist" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)
        val doesNotExist = Resource(resourceType.name, ResourceId("doesNotExist"), Set.empty)
        val testResult = for {
          _ <- dao.createResourceType(resourceType, samRequestContext)
          _ <- dao.createResource(resource, samRequestContext)
          _ <- dao.setResourceParent(resource.fullyQualifiedId, doesNotExist.fullyQualifiedId, samRequestContext)
        } yield ()

        val exception = intercept[WorkbenchException] {
          testResult.unsafeRunSync()
        }
      }
    }

    "deleteResourceParent" - {
      "can unset the parent of a resource" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        val childResource = Resource(resourceType.name, ResourceId("child"), Set.empty)
        val parentResource = Resource(resourceType.name, ResourceId("parent"), Set.empty)

        val testSetup = for {
          _ <- dao.createResourceType(resourceType, samRequestContext)
          _ <- dao.createResource(childResource, samRequestContext)
          _ <- dao.createResource(parentResource, samRequestContext)
          _ <- dao.setResourceParent(childResource.fullyQualifiedId, parentResource.fullyQualifiedId, samRequestContext)
          parentResourceResult <- dao.getResourceParent(childResource.fullyQualifiedId, samRequestContext)
        } yield parentResourceResult shouldBe Option(parentResource.fullyQualifiedId)

        testSetup.unsafeRunSync()

        dao.deleteResourceParent(childResource.fullyQualifiedId, samRequestContext).unsafeRunSync()
        dao.getResourceParent(childResource.fullyQualifiedId, samRequestContext).unsafeRunSync() shouldBe None
      }

      // this shouldn't actually happen but good to be tolerant
      "tolerates trying to delete a parent on a resource without a parent" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        val resource = Resource(resourceType.name, ResourceId("resource"), Set.empty)

        val testResult = for {
          _ <- dao.createResourceType(resourceType, samRequestContext)
          _ <- dao.createResource(resource, samRequestContext)
          _ <- dao.deleteResourceParent(resource.fullyQualifiedId, samRequestContext)
        } yield ()

        testResult.unsafeRunSync()
      }
    }

    "listResourceChildren" - {
      "can list direct children of a resource" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        val oldestChild = Resource(resourceType.name, ResourceId("old"), Set.empty)
        val middleChild = Resource(resourceType.name, ResourceId("middle"), Set.empty)
        val youngestChild = Resource(resourceType.name, ResourceId("young"), Set.empty)
        val parentResource = Resource(resourceType.name, ResourceId("parent"), Set.empty)

        val testSetup = for {
          _ <- dao.createResourceType(resourceType, samRequestContext)
          _ <- dao.createResource(parentResource, samRequestContext)
          _ <- dao.createResource(oldestChild, samRequestContext)
          _ <- dao.createResource(middleChild, samRequestContext)
          _ <- dao.createResource(youngestChild, samRequestContext)

          _ <- dao.setResourceParent(oldestChild.fullyQualifiedId, parentResource.fullyQualifiedId, samRequestContext)
          _ <- dao.setResourceParent(middleChild.fullyQualifiedId, parentResource.fullyQualifiedId, samRequestContext)
          _ <- dao.setResourceParent(youngestChild.fullyQualifiedId, parentResource.fullyQualifiedId, samRequestContext)
        } yield ()

        testSetup.unsafeRunSync()

        val allChildrenIds = Set(oldestChild.fullyQualifiedId, middleChild.fullyQualifiedId, youngestChild.fullyQualifiedId)
        dao.listResourceChildren(parentResource.fullyQualifiedId, samRequestContext).unsafeRunSync() shouldBe allChildrenIds
      }

      "does not list indirect children of a resource" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        val childResource = Resource(resourceType.name, ResourceId("child"), Set.empty)
        val grandchildResource = Resource(resourceType.name, ResourceId("grandchild"), Set.empty)
        val greatGrandchildResource = Resource(resourceType.name, ResourceId("great-grandchild"), Set.empty)
        val parentResource = Resource(resourceType.name, ResourceId("parent"), Set.empty)

        val testSetup = for {
          _ <- dao.createResourceType(resourceType, samRequestContext)
          _ <- dao.createResource(parentResource, samRequestContext)
          _ <- dao.createResource(childResource, samRequestContext)
          _ <- dao.createResource(grandchildResource, samRequestContext)
          _ <- dao.createResource(greatGrandchildResource, samRequestContext)

          _ <- dao.setResourceParent(childResource.fullyQualifiedId, parentResource.fullyQualifiedId, samRequestContext)
          _ <- dao.setResourceParent(grandchildResource.fullyQualifiedId, childResource.fullyQualifiedId, samRequestContext)
          _ <- dao.setResourceParent(greatGrandchildResource.fullyQualifiedId, grandchildResource.fullyQualifiedId, samRequestContext)
        } yield ()

        testSetup.unsafeRunSync()

        val directChildrenIds = Set(childResource.fullyQualifiedId)
        dao.listResourceChildren(parentResource.fullyQualifiedId, samRequestContext).unsafeRunSync() shouldBe directChildrenIds
      }

      "can list children of different resource types" in {
        assume(databaseEnabled, "-- skipping tests that talk to a real database")

        val parentResourceType = resourceType.copy(name = ResourceTypeName("parentRT"))
        val childResourceType1 = resourceType.copy(name = ResourceTypeName("childRT1"))
        val childResourceType2 = resourceType.copy(name = ResourceTypeName("childRT2"))

        val parentResource = Resource(parentResourceType.name, ResourceId("parent"), Set.empty)
        val childResource1 = Resource(childResourceType1.name, ResourceId("child1"), Set.empty)
        val childResource2 = Resource(childResourceType2.name, ResourceId("child2"), Set.empty)
        val sameRTAsParent = Resource(parentResourceType.name, ResourceId("still_a_child"), Set.empty)
        val allChildrenIds = Set(childResource1.fullyQualifiedId, childResource2.fullyQualifiedId, sameRTAsParent.fullyQualifiedId)

        val testSetup = for {
          _ <- dao.createResourceType(parentResourceType, samRequestContext)
          _ <- dao.createResourceType(childResourceType1, samRequestContext)
          _ <- dao.createResourceType(childResourceType2, samRequestContext)

          _ <- dao.createResource(parentResource, samRequestContext)
          _ <- dao.createResource(childResource1, samRequestContext)
          _ <- dao.createResource(childResource2, samRequestContext)
          _ <- dao.createResource(sameRTAsParent, samRequestContext)

          _ <- dao.setResourceParent(childResource1.fullyQualifiedId, parentResource.fullyQualifiedId, samRequestContext)
          _ <- dao.setResourceParent(childResource2.fullyQualifiedId, parentResource.fullyQualifiedId, samRequestContext)
          _ <- dao.setResourceParent(sameRTAsParent.fullyQualifiedId, parentResource.fullyQualifiedId, samRequestContext)
        } yield ()

        testSetup.unsafeRunSync()

        dao.listResourceChildren(parentResource.fullyQualifiedId, samRequestContext).unsafeRunSync() shouldBe allChildrenIds
      }
    }
  }

  private def uuid: String =
    UUID.randomUUID().toString
}
