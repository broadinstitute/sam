package org.broadinstitute.dsde.workbench.sam.service.UserServiceSpecs

import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.sam.Generator.genWorkbenchUserGoogle
import org.broadinstitute.dsde.workbench.sam.model.{BasicWorkbenchGroup, SamUser}
import org.broadinstitute.dsde.workbench.sam.service.{CloudExtensions, TestUserServiceBuilder}
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.verify

import scala.concurrent.ExecutionContextExecutor

class InviteUserSpec extends UserServiceTestTraits {
  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  // Setup test vals
  val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set(), WorkbenchEmail("all_users@fake.com"))
  val blockedDomain = "blocked.domain.com"

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
          val userCaptor = ArgumentCaptor.forClass(classOf[SamUser])
          verify(userService.directoryDAO).createUser(userCaptor.capture(), any[SamRequestContext])
          val capturedInvitedUser: SamUser = userCaptor.getValue
          capturedInvitedUser.email shouldBe invitedUserEmail
        }

        it("creates the user in GCP") {
          // need to user a captor here because userService.inviteUser creates a new SamUser instance using the email
          // address that is passed to it, and that new instance is what is sent to Google
          val userCaptor = ArgumentCaptor.forClass(classOf[SamUser])
          verify(userService.cloudExtensions).onUserCreate(userCaptor.capture(), any[SamRequestContext])
          val capturedInvitedUser: SamUser = userCaptor.getValue
          capturedInvitedUser.email shouldBe invitedUserEmail
        }

        // we should perform these assertions, however right now they're buried inside cloudExtensions.onUserCreate, which is dumb
        ignore("creates a proxy group for the user on GCP") {}
        ignore("adds the user to the All_Users group") {}
      }

      describe("with an invalid email address") {
        it("errors telling us the email address is invalid") {
          val invalidUserEmail = WorkbenchEmail("not an email")
          val e = intercept[Exception] {
            runAndWait(userService.inviteUser(invalidUserEmail, samRequestContext))
          }
          e.getMessage should include("invalid email address")
        }
      }
    }
  }
}
