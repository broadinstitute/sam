package org.broadinstitute.dsde.workbench.sam.service.UserServiceSpecs

import cats.effect.unsafe.implicits.global
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
      // Shared Setup - create a UserService with no existing users
      val samUser = genWorkbenchUserBoth.sample.get
      val userService = TestUserServiceBuilder()
        .withAllUsersGroup(allUsersGroup)
        .withExistingUsers(List.empty) // Just being explicit
        .build

      it("returns an empty response") {
        val resultingStatus = userService.getUserStatus(samUser.id, false, samRequestContext).unsafeRunSync()

        resultingStatus shouldBe empty
      }
    }

    describe("for a fully activated user") {
      describe("that has accepted the ToS") {
        // Shared Setup - create a UserService with a fully activated user who has accepted the ToS
        val samUser = genWorkbenchUserBoth.sample.get
        val userService = TestUserServiceBuilder()
          .withAllUsersGroup(allUsersGroup)
          .withFullyActivatedUser(samUser)
          .withToSAcceptanceStateForUser(samUser, true)
          .build

        it("returns a status with all components enabled") {
          val resultingStatus = runAndWait(userService.getUserStatus(samUser.id, false, samRequestContext))

          inside(resultingStatus.value) { status =>
            status should beForUser(samUser)
            "google" should beEnabledIn(status)
            "ldap" should beEnabledIn(status)
            "allUsersGroup" should beEnabledIn(status)
            "adminEnabled" should beEnabledIn(status)
            "tosAccepted" should beEnabledIn(status)
          }
        }

        describe("and only user details are requested") {
          it("returns a status with user information and without component information") {
            val resultingStatus = runAndWait(userService.getUserStatus(samUser.id, userDetailsOnly = true, samRequestContext))

            inside(resultingStatus.value) { status =>
              status should beForUser(samUser)
              status.enabled shouldBe empty
            }
          }
        }
      }

      describe("that has not accepted the ToS") {
        // Shared Setup - create a UserService with a fully activated user who has not accepted ToS
        val samUser = genWorkbenchUserBoth.sample.get
        val userService = TestUserServiceBuilder()
          .withAllUsersGroup(allUsersGroup)
          .withFullyActivatedUser(samUser)
          .withToSAcceptanceStateForUser(samUser, false)
          .build

        it("returns a status with ToS disabled and all other components enabled") {
          val resultingStatus = runAndWait(userService.getUserStatus(samUser.id, false, samRequestContext))

          inside(resultingStatus.value) { status =>
            status should beForUser(samUser)
            "google" should beEnabledIn(status)
            "ldap" should beEnabledIn(status)
            "allUsersGroup" should beEnabledIn(status)
            "adminEnabled" should beEnabledIn(status)
            "tosAccepted" shouldNot beEnabledIn(status)
          }
        }

        describe("and only user details are requested") {
          it("returns a status with user information and without component information") {
            val userDetailsOnly = true

            // Act
            val resultingStatus = runAndWait(userService.getUserStatus(samUser.id, userDetailsOnly, samRequestContext))

            // Assert
            inside(resultingStatus.value) { status =>
              status should beForUser(samUser)
              status.enabled shouldBe empty
            }
          }
        }
      }
    }

    describe("for an invited user") {
      // Shared Setup - create a UserService with an invited user
      val samUser = genWorkbenchUserBoth.sample.get
      val userService = TestUserServiceBuilder()
        .withAllUsersGroup(allUsersGroup)
        .withInvitedUser(samUser)
        .build

      it("returns a status with all components disabled") {
        val resultingStatus = runAndWait(userService.getUserStatus(samUser.id, false, samRequestContext))

        inside(resultingStatus.value) { status =>
          status should beForUser(samUser)
          "google" shouldNot beEnabledIn(status)
          "ldap" shouldNot beEnabledIn(status)
          "allUsersGroup" shouldNot beEnabledIn(status)
          "adminEnabled" shouldNot beEnabledIn(status)
          "tosAccepted" shouldNot beEnabledIn(status)
        }
      }

      describe("and only user details are requested") {
        it("returns a status with user information and without component information") {
          val resultingStatus = runAndWait(userService.getUserStatus(samUser.id, userDetailsOnly = true, samRequestContext))

          inside(resultingStatus.value) { status =>
            status should beForUser(samUser)
            status.enabled shouldBe empty
          }
        }
      }
    }
  }
}
