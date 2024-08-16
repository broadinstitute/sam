package org.broadinstitute.dsde.workbench.sam.api

import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Directives.{onSuccess, reject}
import akka.http.scaladsl.server._
import akka.stream.Materializer
import org.broadinstitute.dsde.workbench.model.{ErrorReportSource, WorkbenchGroup}
import org.broadinstitute.dsde.workbench.sam.model.TermsOfServiceHistory
import org.broadinstitute.dsde.workbench.sam.model.api.{SamUser, SamUserAttributes}
import org.broadinstitute.dsde.workbench.sam.service._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.MockitoSugar.mock

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

// Don't like having any required parameters in the constructor, but alas, they're needed to be able to finally
// build the routes.  *sniff sniff* I smell potential refactoring.
//
// NOTE: In order to use the Routes from this builder to make an authenticated request, you must have either an
// AdminUser, an EnabledUser, or a DisabledUser specified - this is due to how SamRoutes works with Akka directives
// to make sure that the calling user is a valid user.
class MockSamRoutesBuilder(allUsersGroup: WorkbenchGroup)(implicit system: ActorSystem, materializer: Materializer) {

  private val cloudExtensionsBuilder: MockCloudExtensionsBuilder = MockCloudExtensionsBuilder(allUsersGroup).withNonAdminUser()
  private val userServiceBuilder: MockUserServiceBuilder = MockUserServiceBuilder()
  private val mockTosServiceBuilder = MockTosServiceBuilder()

  val mockResourceService = mock[ResourceService]

  // Needing to keep track of the enabled user is kind of gross.  But this is a single user that exists in the DB.  This
  // is used when we need to test admin routes that look up stuff about _another_ user (the `enabledUser`) when called
  // as the Admin User.  We can only set 1 enabledUser at a time because of the SamUserDirectives implemented with
  // SamRoutes in the build method
  private var enabledUser: Option[SamUser] = None
  private var disabledUser: Option[SamUser] = None
  private var adminUser: Option[SamUser] = None
  private var asServiceAdminUser = false
  private var callingUser: Option[SamUser] = None

  // TODO: *sniff sniff* I can't help but notice we're coordinating state between the userService and the
  //  cloudExtensions.  Same for other methods too.
  def withEnabledUser(samUser: SamUser): MockSamRoutesBuilder = {
    enabledUser = Option(samUser)
    userServiceBuilder.withEnabledUser(samUser)
    cloudExtensionsBuilder.withEnabledUser(samUser)
    mockTosServiceBuilder.withAcceptedStateForUser(samUser, isAccepted = true)
    this
  }

  def withEnabledUsers(samUsers: Iterable[SamUser]): MockSamRoutesBuilder = {
    enabledUser = Option(samUsers.head)
    userServiceBuilder.withEnabledUsers(samUsers)
    cloudExtensionsBuilder.withEnabledUsers(samUsers)
    mockTosServiceBuilder.withAllAccepted()
    this
  }

  def withDisabledUser(samUser: SamUser): MockSamRoutesBuilder = {
    disabledUser = Option(samUser)
    userServiceBuilder.withDisabledUser(samUser)
    cloudExtensionsBuilder.withDisabledUser(samUser)
    this
  }

  def withAllowedUser(samUser: SamUser): MockSamRoutesBuilder = {
    userServiceBuilder.withAllowedUser(samUser)
    this
  }
  def withAllowedUsers(samUsers: Iterable[SamUser]): MockSamRoutesBuilder = {
    userServiceBuilder.withAllowedUsers(samUsers)
    this
  }

  def withDisallowedUser(samUser: SamUser): MockSamRoutesBuilder = {
    userServiceBuilder.withDisallowedUser(samUser)
    this
  }

  def withDisallowedUsers(samUsers: Iterable[SamUser]): MockSamRoutesBuilder = {
    userServiceBuilder.withDisallowedUsers(samUsers)
    this
  }

  def withTosStateForUser(samUser: SamUser, isAccepted: Boolean, version: String = "0"): MockSamRoutesBuilder = {
    mockTosServiceBuilder.withAcceptedStateForUser(samUser, isAccepted, version)
    this
  }

