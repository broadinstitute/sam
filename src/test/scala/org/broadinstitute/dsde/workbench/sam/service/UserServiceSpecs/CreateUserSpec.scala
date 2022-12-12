package org.broadinstitute.dsde.workbench.sam.service.UserServiceSpecs

import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchExceptionWithErrorReport, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.Generator.genWorkbenchUserBoth
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, MockDirectoryDaoBuilder}
import org.broadinstitute.dsde.workbench.sam.model.{BasicWorkbenchGroup, SamUser, UserStatus, UserStatusDetails}
import org.broadinstitute.dsde.workbench.sam.service.{CloudExtensions, MockCloudExtensionsBuilder, TosService, UserService}
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.scalatest.{Inside, OptionValues}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.ExecutionContextExecutor

class CreateUserSpec
  extends AnyFunSpec
  with Matchers
  with TestSupport
  with MockitoSugar
  with ScalaFutures
  with Inside
  with OptionValues {

  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  val defaultUser: SamUser = genWorkbenchUserBoth.sample.get
  val enabledDefaultUserStatus: UserStatus = UserStatus(UserStatusDetails(defaultUser.id, defaultUser.email),
                                            Map("tosAccepted" -> true,
                                                "google" -> true,
                                                "ldap" -> true,
                                                "allUsersGroup" -> true,
                                                "adminEnabled" -> true))

  val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set(), WorkbenchEmail("all_users@fake.com"))

  val baseMockedDirectoryDao: DirectoryDAO = new MockDirectoryDaoBuilder().withAllUsersGroup(allUsersGroup).build()
  val baseMockedCloudExtensions: CloudExtensions = new MockCloudExtensionsBuilder(baseMockedDirectoryDao).build()

  val baseMockTosService: TosService = mock[TosService](RETURNS_SMART_NULLS)
  when(baseMockTosService.getTosStatus(any[WorkbenchUserId], any[SamRequestContext])).thenReturn(IO(Option(true)))

  val blockedDomain = "blocked.domain.com"
  val baseUserService = new UserService(baseMockedDirectoryDao, baseMockedCloudExtensions, Seq(blockedDomain), baseMockTosService)

  describe("UserService.createUser") {
    describe("when it succeeds") {
      it("returns a UserStatus object indicating that all components are enabled") {
        val userStatus = runAndWait(baseUserService.createUser(defaultUser, samRequestContext))
        userStatus shouldBe enabledDefaultUserStatus
      }
    }

    describe("fails") {
      it("when user's email is in a blocked domain") {
        val userWithBadEmail = defaultUser.copy(email = WorkbenchEmail(s"foo@${blockedDomain}"))
        assertThrows[WorkbenchExceptionWithErrorReport] {
          runAndWait(baseUserService.createUser(userWithBadEmail, samRequestContext))
        }
      }

      it("when user's email is not a properly formatted email address") {
        val userWithBadEmail = defaultUser.copy(email = WorkbenchEmail("foo"))
        assertThrows[WorkbenchExceptionWithErrorReport] {
          runAndWait(baseUserService.createUser(userWithBadEmail, samRequestContext))
        }
      }
    }
  }

}
