package org.broadinstitute.dsde.workbench.sam

import java.io.File
import java.net.URI

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import cats.data.NonEmptyList
import cats.effect.{ExitCode, IO, IOApp}
import cats.implicits._
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import com.unboundid.ldap.sdk.{LDAPConnection, LDAPConnectionPool}
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import org.broadinstitute.dsde.workbench.dataaccess.PubSubNotificationDAO
import org.broadinstitute.dsde.workbench.google.GoogleCredentialModes.{Json, Pem}
import org.broadinstitute.dsde.workbench.google.util.DistributedLock
import org.broadinstitute.dsde.workbench.google.{
  GoogleDirectoryDAO,
  GoogleFirestoreInterpreter,
  GoogleStorageInterpreter,
  GoogleStorageService,
  HttpGoogleDirectoryDAO,
  HttpGoogleIamDAO,
  HttpGoogleProjectDAO,
  HttpGooglePubSubDAO,
  HttpGoogleStorageDAO
}
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchException, WorkbenchSubject}
import org.broadinstitute.dsde.workbench.sam.api.{SamRoutes, StandardUserInfoDirectives}
import org.broadinstitute.dsde.workbench.sam.config.{AppConfig, DirectoryConfig, GoogleConfig}
import org.broadinstitute.dsde.workbench.sam.directory._
import org.broadinstitute.dsde.workbench.sam.google.{GoogleExtensionRoutes, GoogleExtensions, GoogleKeyCache}
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam._
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO
import org.broadinstitute.dsde.workbench.sam.service._
import org.broadinstitute.dsde.workbench.util.{DelegatePool, ExecutionContexts}
import org.ehcache.Cache
import org.ehcache.config.builders.{CacheConfigurationBuilder, CacheManagerBuilder, ExpiryPolicyBuilder, ResourcePoolsBuilder}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

object Boot extends IOApp with LazyLogging {

  def run(args: List[String]): IO[ExitCode] =
    startup() *> ExitCode.Success.pure[IO]

  private def startup(): IO[Unit] = {

    // we need an ActorSystem to host our application in
    implicit val system = ActorSystem("sam")
    implicit val materializer = ActorMaterializer()

    val config = ConfigFactory.load()
    val appConfig = AppConfig.readConfig(config)

    val schemaDAO = new JndiSchemaDAO(appConfig.directoryConfig, appConfig.schemaLockConfig)

    val appDependencies = createAppDependencies(appConfig)

    appDependencies.use { dependencies =>
      for {
        _ <- IO.fromFuture(IO(schemaDAO.init())).onError {
          case e: WorkbenchException =>
            IO(logger.error("FATAL - could not update schema to latest version. Is the schema lock stuck?")) *> IO
              .raiseError(e)
          case t: Throwable =>
            IO(logger.error("FATAL - could not init ldap schema", t)) *> IO.raiseError(t)
        }

        _ <- dependencies.samApplication.resourceService.initResourceTypes().onError {
          case t: Throwable => IO(logger.error("FATAL - failure starting http server", t)) *> IO.raiseError(t)
        }

        _ <- dependencies.policyEvaluatorService.initPolicy()

        _ <- dependencies.cloudExtensions.onBoot(dependencies.samApplication)

        binding <- IO.fromFuture(IO(Http().bindAndHandle(dependencies.samRoutes.route, "0.0.0.0", 8080))).onError {
          case t: Throwable => IO(logger.error("FATAL - failure starting http server", t)) *> IO.raiseError(t)
        }
        _ <- IO.fromFuture(IO(binding.whenTerminated))
        _ <- IO(system.terminate())
      } yield ()
    }
  }

  private[sam] def createLdapConnectionPool(directoryConfig: DirectoryConfig): cats.effect.Resource[IO, LDAPConnectionPool] = {
    val dirURI = new URI(directoryConfig.directoryUrl)
    val (socketFactory, defaultPort) = dirURI.getScheme.toLowerCase match {
      case "ldaps" => (SSLContext.getDefault.getSocketFactory, 636)
      case "ldap" => (SocketFactory.getDefault, 389)
      case unsupported => throw new WorkbenchException(s"unsupported directory url scheme: $unsupported")
    }
    val port = if (dirURI.getPort > 0) dirURI.getPort else defaultPort
    cats.effect.Resource.make(
      IO(
        new LDAPConnectionPool(
          new LDAPConnection(socketFactory, dirURI.getHost, port, directoryConfig.user, directoryConfig.password),
          directoryConfig.connectionPoolSize
        )))(ldapConnection => IO(ldapConnection.close()))
  }

