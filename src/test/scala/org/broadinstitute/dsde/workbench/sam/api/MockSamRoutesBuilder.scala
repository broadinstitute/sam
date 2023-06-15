package org.broadinstitute.dsde.workbench.sam.api

import akka.actor.ActorSystem
import akka.http.scaladsl.server.Directives.{onSuccess, reject}
import akka.http.scaladsl.server.{Directive1, Route}
import akka.stream.Materializer
import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.WorkbenchGroup
import org.broadinstitute.dsde.workbench.openTelemetry.OpenTelemetryMetrics
import org.broadinstitute.dsde.workbench.sam.TestSupport.enabledMapNoTosAccepted
import org.broadinstitute.dsde.workbench.sam.dataAccess.MockDirectoryDaoBuilder
import org.broadinstitute.dsde.workbench.sam.model.{SamUser, UserStatus, UserStatusDetails}
import org.broadinstitute.dsde.workbench.sam.service._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentMatchersSugar.{any, eqTo}
import org.mockito.IdiomaticMockito.StubbingOps
import org.mockito.MockitoSugar.mock

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

// Don't like having any required parameters in the constructor, but alas, they're needed to be able to finally
// build the routes.  *sniff sniff* I smell potential refactoring.
class MockSamRoutesBuilder(allUsersGroup: WorkbenchGroup)(implicit system: ActorSystem, materializer: Materializer, openTelemetry: OpenTelemetryMetrics[IO]) {

  //This should be able to be removed, but SamRoutes is dependant on having a DirectoryDAO
  //object. Maybe that can be changed but would require additional work
  private val directoryDaoBuilder: MockDirectoryDaoBuilder = MockDirectoryDaoBuilder(allUsersGroup)
  private val cloudExtensionsBuilder: MockCloudExtensionsBuilder = MockCloudExtensionsBuilder(allUsersGroup)
  //mock user service builder?
  private val mockUserService = mock[UserService]
  //mockUserService.

  // newUser is used on requests testing the creation of that user
  private var newUser: Option[SamUser] = None

  // Needing to keep track of the enabled user is kind of gross.  But this is a single user that exists in the DB.  This
  // is used when we need to test admin routes that look up stuff about _another_ user (the `enabledUser`) when called
  // as the Admin User.  We can only set 1 enabledUser at a time because of the SamUserDirectives implemented with
  // SamRoutes in the build method
  private var enabledUser: Option[SamUser] = None
  private var disabledUser: Option[SamUser] = None

  private var adminUser: Option[SamUser] = None

  // TODO: *sniff sniff* I can't help but notice we're coordinating state between the directoryDAO and the
  //  cloudExtensions.  Same for other methods too.
  def withEnabledUser(samUser: SamUser): MockSamRoutesBuilder = {
    enabledUser = Option(samUser)
    //directoryDaoBuilder.withEnabledUser(samUser)
    //cloudExtensionsBuilder.withEnabledUser(samUser)
    //we want to remove directory dao and cloud extension and replace with all user service calls since that is what is called by admin routes
    mockUserService.disableUser(eqTo(samUser.id), any[SamRequestContext]) returns {
      IO(Option(UserStatus(UserStatusDetails(samUser.id, samUser.email),
        enabledMapNoTosAccepted + ("ldap" -> false) + ("adminEnabled" -> false))))
    }
    //mockedCloudExtensions.onUserDisable(any[SamUser], any[SamRequestContext]) returns IO.unit
    this
  }

  def withDisabledUser(samUser: SamUser): MockSamRoutesBuilder = {
    disabledUser = Option(samUser)
    //directoryDaoBuilder.withDisabledUser(samUser)
    //cloudExtensionsBuilder.withDisabledUser(samUser)
    mockUserService.disableUser(eqTo(samUser.id), any[SamRequestContext]) returns {
      IO(Option(UserStatus(UserStatusDetails(samUser.id, samUser.email), enabledMapNoTosAccepted)))
    }
    this
  }

  def withAdminUser(samUser: SamUser): MockSamRoutesBuilder = {
    adminUser = Option(samUser)
    //cloudExtensionsBuilder.withAdminUser(samUser) // duplicated line
    this
  }

  def withNonAdminUser(samUser: SamUser): MockSamRoutesBuilder = {
    // adminUser = Option(samUser)
    //cloudExtensionsBuilder.withNonAdminUser(samUser) // duplicated line
    this
  }

  // Use this method to set up tests that need to test creating/registering a brand new user, aka the `newUser`
  def withNewUser(samUser: SamUser): MockSamRoutesBuilder = {
    newUser = Option(samUser)
    this
  }

  // Can only have 1 active user when making a request.  If the adminUser is set, that takes precedence, otherwise try
  // to get the enabledUser.
  private def getActiveUser: SamUser =
    adminUser
      .orElse(enabledUser)
      .getOrElse(throw new Exception("Try building MockSamRoutes .withAdminUser() or .withEnabledUser() first"))

  // TODO: This is not great.  We need to do a little state management to set and look up users and admin users.  This
  //  could be made better probably but the Akka stuff is kinda weird.  Also, unlike the other "Test Builder" classes,
  //  this one just builds and implements a trait, instead of just mocking out the dependencies that we then inject into
  //  the real object under test. I think the key here would be refactoring `SamRoutes`.
  def build: SamRoutes = {
    val mockDirectoryDao = directoryDaoBuilder.build
    val mockCloudExtensions = cloudExtensionsBuilder.build
    val mockTosService = MockTosServiceBuilder().withAllAccepted().build

    new SamRoutes(
      null,
      mockUserService,
      //new UserService(mockDirectoryDao, mockCloudExtensions, Seq.empty, mockTosService),
      null,
      null,
      null,
      mockDirectoryDao,
      null,
      mockTosService,
      null,
      null,
      None
    ) {
      override val cloudExtensions: CloudExtensions = mockCloudExtensions

      override def withActiveUser(samRequestContext: SamRequestContext): Directive1[SamUser] = onSuccess {
        Future.successful(getActiveUser)
      }

      override def withUserAllowInactive(samRequestContext: SamRequestContext): Directive1[SamUser] = onSuccess {
        Future.successful(disabledUser.getOrElse(throw new Exception("Try building MockSamRoutes .withDisabledUser() first")))
      }

      override def withNewUser(samRequestContext: SamRequestContext): Directive1[SamUser] = onSuccess {
        Future.successful(newUser match {
          case Some(user) => user
          case None => throw new Exception("Try building MockSamRoutes .withNewUser() first")
        })
      }

      override def extensionRoutes(samUser: SamUser, samRequestContext: SamRequestContext): Route = reject
    }
  }
}
