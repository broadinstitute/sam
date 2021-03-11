package org.broadinstitute.dsde.workbench.sam.microsoft

import akka.http.scaladsl.model.StatusCodes
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchException, WorkbenchExceptionWithErrorReport}
import org.broadinstitute.dsde.workbench.sam.config.AzureConfig
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class AzureActiveDirectoryDAOSpec extends AnyFreeSpec with Matchers {
  val azureActiveDirectoryDAO = new AzureActiveDirectoryDAO(AzureConfig(
    tenant = "fad90753-2022-4456-9b0a-c7e5b934e408",
    clientId = "3814c76d-e2a2-41ab-87dc-bc4c70c3c67c",
    clientSecret = null))

  val existsTwiceEmail = WorkbenchEmail("foo@bar.com")
  val existingGroupEmail = WorkbenchEmail("testgroup")

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
