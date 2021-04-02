package org.broadinstitute.dsde.workbench.sam.microsoft

import akka.http.scaladsl.model.StatusCodes
import com.azure.core.management.AzureEnvironment
import com.azure.core.management.profile.AzureProfile
import com.azure.identity.ClientSecretCredentialBuilder
import com.azure.resourcemanager.AzureResourceManager
import com.azure.resourcemanager.resources.models.DeploymentMode
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchException, WorkbenchExceptionWithErrorReport}
import org.broadinstitute.dsde.workbench.sam.config.AzureConfig
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.util.UUID
import scala.io.Source

class AzureActiveDirectoryDAOSpec extends AnyFreeSpec with Matchers {
  val azureActiveDirectoryDAO = new AzureActiveDirectoryDAO(AzureConfig(
    tenant = "fad90753-2022-4456-9b0a-c7e5b934e408",
    clientId = "3814c76d-e2a2-41ab-87dc-bc4c70c3c67c",
    clientSecret = null))

  val existsTwiceEmail = WorkbenchEmail("foo@bar.com")
  val existingGroupEmail = WorkbenchEmail("testgroup")

  "xxx" in {
    val tenantId = "42998b82-3ac1-40b6-8a71-5c4f31201c17"
    val clientId = "6aabd5dd-ca46-443e-aa17-2e383ea91af6"
    val clientSecret = ???
    val creds = new ClientSecretCredentialBuilder().clientSecret(clientSecret).clientId(clientId).tenantId(tenantId).build()

    val subscriptionID = "a2e2365d-c161-47a4-8df3-5076a409b38f"
    val rm = AzureResourceManager.authenticate(creds, new AzureProfile(AzureEnvironment.AZURE)).withSubscription(subscriptionID)

    val templateJson = Source.fromFile("/Users/dvoet/projects/az-managed-app/template.json").mkString.replaceAll("\n", "")
    val deployment = rm.deployments().define(UUID.randomUUID().toString)
      .withExistingResourceGroup("terra")
      .withTemplate(templateJson)
      .withParameters(s"""{
                        |        "storageAccountNamePrefix": {
                        |            "value": "pot"
                        |        },
                        |        "storageAccountType": {
                        |            "value": "Standard_LRS"
                        |        },
                        |        "location": {
                        |            "value": "eastus"
                        |        },
                        |        "applicationResourceName": {
                        |            "value": "dvoet20"
                        |        },
                        |        "managedResourceGroupId": {
                        |            "value": "/subscriptions/$subscriptionID/resourceGroups/dvoet20-mrg"
                        |        },
                        |        "managedIdentity": {
                        |            "value": {}
                        |        }
                        |    }""".stripMargin)
      .withMode(DeploymentMode.INCREMENTAL)
      .create()

//    val x = rm.accessManagement().roleAssignments().manager().roleServiceClient().getRoleAssignments.listForScope("/subscriptions/a2e2365d-c161-47a4-8df3-5076a409b38f", "atScope()", Context.NONE).asScala
//    x.foreach { r=>
//      println(s"${r.principalId()}, ${r.roleDefinitionId()}")
//    }
  }

  "AzureActiveDirectoryDAO" ignore {
    "createGroup and deleteGroup" - {
      "create and delete a group" in {
        val email = WorkbenchEmail("create@bar.com")
        val test = for {
          _ <- azureActiveDirectoryDAO.createGroup("displayname", email)
          groupIdAfterCreate <- azureActiveDirectoryDAO.maybeGetGroupId(email)
          _ <- azureActiveDirectoryDAO.deleteGroup(email)
          groupIdAfterDelete <- azureActiveDirectoryDAO.maybeGetGroupId(email)
        } yield {
          groupIdAfterCreate.isDefined shouldBe true
          groupIdAfterDelete.isDefined shouldBe false
        }
        test.unsafeRunSync()
      }

      "conflict creating group that exists" in {
        val e = intercept[WorkbenchExceptionWithErrorReport] {
          azureActiveDirectoryDAO.createGroup("", existingGroupEmail).unsafeRunSync()
        }
        e.errorReport.statusCode shouldBe Option(StatusCodes.Conflict)
      }

      "success deleting group that does not exist" in {
        azureActiveDirectoryDAO.deleteGroup(WorkbenchEmail("dne")).unsafeRunSync()
      }
    }

    "maybeGetGroupId" - {
      "get a group id" in {
        azureActiveDirectoryDAO.maybeGetGroupId(existingGroupEmail).unsafeRunSync() shouldBe Some(AzureGroupId("d40e45ea-e38e-45fa-bb6e-db9db4de4438"))
      }

      "return None if group does not exist" in {
        azureActiveDirectoryDAO.maybeGetGroupId(WorkbenchEmail("foo")).unsafeRunSync() shouldBe None
      }

      "raise exception if too many" in {
        intercept[WorkbenchException] {
          azureActiveDirectoryDAO.maybeGetGroupId(existsTwiceEmail).unsafeRunSync()
        }
      }
    }

    "listGroupMembers" in {
      azureActiveDirectoryDAO.listGroupMembers(WorkbenchEmail("testgroup")).unsafeRunSync().getOrElse(Seq.empty) should contain theSameElementsAs(Seq("nmalfroy@broadinstitute.org", "foo@bar.com"))
    }
  }
}
