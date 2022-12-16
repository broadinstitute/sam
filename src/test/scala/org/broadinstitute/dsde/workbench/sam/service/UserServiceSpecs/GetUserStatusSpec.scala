package org.broadinstitute.dsde.workbench.sam.service.UserServiceSpecs

import org.broadinstitute.dsde.workbench.sam.Generator.{genBasicWorkbenchGroup, genWorkbenchUserBoth}
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup
import org.broadinstitute.dsde.workbench.sam.service._

import scala.concurrent.ExecutionContextExecutor

class GetUserStatusSpec extends UserServiceTestTraits {
  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  // Setup test vals
  val allUsersGroup: BasicWorkbenchGroup = genBasicWorkbenchGroup.sample.get.copy(id = CloudExtensions.allUsersGroupName)

  describe("UserService.getUserStatus") {
    describe("for a user that does not exist") {
      it("returns an empty response") {
        // Setup
        val samUser = genWorkbenchUserBoth.sample.get
        val userService = TestUserServiceBuilder()
          .withAllUsersGroup(allUsersGroup)
          .withExistingUsers(List.empty) // Just being explicit
          .build

        // Act
        val resultingStatus = runAndWait(userService.getUserStatus(samUser.id, false, samRequestContext))

        // Assert
        resultingStatus shouldBe empty
      }
    }

    describe("for a fully activated user") {
      describe("that has accepted the ToS") {
        it("returns a status with all components enabled") {
          // Setup
          val samUser = genWorkbenchUserBoth.sample.get
          val userService = TestUserServiceBuilder()
            .withAllUsersGroup(allUsersGroup)
            .withFullyActivatedUser(samUser)
            .withToSAcceptanceStateForUser(samUser, true)
            .build

          // Act
          val resultingStatus = runAndWait(userService.getUserStatus(samUser.id, false, samRequestContext))

          // Assert
          inside(resultingStatus.value) { status =>
            status should beForUser(samUser)
            "google" should beEnabledIn(status)
            "ldap" should beEnabledIn(status)
            "allUsersGroup" should beEnabledIn(status)
            "adminEnabled" should beEnabledIn(status)
            "tosAccepted" should beEnabledIn(status)
          }
        }
      }

      describe("that has not accepted the ToS") {
        // Setup
        val samUser = genWorkbenchUserBoth.sample.get
        val userService = TestUserServiceBuilder()
          .withAllUsersGroup(allUsersGroup)
          .withFullyActivatedUser(samUser)
          .withToSAcceptanceStateForUser(samUser, false)
          .build

        // Act
        val resultingStatus = runAndWait(userService.getUserStatus(samUser.id, false, samRequestContext))

        // Assert
        inside(resultingStatus.value) { status =>
          status should beForUser(samUser)
          "google" should beEnabledIn(status)
          "ldap" should beEnabledIn(status)
          "allUsersGroup" should beEnabledIn(status)
          "adminEnabled" should beEnabledIn(status)
          "tosAccepted" shouldNot beEnabledIn(status)
        }
      }
    }

    describe("for an invited user") {
      it("returns a status with all components disabled") {
        // Setup
        val samUser = genWorkbenchUserBoth.sample.get
        val userService = TestUserServiceBuilder()
          .withAllUsersGroup(allUsersGroup)
          .withInvitedUser(samUser)
          .withNoUsersHavingAcceptedTos()
          .build

        // Act
        val resultingStatus = runAndWait(userService.getUserStatus(samUser.id, false, samRequestContext))

        // Assert
        inside(resultingStatus.value) { status =>
          status should beForUser(samUser)
          "google" shouldNot beEnabledIn(status)
          "ldap" shouldNot beEnabledIn(status)
          "allUsersGroup" shouldNot beEnabledIn(status)
          "adminEnabled" shouldNot beEnabledIn(status)
          "tosAccepted" shouldNot beEnabledIn(status)
        }
      }
    }
  }
}
