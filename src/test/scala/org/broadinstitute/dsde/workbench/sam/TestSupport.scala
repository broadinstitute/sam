package org.broadinstitute.dsde.workbench.sam

import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.RawHeader
import akka.stream.Materializer
import com.typesafe.config.ConfigFactory
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.dataaccess.PubSubNotificationDAO
import org.broadinstitute.dsde.workbench.google.mock.{MockGoogleDirectoryDAO, MockGoogleIamDAO, MockGooglePubSubDAO, MockGoogleStorageDAO}
import org.broadinstitute.dsde.workbench.google.{GoogleDirectoryDAO, GoogleIamDAO}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.api._
import org.broadinstitute.dsde.workbench.sam.config.{GoogleServicesConfig, PetServiceAccountConfig, _}
import org.broadinstitute.dsde.workbench.sam.directory.MockDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.google.{GoogleExtensionRoutes, GoogleExtensions, GoogleKeyCache}
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.MockAccessPolicyDAO
import org.broadinstitute.dsde.workbench.sam.service._
import org.broadinstitute.dsde.workbench.sam.service.UserService._
import org.scalatest.prop.{Configuration, PropertyChecks}
import org.scalatest.{FlatSpec, Matchers}
import StandardUserInfoDirectives._
import org.scalactic.Equality

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Awaitable, ExecutionContext}

/**
  * Created by dvoet on 6/27/17.
  */
trait TestSupport{
  def runAndWait[T](f: Awaitable[T]): T = Await.result(f, Duration.Inf)
}

trait PropertyBasedTesting extends FlatSpec with PropertyChecks with Configuration with Matchers {
  implicit override val generatorDrivenConfig: PropertyCheckConfiguration = PropertyCheckConfiguration(minSuccessful = 3)
}

object TestSupport extends TestSupport{
  implicit val eqThrowable: Equality[Throwable] = new Equality[Throwable]{
    override def areEqual(a: Throwable, b: Any): Boolean = b.isInstanceOf[Throwable] && a.getMessage == b.asInstanceOf[Throwable].getMessage
  }
  val config = ConfigFactory.load()
  val petServiceAccountConfig = config.as[PetServiceAccountConfig]("petServiceAccount")
  val googleServicesConfig = config.as[GoogleServicesConfig]("googleServices")
  val configResourceTypes = config.as[Map[String, ResourceType]]("resourceTypes").values.map(rt => rt.name -> rt).toMap
  val defaultUserEmail = WorkbenchEmail("newuser@new.com")
  def proxyEmail(workbenchUserId: WorkbenchUserId) = WorkbenchEmail(s"PROXY_$workbenchUserId@${googleServicesConfig.appsDomain}")
  def googleSubjectIdHeaderWithId(googleSubjectId: GoogleSubjectId) = RawHeader(googleSubjectIdHeader, googleSubjectId.value)
  def genGoogleSubjectId(): GoogleSubjectId = GoogleSubjectId(genRandom(System.currentTimeMillis()))

  def genGoogleSubjectIdHeader = RawHeader(googleSubjectIdHeader, genRandom(System.currentTimeMillis()))
  val defaultEmailHeader = RawHeader(emailHeader, defaultUserEmail.value)
  def genDefaultEmailHeader(workbenchEmail: WorkbenchEmail) = RawHeader(emailHeader, workbenchEmail.value)

  def genSamDependencies(resourceTypes: Map[ResourceTypeName, ResourceType] = Map.empty, googIamDAO: Option[GoogleIamDAO] = None, googleServicesConfig: GoogleServicesConfig = googleServicesConfig, cloudExtensions: Option[CloudExtensions] = None, googleDirectoryDAO: Option[GoogleDirectoryDAO] = None)(implicit system: ActorSystem, executionContext: ExecutionContext) = {
    val googleDirectoryDAO = new MockGoogleDirectoryDAO()
    val directoryDAO = new MockDirectoryDAO()
    val googleIamDAO = googIamDAO.getOrElse(new MockGoogleIamDAO())
    val policyDAO = new MockAccessPolicyDAO()
    val pubSubDAO = new MockGooglePubSubDAO()
    val googleStorageDAO = new MockGoogleStorageDAO()
    val notificationDAO = new PubSubNotificationDAO(pubSubDAO, "foo")
    val cloudKeyCache = new GoogleKeyCache(googleIamDAO, googleStorageDAO, pubSubDAO, googleServicesConfig, petServiceAccountConfig)
    val googleExt = cloudExtensions.getOrElse(new GoogleExtensions(
      directoryDAO,
      policyDAO,
      googleDirectoryDAO,
      null,
      googleIamDAO,
      null,
      null,
      cloudKeyCache,
      notificationDAO,
      googleServicesConfig,
      petServiceAccountConfig,
      configResourceTypes(CloudExtensions.resourceTypeName)))
    val mockResourceService = new ResourceService(resourceTypes, policyDAO, directoryDAO, googleExt, "example.com")
    val mockManagedGroupService = new ManagedGroupService(mockResourceService, resourceTypes, policyDAO, directoryDAO, googleExt, "example.com")

    SamDependencies(mockResourceService, new UserService(directoryDAO, googleExt), new StatusService(directoryDAO, googleExt), mockManagedGroupService, directoryDAO, googleExt)
  }

  def genSamRoutes(samDependencies: SamDependencies)(implicit system: ActorSystem, executionContext: ExecutionContext, materializer: Materializer): SamRoutes = new SamRoutes(samDependencies.resourceService, samDependencies.userService, samDependencies.statusService, samDependencies.managedGroupService, null, samDependencies.directoryDAO)
    with StandardUserInfoDirectives
    with GoogleExtensionRoutes {
    override val cloudExtensions: CloudExtensions = samDependencies.cloudExtensions
    override val googleExtensions: GoogleExtensions = if(samDependencies.cloudExtensions.isInstanceOf[GoogleExtensions]) samDependencies.cloudExtensions.asInstanceOf[GoogleExtensions] else null
    val googleKeyCache = if(samDependencies.cloudExtensions.isInstanceOf[GoogleExtensions])samDependencies.cloudExtensions.asInstanceOf[GoogleExtensions].googleKeyCache else null
  }
}

final case class SamDependencies(resourceService: ResourceService, userService: UserService, statusService: StatusService, managedGroupService: ManagedGroupService, directoryDAO: MockDirectoryDAO, val cloudExtensions: CloudExtensions)