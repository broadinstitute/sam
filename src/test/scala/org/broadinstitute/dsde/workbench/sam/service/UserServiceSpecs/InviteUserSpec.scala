package org.broadinstitute.dsde.workbench.sam.service.UserServiceSpecs

import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.sam.Generator.{genWorkbenchUserBoth, genWorkbenchUserGoogle}
import org.broadinstitute.dsde.workbench.sam.model.{BasicWorkbenchGroup, SamUser}
import org.broadinstitute.dsde.workbench.sam.service.GenEmail.genBadChar
import org.broadinstitute.dsde.workbench.sam.service.{CloudExtensions, TestUserServiceBuilder}
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentMatchersSugar.any
import org.mockito.captor.ArgCaptor
import org.scalatest.DoNotDiscover

import scala.concurrent.ExecutionContextExecutor

@DoNotDiscover
class InviteUserSpec extends UserServiceTestTraits {
  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  // Setup test vals
  val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set(), WorkbenchEmail("all_users@fake.com"))

  describe("When a new user") {
    val newUser = genWorkbenchUserGoogle.sample.get

    describe("is invited") {
      val userService = TestUserServiceBuilder().withAllUsersGroup(allUsersGroup).build

      describe("with a valid email address") {
        val invitedUserEmail = newUser.email
        val invitedUserStatus = runAndWait(userService.inviteUser(invitedUserEmail, samRequestContext))

        it("returns a status that indicates the user exists and is invited") {
          invitedUserStatus.userSubjectId.value should fullyMatch regex """\S+"""
          invitedUserStatus.userEmail shouldBe invitedUserEmail
        }

        it("creates a user in the Sam database") {
          // need to user a captor here because userService.inviteUser creates a new SamUser instance using the email
          // address that is passed to it, and that new instance is what it saves to the db
          val userCaptor = ArgCaptor[SamUser]
          userService.directoryDAO.createUser(userCaptor, any[SamRequestContext]) wasCalled once
          userCaptor.value.email shouldBe invitedUserEmail
        }

        it("creates the user in GCP") {
          // need to user a captor here because userService.inviteUser creates a new SamUser instance using the email
          // address that is passed to it, and that new instance is what is sent to Google
          val userCaptor = ArgCaptor[SamUser]
          userService.cloudExtensions.onUserCreate(userCaptor, any[SamRequestContext]) wasCalled once
          userCaptor.value.email shouldBe invitedUserEmail
        }

        // we should perform these assertions, however right now they're buried inside cloudExtensions.onUserCreate, which is dumb
        ignore("creates a proxy group for the user on GCP") {}
        ignore("adds the user to the All_Users group") {}
      }

      describe("with an invalid email address") {
        it("fails with a message indicating that the email address is invalid") {
          val invalidUserEmail = WorkbenchEmail("not an email")
          val e = intercept[Exception] {
            runAndWait(userService.inviteUser(invalidUserEmail, samRequestContext))
          }
          e.getMessage should include("invalid email address")
        }

        it("reject email addresses missing @") {
          val invalidUserEmail = WorkbenchEmail("bart.simpson_google.com")
          val e = intercept[Exception] {
            runAndWait(userService.inviteUser(invalidUserEmail, samRequestContext))
          }
          e.getMessage should include("invalid email address")
        }

        it("reject email addresses with bad chars") {
          val invalidUserEmail = WorkbenchEmail(s"barts${genBadChar}simpson@google.com")
          val e = intercept[Exception] {
            runAndWait(userService.inviteUser(invalidUserEmail, samRequestContext))
          }
          e.getMessage should include("invalid email address")
        }
      }

      describe("with an email address from a blocked domain") {
        val blockedDomain = "blocked.domain.com"

        it("fails with a message indicating that the email domain is invalid") {
          // Setup
          val invalidEmail = WorkbenchEmail(s"foo@${blockedDomain}")
          val userService = TestUserServiceBuilder()
            .withBlockedEmailDomain(blockedDomain)
            .build

          val e = intercept[Exception] {
            runAndWait(userService.inviteUser(invalidEmail, samRequestContext))
          }
          e.getMessage should include(s"email domain not permitted [${invalidEmail.value}]")
        }
      }
    }
  }

  describe("When an existing user") {
    describe("is invited") {
      it("fails with a message indicating that the user already exists") {
        val existingUser = genWorkbenchUserBoth.sample.get
        val userService = TestUserServiceBuilder()
          .withAllUsersGroup(allUsersGroup)
          .withExistingUser(existingUser)
          .build

        val e = intercept[Exception] {
          runAndWait(userService.inviteUser(existingUser.email, samRequestContext))
        }
        e.getMessage should include(s"${existingUser.email.value} already exists")
      }
    }
  }
}
