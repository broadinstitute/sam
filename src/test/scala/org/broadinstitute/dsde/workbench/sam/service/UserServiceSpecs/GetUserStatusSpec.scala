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

//  it should "return UserStatus.ldap and UserStatus.adminEnabled as false if user is disabled" in {
//    when(dirDAO.isEnabled(disabledUser.id, samRequestContext)).thenReturn(IO(false))
//    val status = service.getUserStatus(defaultUser.id, samRequestContext = samRequestContext).futureValue
//    status.value.enabled("ldap") shouldBe false
//    status.value.enabled("adminEnabled") shouldBe false
//  }
//
//  it should "return UserStatus.allUsersGroup as false if user is not in the All_Users group" in {
//    when(dirDAO.isGroupMember(allUsersGroup.id, defaultUser.id, samRequestContext)).thenReturn(IO(false))
//    val status = service.getUserStatus(defaultUser.id, samRequestContext = samRequestContext).futureValue
//    status.value.enabled("allUsersGroup") shouldBe false
//  }
//
//  it should "return UserStatus.google as false if user is not a member of their proxy group on Google" in {
//    when(googleExtensions.getUserStatus(enabledUser)).thenReturn(Future.successful(false))
//    val status = service.getUserStatus(enabledUser.id, samRequestContext = samRequestContext).futureValue
//    status.value.enabled("google") shouldBe false
//  }
//
//  it should "not return UserStatus.tosAccepted or UserStatus.adminEnabled if user's TOS status is false" in {
//    when(mockTosService.getTosStatus(enabledUser.id, samRequestContext)).thenReturn(IO(Option(false)))
//    val status = service.getUserStatus(enabledUser.id, samRequestContext = samRequestContext).futureValue
//    status.value.enabled shouldNot contain("tosAccepted")
//    status.value.enabled shouldNot contain("adminEnabled")
//  }
//
//  it should "not return UserStatus.tosAccepted or UserStatus.adminEnabled if user's TOS status is None" in {
//    when(mockTosService.getTosStatus(enabledUser.id, samRequestContext)).thenReturn(IO(None))
//    val status = service.getUserStatus(enabledUser.id, samRequestContext = samRequestContext).futureValue
//    status.value.enabled shouldNot contain("tosAccepted")
//    status.value.enabled shouldNot contain("adminEnabled")
//  }
//
//  it should "return no status for a user that does not exist" in {
//    when(dirDAO.loadUser(defaultUser.id, samRequestContext)).thenReturn(IO(None))
//    service.getUserStatus(defaultUser.id, samRequestContext = samRequestContext).futureValue shouldBe None
//  }
//
//  it should "return userDetailsOnly status when told to" in {
//    val statusNoEnabled = service.getUserStatus(defaultUser.id, true, samRequestContext).futureValue
//    statusNoEnabled shouldBe Some(UserStatus(UserStatusDetails(defaultUser.id, defaultUser.email), Map.empty))
//  }
//
//  it should "return userDetailsOnly status for a disabled user" in {
//    when(dirDAO.isEnabled(disabledUser.id, samRequestContext)).thenReturn(IO(false))
//    val statusNoEnabled = service.getUserStatus(defaultUser.id, true, samRequestContext).futureValue
//    statusNoEnabled shouldBe Some(UserStatus(UserStatusDetails(defaultUser.id, defaultUser.email), Map.empty))
//  }

}
