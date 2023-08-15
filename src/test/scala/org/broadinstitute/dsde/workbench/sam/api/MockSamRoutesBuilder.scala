package org.broadinstitute.dsde.workbench.sam.api

import akka.actor.ActorSystem
import akka.http.scaladsl.server.Directives.{onSuccess, reject}
import akka.http.scaladsl.server._
import akka.stream.Materializer
import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.{ErrorReportSource, WorkbenchGroup}
import org.broadinstitute.dsde.workbench.openTelemetry.OpenTelemetryMetrics
import org.broadinstitute.dsde.workbench.sam.model.SamUser
import org.broadinstitute.dsde.workbench.sam.service._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

// Don't like having any required parameters in the constructor, but alas, they're needed to be able to finally
// build the routes.  *sniff sniff* I smell potential refactoring.
class MockSamRoutesBuilder(allUsersGroup: WorkbenchGroup)(implicit system: ActorSystem, materializer: Materializer, openTelemetry: OpenTelemetryMetrics[IO]) {

  private val cloudExtensionsBuilder: MockCloudExtensionsBuilder = MockCloudExtensionsBuilder(allUsersGroup)
  cloudExtensionsBuilder.withNonAdminUser()
  private val userServiceBuilder: MockUserServiceBuilder = MockUserServiceBuilder()

  // Needing to keep track of the enabled user is kind of gross.  But this is a single user that exists in the DB.  This
  // is used when we need to test admin routes that look up stuff about _another_ user (the `enabledUser`) when called
  // as the Admin User.  We can only set 1 enabledUser at a time because of the SamUserDirectives implemented with
  // SamRoutes in the build method
  private var enabledUser: Option[SamUser] = None
  private var disabledUser: Option[SamUser] = None
  private var adminUser: Option[SamUser] = None
  private var asServiceAdminUser = false

  // TODO: *sniff sniff* I can't help but notice we're coordinating state between the userService and the
  //  cloudExtensions.  Same for other methods too.
  def withEnabledUser(samUser: SamUser): MockSamRoutesBuilder = {
    enabledUser = Option(samUser)
    userServiceBuilder.withEnabledUser(samUser)
    cloudExtensionsBuilder.withEnabledUser(samUser)
    this
  }

  def withEnabledUsers(samUsers: Iterable[SamUser]): MockSamRoutesBuilder = {
    enabledUser = Option(samUsers.head)
    userServiceBuilder.withEnabledUsers(samUsers)
    cloudExtensionsBuilder.withEnabledUsers(samUsers)
    this
  }

  def withDisabledUser(samUser: SamUser): MockSamRoutesBuilder = {
    disabledUser = Option(samUser)
    userServiceBuilder.withDisabledUser(samUser)
    cloudExtensionsBuilder.withDisabledUser(samUser)
    this
  }

  def callAsAdminUser(): MockSamRoutesBuilder = {
    cloudExtensionsBuilder.withAdminUser()
    this
  }

  def callAsAdminServiceUser(): MockSamRoutesBuilder = {
    asServiceAdminUser = true
    this
  }

  def callAsNonAdminUser(): MockSamRoutesBuilder = {
    cloudExtensionsBuilder.withNonAdminUser()
    this
  }

  def withBadEmail(): MockSamRoutesBuilder = {
    userServiceBuilder.withBadEmail()
    this
  }

  // Can only have 1 active user when making a request.  If the adminUser is set, that takes precedence, otherwise try
  // to get the enabledUser.
  private def getActiveUser: SamUser =
    enabledUser
      .orElse(disabledUser)
      .getOrElse(throw new Exception("Try building MockSamRoutes .withAdminUser(), .withEnabledUser(), withDisabledUser() first"))

  // TODO: This is not great.  We need to do a little state management to set and look up users and admin users.  This
  //  could be made better probably but the Akka stuff is kinda weird.  Also, unlike the other "Test Builder" classes,
  //  this one just builds and implements a trait, instead of just mocking out the dependencies that we then inject into
  //  the real object under test. I think the key here would be refactoring `SamRoutes`.
  def build: SamRoutes = {
    val mockUserService = userServiceBuilder.build
    val mockCloudExtensions = cloudExtensionsBuilder.build
    val mockTosService = MockTosServiceBuilder().withAllAccepted().build

    new SamRoutes(
      null,
      mockUserService,
      null,
      null,
      null,
      null,
      mockTosService,
      null,
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

      // We should really not be testing this, the routes should work identically whether the user
      // is newly created or not
      override def withNewUser(samRequestContext: SamRequestContext): Directive1[SamUser] = onSuccess {
        Future.successful(getActiveUser)
      }

      override def extensionRoutes(samUser: SamUser, samRequestContext: SamRequestContext): Route = reject
      implicit val errorReportSource: ErrorReportSource = ErrorReportSource("test")
      override def asAdminServiceUser: Directive0 = if (asServiceAdminUser) Directive.Empty
      else reject(AuthorizationFailedRejection)
    }
  }
}
