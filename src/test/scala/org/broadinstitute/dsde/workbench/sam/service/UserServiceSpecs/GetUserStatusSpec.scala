package org.broadinstitute.dsde.workbench.sam.service.UserServiceSpecs

import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.sam.Generator.genWorkbenchUserBoth
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, MockDirectoryDaoBuilder}
import org.broadinstitute.dsde.workbench.sam.model.{BasicWorkbenchGroup, SamUser}
import org.broadinstitute.dsde.workbench.sam.service._

import scala.concurrent.ExecutionContextExecutor

class GetUserStatusSpec extends UserServiceTestTraits {
  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  // Setup test vals
  val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set(), WorkbenchEmail("all_users@fake.com"))

  // Setup mocks
  val baseMockedDirectoryDao: DirectoryDAO = MockDirectoryDaoBuilder().withAllUsersGroup(allUsersGroup).build
  val baseMockedCloudExtensions: CloudExtensions = MockCloudExtensionsBuilder(baseMockedDirectoryDao).build
  val baseMockTosService: TosService = MockTosServiceBuilder().withNoneAccepted().build

  // Setup a UserService that can be used in most of the tests
  val baseUserService = new UserService(baseMockedDirectoryDao, baseMockedCloudExtensions, Seq.empty, baseMockTosService)

  private def makeUserService(withEnabledUsers: List[SamUser] = List.empty,
                              withExistingUsers: List[SamUser] = List.empty): UserService = {
    // Setup the DirectoryDao and TosService
    val mockDirectoryDaoBuilder = MockDirectoryDaoBuilder().withAllUsersGroup(allUsersGroup)
    val mockTosServiceBuilder = MockTosServiceBuilder().withNoneAccepted()
    // Add all Enabled Users to the DirectoryDao and TosService
    withEnabledUsers.map { user =>
      mockDirectoryDaoBuilder.withFullyActivatedUser(user)
      mockTosServiceBuilder.withAcceptedStateForUser(user, true)
    }
    // Add all Existing Users to just the DirectoryDao (because they should only exist in the Sam database and bare
    // minimum existence means they should not have accepted ToS yet either)
    withExistingUsers.map(mockDirectoryDaoBuilder.withExistingUser)

    // Need the DirectoryDao so that we can make the CloudExtensions :(
    val mockDirectoryDao = mockDirectoryDaoBuilder.build

    // Setup MockCloudExtensions - Enabled users are all that matter because users who only exist in Sam should not have
    // any state on Google yet
    val mockCloudExtensionsBuilder = MockCloudExtensionsBuilder(mockDirectoryDao)
    withEnabledUsers.map(mockCloudExtensionsBuilder.withEnabledUser)

    new UserService(mockDirectoryDao, mockCloudExtensionsBuilder.build, Seq.empty, mockTosServiceBuilder.build)
  }

  describe("UserService.getUserStatus") {
    describe("for a user that does not exist") {
      it("returns an empty response") {
        // Setup
        val samUser = genWorkbenchUserBoth.sample.get
        val userService = makeUserService(withExistingUsers = List.empty)

        // Act
        val resultingStatus = runAndWait(userService.getUserStatus(samUser.id, false, samRequestContext))

        // Assert
        resultingStatus shouldBe empty
      }
    }

    describe("for an enabled user") {
      describe("that has accepted the ToS") {
        it("returns a status with all components enabled") {
          // Setup
          val samUser = genWorkbenchUserBoth.sample.get
          val userService = makeUserService(withEnabledUsers = List(samUser))

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
        val directoryDAO = MockDirectoryDaoBuilder()
          .withAllUsersGroup(allUsersGroup)
          .withExistingUser(samUser).build
        val cloudExtensions = MockCloudExtensionsBuilder(directoryDAO).build
        val tosService = MockTosServiceBuilder().withNoneAccepted().build
        val userService = new UserService(directoryDAO, cloudExtensions, Seq.empty, tosService)

      }
    }

    describe("for an invited user") {
        it("returns a status with all components disabled") {
          // Setup
          val samUser = genWorkbenchUserBoth.sample.get
          val directoryDAO = MockDirectoryDaoBuilder()
            .withAllUsersGroup(allUsersGroup)
            .withExistingUser(samUser).build
          val cloudExtensions = MockCloudExtensionsBuilder(directoryDAO).build
          val tosService = MockTosServiceBuilder().withNoneAccepted().build
          val userService = new UserService(directoryDAO, cloudExtensions, Seq.empty, tosService)

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