  def withTermsOfServiceHistoryForUser(samUser: SamUser, tosHistory: TermsOfServiceHistory): MockSamRoutesBuilder = {
    mockTosServiceBuilder.withTermsOfServiceHistoryForUser(samUser, tosHistory)
    this
  }

  def withUserAttributes(samUser: SamUser, userAttributes: SamUserAttributes): MockSamRoutesBuilder = {
    userServiceBuilder.withUserAttributes(samUser, userAttributes)
    this
  }

  def callAsAdminUser(samUser: Option[SamUser] = None): MockSamRoutesBuilder = {
    cloudExtensionsBuilder.withAdminUser()
    callingUser = samUser
    this
  }

  def callAsAdminServiceUser(samUser: Option[SamUser] = None): MockSamRoutesBuilder = {
    asServiceAdminUser = true
    callingUser = samUser
    this
  }

  def callAsNonAdminUser(samUser: Option[SamUser] = None): MockSamRoutesBuilder = {
    cloudExtensionsBuilder.withNonAdminUser()
    callingUser = samUser
    this
  }

  def withBadEmail(): MockSamRoutesBuilder = {
    userServiceBuilder.withBadEmail()
    this
  }

  // Can only have 1 active user when making a request.  If the adminUser is set, that takes precedence, otherwise try
  // to get the enabledUser.
  private def getActiveUser: SamUser =
    callingUser
      .orElse(enabledUser)
      .orElse(disabledUser)
      .getOrElse(throw new Exception("Try building MockSamRoutes .withAdminUser(), .withEnabledUser(), withDisabledUser() first"))

  // TODO: This is not great.  We need to do a little state management to set and look up users and admin users.  This
  //  could be made better probably but the Akka stuff is kinda weird.  Also, unlike the other "Test Builder" classes,
  //  this one just builds and implements a trait, instead of just mocking out the dependencies that we then inject into
  //  the real object under test. I think the key here would be refactoring `SamRoutes`.
  def build: SamRoutes = {
    val mockUserService = userServiceBuilder.build
    val mockCloudExtensions = cloudExtensionsBuilder.build
    val mockTosService = mockTosServiceBuilder.build
    val mockPolicyEvaluatorService = mock[PolicyEvaluatorService]

    new SamRoutes(
      mockResourceService,
      mockUserService,
      null,
      null,
      null,
      mockPolicyEvaluatorService,
      mockTosService,
      null,
      null,
      null,
      None
    ) {
      import cats.effect.unsafe.implicits.global

      override val cloudExtensions: CloudExtensions = mockCloudExtensions

      override def withActiveUser(samRequestContext: SamRequestContext): Directive1[SamUser] = onSuccess {
        val user = samRequestContext.samUser.getOrElse(getActiveUser)

        val fakeOidcHeaders =
          OIDCHeaders(OAuth2BearerToken("dummy token"), user.googleSubjectId.toLeft(user.azureB2CId.get), user.email, user.googleSubjectId)

        StandardSamUserDirectives.getActiveSamUser(fakeOidcHeaders, userService, termsOfServiceConfig, samRequestContext).unsafeToFuture()
      }

      override def withUserAllowInactive(samRequestContext: SamRequestContext): Directive1[SamUser] = onSuccess {
        val user = samRequestContext.samUser.getOrElse(getActiveUser)
        val fakeOidcHeaders =
          OIDCHeaders(OAuth2BearerToken("dummy token"), user.googleSubjectId.toLeft(user.azureB2CId.get), user.email, user.googleSubjectId)

        StandardSamUserDirectives.getSamUser(fakeOidcHeaders, userService, samRequestContext).unsafeToFuture()
      }

      // We should really not be testing this, the routes should work identically whether the user
      // is newly created or not
      override def withNewUser(samRequestContext: SamRequestContext): Directive1[SamUser] = onSuccess {
        Future.successful(getActiveUser)
      }

      override def extensionRoutes(samUser: SamUser, samRequestContext: SamRequestContext): Route = reject

      implicit val errorReportSource: ErrorReportSource = ErrorReportSource("test")

      override def asAdminServiceUser: Directive0 =
        if (asServiceAdminUser) Directive.Empty else reject(AuthorizationFailedRejection)
    }
  }
}
