package org.broadinstitute.dsde.workbench.sam.service.UserServiceSpecs

import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.Generator.genWorkbenchUserBoth
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, MockDirectoryDaoBuilder}
import org.broadinstitute.dsde.workbench.sam.model.{BasicWorkbenchGroup, SamUser}
import org.broadinstitute.dsde.workbench.sam.service.{CloudExtensions, MockCloudExtensionsBuilder, TosService, UserService}
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{RETURNS_SMART_NULLS, doReturn}

import scala.concurrent.ExecutionContextExecutor

class GetUserStatusSpec extends UserServiceTestTraits {
  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  // Setup test vals
  val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set(), WorkbenchEmail("all_users@fake.com"))

  // Setup mocks
  val baseMockedDirectoryDao: DirectoryDAO = MockDirectoryDaoBuilder().withAllUsersGroup(allUsersGroup).build()
  val baseMockedCloudExtensions: CloudExtensions = MockCloudExtensionsBuilder(baseMockedDirectoryDao).build()
  val baseMockTosService: TosService = mock[TosService](RETURNS_SMART_NULLS)
  doReturn(IO(Option(true)))
    .when(baseMockTosService)
    .getTosStatus(any[WorkbenchUserId], any[SamRequestContext])

  // Setup a UserService that can be used in most of the tests
  val baseUserService = new UserService(baseMockedDirectoryDao, baseMockedCloudExtensions, Seq.empty, baseMockTosService)

  private def makeUserService(withEnabledUsers: List[SamUser] = List.empty,
                              withExistingUsers: List[SamUser] = List.empty): UserService = {
    val mockDirectoryDaoBuilder = MockDirectoryDaoBuilder().withAllUsersGroup(allUsersGroup)
    withEnabledUsers.map(mockDirectoryDaoBuilder.withEnabledUser)
    withExistingUsers.map(mockDirectoryDaoBuilder.withExistingUser)
    val mockDirectoryDao = mockDirectoryDaoBuilder.build()

    val mockCloudExtensionsBuilder = MockCloudExtensionsBuilder(mockDirectoryDao)
    (withEnabledUsers ::: withExistingUsers).map(mockCloudExtensionsBuilder.withEnabledUser)

    new UserService(mockDirectoryDao, mockCloudExtensionsBuilder.build(), Seq.empty, baseMockTosService)
  }

  describe("UserService.getUserStatus") {
    describe("for a user that is fully enabled") {
      describe("when `userDetailsOnly` is false") {
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

        describe("for a user that is disabled") {
          // TODO:  Currently ignored because the expected functionality is kind of ambiguous.
          // Need to run some manual tests against a live env to understand what the intended behavior should be
          ignore("returns a status with `ldap` and `adminEnabled` as false") {
            // Setup
            val samUser = genWorkbenchUserBoth.sample.get
            val userService = makeUserService(withExistingUsers = List(samUser))

            // Act
            val resultingStatus = runAndWait(userService.getUserStatus(samUser.id, false, samRequestContext))

            // Assert
            inside(resultingStatus.value) { status =>
              status should beForUser(samUser)
              "google" should beEnabledIn(status)
              "ldap" shouldNot beEnabledIn(status)
              "allUsersGroup" should beEnabledIn(status)
              "adminEnabled" shouldNot beEnabledIn(status)
              "tosAccepted" should beEnabledIn(status)
            }
          }
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