  private[sam] def createMemberOfCache(cacheName: String, maxEntries: Long, timeToLive: java.time.Duration): cats.effect.Resource[IO, Cache[WorkbenchSubject, Set[String]]] = {
    val cacheManager = CacheManagerBuilder.newCacheManagerBuilder
      .withCache(
        cacheName,
        CacheConfigurationBuilder
          .newCacheConfigurationBuilder(classOf[WorkbenchSubject], classOf[Set[String]], ResourcePoolsBuilder.heap(maxEntries))
          .withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(timeToLive))
      )
      .build

    cats.effect.Resource.make {
      IO {
        cacheManager.init()
        cacheManager.getCache(cacheName, classOf[WorkbenchSubject], classOf[Set[String]])
      }
    } { _ =>
      IO(cacheManager.close())
    }
  }

  private[sam] def createResourceCache(cacheName: String, maxEntries: Long, timeToLive: java.time.Duration): cats.effect.Resource[IO, Cache[FullyQualifiedResourceId, Resource]] = {
    val cacheManager = CacheManagerBuilder.newCacheManagerBuilder
      .withCache(
        cacheName,
        CacheConfigurationBuilder
          .newCacheConfigurationBuilder(classOf[FullyQualifiedResourceId], classOf[Resource], ResourcePoolsBuilder.heap(maxEntries))
          .withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(timeToLive))
      )
      .build

    cats.effect.Resource.make {
      IO {
        cacheManager.init()
        cacheManager.getCache(cacheName, classOf[FullyQualifiedResourceId], classOf[Resource])
      }
    } { _ =>
      IO(cacheManager.close())
    }
  }

  private[sam] def createAppDependencies(
      appConfig: AppConfig)(implicit actorSystem: ActorSystem, actorMaterializer: ActorMaterializer): cats.effect.Resource[IO, AppDependencies] =
    for {
      ldapConnectionPool <- createLdapConnectionPool(appConfig.directoryConfig)
      memberOfCache <- createMemberOfCache("memberof", appConfig.directoryConfig.memberOfCache.maxEntries, appConfig.directoryConfig.memberOfCache.timeToLive)
      resourceCache <- createResourceCache("resource", appConfig.directoryConfig.resourceCache.maxEntries, appConfig.directoryConfig.resourceCache.timeToLive)
      blockingEc <- ExecutionContexts.cachedThreadPool[IO]
      accessPolicyDao = new LdapAccessPolicyDAO(ldapConnectionPool, appConfig.directoryConfig, blockingEc, memberOfCache, resourceCache)
      directoryDAO = new LdapDirectoryDAO(ldapConnectionPool, appConfig.directoryConfig, blockingEc, memberOfCache)
      appDependencies <- appConfig.googleConfig match {
        case Some(config) =>
          for {
            googleFire <- GoogleFirestoreInterpreter.firestore[IO](
              config.googleServicesConfig.serviceAccountCredentialJson.firestoreServiceAccountJsonPath.asString)
            googleStorage <- GoogleStorageInterpreter.storage[IO](
              config.googleServicesConfig.serviceAccountCredentialJson.defaultServiceAccountJsonPath.asString)
          } yield {
            val ioFireStore = GoogleFirestoreInterpreter[IO](googleFire)
            // googleServicesConfig.resourceNamePrefix is an environment specific variable passed in https://github.com/broadinstitute/firecloud-develop/blob/fade9286ff0aec8449121ed201ebc44c8a4d57dd/run-context/fiab/configs/sam/docker-compose.yaml.ctmpl#L24
            // Use resourceNamePrefix to avoid collision between different fiab environments (we share same firestore for fiabs)
            val lock =
              DistributedLock[IO](s"sam-${config.googleServicesConfig.resourceNamePrefix.getOrElse("local")}", appConfig.distributedLockConfig, ioFireStore)
            val newGoogleStorage = GoogleStorageInterpreter[IO](googleStorage, blockingEc)
            val resourceTypeMap = appConfig.resourceTypes.map(rt => rt.name -> rt).toMap
            val cloudExtension = createGoogleCloudExt(accessPolicyDao, directoryDAO, config, resourceTypeMap, lock, newGoogleStorage)
            createAppDepenciesWithSamRoutes(appConfig, cloudExtension, accessPolicyDao, directoryDAO)
          }
        case None => cats.effect.Resource.pure[IO, AppDependencies](createAppDepenciesWithSamRoutes(appConfig, NoExtensions, accessPolicyDao, directoryDAO))
      }
    } yield appDependencies

  private[sam] def createGoogleCloudExt(
      accessPolicyDAO: AccessPolicyDAO,
      directoryDAO: DirectoryDAO,
      config: GoogleConfig,
      resourceTypeMap: Map[ResourceTypeName, ResourceType],
      distributedLock: DistributedLock[IO],
      googleStorageNew: GoogleStorageService[IO])(implicit actorSystem: ActorSystem): GoogleExtensions = {
    val googleDirDaos = (config.googleServicesConfig.adminSdkServiceAccounts match {
      case None =>
        NonEmptyList.one(
          Pem(
            WorkbenchEmail(config.googleServicesConfig.serviceAccountClientId),
            new File(config.googleServicesConfig.pemFile),
            Option(config.googleServicesConfig.subEmail)
          ))
      case Some(accounts) => accounts.map(account => Json(account.json, Option(config.googleServicesConfig.subEmail)))
    }).map { credentials =>
      new HttpGoogleDirectoryDAO(config.googleServicesConfig.appName, credentials, "google")
    }

    val googleDirectoryDAO = DelegatePool[GoogleDirectoryDAO](googleDirDaos)
    val googleIamDAO = new HttpGoogleIamDAO(
      config.googleServicesConfig.appName,
      Pem(WorkbenchEmail(config.googleServicesConfig.serviceAccountClientId), new File(config.googleServicesConfig.pemFile)),
      "google"
    )
    val googlePubSubDAO = new HttpGooglePubSubDAO(
      config.googleServicesConfig.appName,
      Pem(WorkbenchEmail(config.googleServicesConfig.serviceAccountClientId), new File(config.googleServicesConfig.pemFile)),
      "google",
      config.googleServicesConfig.groupSyncPubSubProject
    )
    val googleStorageDAO = new HttpGoogleStorageDAO(
      config.googleServicesConfig.appName,
      Pem(WorkbenchEmail(config.googleServicesConfig.serviceAccountClientId), new File(config.googleServicesConfig.pemFile)),
      "google"
    )
    val googleProjectDAO = new HttpGoogleProjectDAO(
      config.googleServicesConfig.appName,
      Pem(
        config.googleServicesConfig.billingPemEmail,
        new File(config.googleServicesConfig.pathToBillingPem),
        Option(config.googleServicesConfig.billingEmail)),
      "google"
    )
    val googleKeyCache =
      new GoogleKeyCache(distributedLock, googleIamDAO, googleStorageDAO, googleStorageNew, googlePubSubDAO, config.googleServicesConfig, config.petServiceAccountConfig)
    val notificationDAO = new PubSubNotificationDAO(googlePubSubDAO, config.googleServicesConfig.notificationTopic)

    new GoogleExtensions(
      distributedLock,
      directoryDAO,
      accessPolicyDAO,
      googleDirectoryDAO,
      googlePubSubDAO,
      googleIamDAO,
      googleStorageDAO,
      googleProjectDAO,
      googleKeyCache,
      notificationDAO,
      config.googleServicesConfig,
      config.petServiceAccountConfig,
      resourceTypeMap
    )
  }

  private[sam] def createAppDepenciesWithSamRoutes(
      config: AppConfig,
      cloudExtensions: CloudExtensions,
      accessPolicyDAO: AccessPolicyDAO,
      directoryDAO: DirectoryDAO)(implicit actorSystem: ActorSystem, actorMaterializer: ActorMaterializer): AppDependencies = {
    val resourceTypeMap = config.resourceTypes.map(rt => rt.name -> rt).toMap
    val policyEvaluatorService = PolicyEvaluatorService(config.emailDomain, resourceTypeMap, accessPolicyDAO)
    val resourceService = new ResourceService(resourceTypeMap, policyEvaluatorService, accessPolicyDAO, directoryDAO, cloudExtensions, config.emailDomain)
    val userService = new UserService(directoryDAO, cloudExtensions)
    val statusService = new StatusService(directoryDAO, cloudExtensions, 10 seconds)
    val managedGroupService =
      new ManagedGroupService(resourceService, policyEvaluatorService, resourceTypeMap, accessPolicyDAO, directoryDAO, cloudExtensions, config.emailDomain)
    val samApplication = SamApplication(userService, resourceService, statusService)

    cloudExtensions match {
      case googleExt: GoogleExtensions =>
        val routes = new SamRoutes(resourceService, userService, statusService, managedGroupService, config.swaggerConfig, directoryDAO, policyEvaluatorService)
        with StandardUserInfoDirectives with GoogleExtensionRoutes {
          val googleExtensions = googleExt
          val cloudExtensions = googleExt
        }
        AppDependencies(routes, samApplication, googleExt, directoryDAO, accessPolicyDAO, policyEvaluatorService)
      case _ =>
        val routes = new SamRoutes(resourceService, userService, statusService, managedGroupService, config.swaggerConfig, directoryDAO, policyEvaluatorService)
        with StandardUserInfoDirectives with NoExtensionRoutes
        AppDependencies(routes, samApplication, NoExtensions, directoryDAO, accessPolicyDAO, policyEvaluatorService)
    }
  }
}

final case class AppDependencies(
    samRoutes: SamRoutes,
    samApplication: SamApplication,
    cloudExtensions: CloudExtensions,
    directoryDAO: DirectoryDAO,
    accessPolicyDAO: AccessPolicyDAO,
    policyEvaluatorService: PolicyEvaluatorService)
