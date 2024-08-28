package org.broadinstitute.dsde.workbench.sam.google

import akka.actor.ActorSystem
import akka.testkit.TestKit
import cats.effect.IO
import com.google.auth.oauth2.ServiceAccountCredentials
import fs2.Stream
import org.broadinstitute.dsde.workbench.RetryConfig
import org.broadinstitute.dsde.workbench.dataaccess.NotificationDAO
import org.broadinstitute.dsde.workbench.google.{GoogleDirectoryDAO, GoogleIamDAO, GoogleKmsService, GoogleProjectDAO, GooglePubSubDAO, GoogleStorageDAO}
import org.broadinstitute.dsde.workbench.google2.{GcsBlobName, GoogleStorageService}
import org.broadinstitute.dsde.workbench.model.TraceId
import org.broadinstitute.dsde.workbench.model.google.GcsBucketName
import org.broadinstitute.dsde.workbench.sam.Generator.{
  genFirecloudEmail,
  genGcsBlobName,
  genGcsBucketName,
  genGoogleProject,
  genNonPetEmail,
  genOAuth2BearerToken,
  genPetServiceAccount,
  genWorkbenchUserGoogle,
  genWorkbenchUserId
}
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.dataAccess.{AccessPolicyDAO, DirectoryDAO, PostgresDistributedLockDAO}
import org.broadinstitute.dsde.workbench.sam.mock.RealKeyMockGoogleIamDAO
import org.broadinstitute.dsde.workbench.sam.model.api.SamUser
import org.broadinstitute.dsde.workbench.sam.model.{ResourceType, ResourceTypeName}
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentMatchersSugar._
import org.mockito.IdiomaticMockito
import org.mockito.Mockito.{RETURNS_SMART_NULLS, clearInvocations, doReturn, verifyNoInteractions}
import org.mockito.MockitoSugar.verify
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Inside, OptionValues}

import java.net.URL
import java.util.concurrent.TimeUnit
import scala.concurrent.{ExecutionContextExecutor, Future}

class NewGoogleExtensionsSpec(_system: ActorSystem)
    extends TestKit(_system)
    with AnyFreeSpecLike
    with Matchers
    with TestSupport
    with IdiomaticMockito
    with ScalaFutures
    with OptionValues
    with Inside {

  def this() = this(ActorSystem("NewGoogleExtensionSpec"))

  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  "GoogleExtensions: Signed URLs" - {
    val newGoogleUser = genWorkbenchUserGoogle.sample.get
    val googleProject = genGoogleProject.sample.get
    val gcsBucket = genGcsBucketName.sample.get
    val gcsBlob = genGcsBlobName.sample.get
    val gsPath = s"gs://${gcsBucket.value}/${gcsBlob.value}"
    val petServiceAccount = genPetServiceAccount.sample.get
    val petServiceAccountKey = RealKeyMockGoogleIamDAO.generateNewRealKey(petServiceAccount.serviceAccount.email)._2
    val expectedUrl = new URL("https", "localhost", 80, s"${gcsBucket.value}/${gcsBlob.value}")

    val mockGoogleKeyCache = mock[GoogleKeyCache](RETURNS_SMART_NULLS)
    val mockGoogleStorageService = mock[GoogleStorageService[IO]](RETURNS_SMART_NULLS)

    lazy val petServiceAccountConfig = TestSupport.petServiceAccountConfig
    lazy val googleServicesConfig = TestSupport.googleServicesConfig

    val googleExtensions: GoogleExtensions = spy(
      new GoogleExtensions(
        mock[PostgresDistributedLockDAO[IO]](RETURNS_SMART_NULLS),
        mock[DirectoryDAO](RETURNS_SMART_NULLS),
        mock[AccessPolicyDAO](RETURNS_SMART_NULLS),
        mock[GoogleDirectoryDAO](RETURNS_SMART_NULLS),
        mock[GooglePubSubDAO](RETURNS_SMART_NULLS),
        mock[GooglePubSubDAO](RETURNS_SMART_NULLS),
        mock[GooglePubSubDAO](RETURNS_SMART_NULLS),
        mock[GoogleIamDAO](RETURNS_SMART_NULLS),
        mock[GoogleStorageDAO](RETURNS_SMART_NULLS),
        mock[GoogleProjectDAO](RETURNS_SMART_NULLS),
        mockGoogleKeyCache,
        mock[NotificationDAO](RETURNS_SMART_NULLS),
        mock[GoogleKmsService[IO]](RETURNS_SMART_NULLS),
        mockGoogleStorageService,
        googleServicesConfig,
        petServiceAccountConfig,
        Map.empty[ResourceTypeName, ResourceType],
        genFirecloudEmail.sample.get
      )
    )

    doReturn(IO.pure(petServiceAccount))
      .when(googleExtensions)
      .createUserPetServiceAccount(eqTo(newGoogleUser), eqTo(googleProject), any[SamRequestContext])

    doReturn(IO.pure(petServiceAccountKey))
      .when(mockGoogleKeyCache)
      .getKey(eqTo(petServiceAccount))

    doReturn(Stream.emit(expectedUrl))
      .when(mockGoogleStorageService)
      .getSignedBlobUrl(
        any[GcsBucketName],
        any[GcsBlobName],
        any[ServiceAccountCredentials],
        any[Option[TraceId]],
        any[RetryConfig],
        any[Long],
        any[TimeUnit],
        any[Map[String, String]]
      )
    "getSignedUrl" - {
      "generates a signed URL for a GCS Object" in {
        val signedUrl =
          runAndWait(googleExtensions.getSignedUrl(newGoogleUser, googleProject, gcsBucket, gcsBlob, None, requesterPays = true, samRequestContext))

        signedUrl should be(expectedUrl)

        verify(googleExtensions).createUserPetServiceAccount(eqTo(newGoogleUser), eqTo(googleProject), any[SamRequestContext])

        verify(mockGoogleKeyCache).getKey(eqTo(petServiceAccount))

        verify(mockGoogleStorageService).getSignedBlobUrl(
          eqTo(gcsBucket),
          eqTo(gcsBlob),
          argThat((creds: ServiceAccountCredentials) => creds.getClientEmail.equals(petServiceAccount.serviceAccount.email.value)),
          any[Option[TraceId]],
          any[RetryConfig],
          eqTo(60L),
          eqTo(TimeUnit.MINUTES),
          eqTo(Map("userProject" -> googleProject.value, "requestedBy" -> newGoogleUser.email.value))
        )
      }

      "customizes link duration" in {
        runAndWait(googleExtensions.getSignedUrl(newGoogleUser, googleProject, gcsBucket, gcsBlob, Some(5L), requesterPays = true, samRequestContext))
        verify(mockGoogleStorageService).getSignedBlobUrl(
          eqTo(gcsBucket),
          eqTo(gcsBlob),
          argThat((creds: ServiceAccountCredentials) => creds.getClientEmail.equals(petServiceAccount.serviceAccount.email.value)),
          any[Option[TraceId]],
          any[RetryConfig],
          eqTo(5L),
          eqTo(TimeUnit.MINUTES),
          eqTo(Map("userProject" -> googleProject.value, "requestedBy" -> newGoogleUser.email.value))
        )
      }

      "does not include requester pays user project if told to skip it" in {
        runAndWait(googleExtensions.getSignedUrl(newGoogleUser, googleProject, gcsBucket, gcsBlob, None, requesterPays = false, samRequestContext))
        verify(mockGoogleStorageService).getSignedBlobUrl(
          eqTo(gcsBucket),
          eqTo(gcsBlob),
          argThat((creds: ServiceAccountCredentials) => creds.getClientEmail.equals(petServiceAccount.serviceAccount.email.value)),
          any[Option[TraceId]],
          any[RetryConfig],
          eqTo(60L),
          eqTo(TimeUnit.MINUTES),
          eqTo(Map("requestedBy" -> newGoogleUser.email.value))
        )
      }
    }
    "getRequesterPaysSignedUrl" - {
      val requesterPaysGoogleProject = genGoogleProject.sample.get
      val arbitraryPetServiceAccount = genPetServiceAccount.sample.get
      val arbitraryPetServiceAccountKey = RealKeyMockGoogleIamDAO.generateNewRealKey(arbitraryPetServiceAccount.serviceAccount.email)._2

      doReturn(IO.pure(arbitraryPetServiceAccountKey))
        .when(googleExtensions)
        .getArbitraryPetServiceAccountKey(eqTo(newGoogleUser), any[SamRequestContext])

      "includes the requester pays user project if provided" in {
        runAndWait(
          googleExtensions.getRequesterPaysSignedUrl(
            newGoogleUser,
            gsPath,
            None,
            requesterPaysProject = Some(requesterPaysGoogleProject),
            samRequestContext
          )
        )
        verify(mockGoogleStorageService).getSignedBlobUrl(
          eqTo(gcsBucket),
          eqTo(gcsBlob),
          argThat((creds: ServiceAccountCredentials) => creds.getClientEmail.equals(arbitraryPetServiceAccount.serviceAccount.email.value)),
          any[Option[TraceId]],
          any[RetryConfig],
          eqTo(60L),
          eqTo(TimeUnit.MINUTES),
          eqTo(Map("requestedBy" -> newGoogleUser.email.value, "userProject" -> requesterPaysGoogleProject.value))
        )
      }
      "does not include a requester pays user project if none is provided" in {
        runAndWait(googleExtensions.getRequesterPaysSignedUrl(newGoogleUser, gsPath, None, requesterPaysProject = None, samRequestContext))
        verify(mockGoogleStorageService).getSignedBlobUrl(
          eqTo(gcsBucket),
          eqTo(gcsBlob),
          argThat((creds: ServiceAccountCredentials) => creds.getClientEmail.equals(arbitraryPetServiceAccount.serviceAccount.email.value)),
          any[Option[TraceId]],
          any[RetryConfig],
          eqTo(60L),
          eqTo(TimeUnit.MINUTES),
          eqTo(Map("requestedBy" -> newGoogleUser.email.value))
        )
      }
    }
  }
  "GoogleExtensions: Pet Service Accounts" - {
    val subject = genWorkbenchUserId.sample.get
    val email = genNonPetEmail.sample.get
    val user = SamUser(subject, None, email, None, false)
    val googleProject = genGoogleProject.sample.get
    val scopes = Set("scope1", "scope2")
    val petServiceAccount = genPetServiceAccount.sample.get
    val expectedKey = RealKeyMockGoogleIamDAO.generateNewRealKey(petServiceAccount.serviceAccount.email)._2
    val expectedToken = genOAuth2BearerToken.sample.get.token

    val mockDirectoryDAO = mock[DirectoryDAO](RETURNS_SMART_NULLS)
    val mockGoogleKeyCache = mock[GoogleKeyCache](RETURNS_SMART_NULLS)

    val googleExtensions: GoogleExtensions = spy(
      new GoogleExtensions(
        mock[PostgresDistributedLockDAO[IO]](RETURNS_SMART_NULLS),
        mockDirectoryDAO,
        mock[AccessPolicyDAO](RETURNS_SMART_NULLS),
        mock[GoogleDirectoryDAO](RETURNS_SMART_NULLS),
        mock[GooglePubSubDAO](RETURNS_SMART_NULLS),
        mock[GooglePubSubDAO](RETURNS_SMART_NULLS),
        mock[GooglePubSubDAO](RETURNS_SMART_NULLS),
        mock[GoogleIamDAO](RETURNS_SMART_NULLS),
        mock[GoogleStorageDAO](RETURNS_SMART_NULLS),
        mock[GoogleProjectDAO](RETURNS_SMART_NULLS),
        mockGoogleKeyCache,
        mock[NotificationDAO](RETURNS_SMART_NULLS),
        mock[GoogleKmsService[IO]](RETURNS_SMART_NULLS),
        mock[GoogleStorageService[IO]](RETURNS_SMART_NULLS),
        TestSupport.googleServicesConfig,
        TestSupport.petServiceAccountConfig,
        Map.empty[ResourceTypeName, ResourceType],
        genFirecloudEmail.sample.get
      )
    )

    doReturn(IO.some(subject))
      .when(mockDirectoryDAO)
      .loadSubjectFromEmail(eqTo(email), any[SamRequestContext])

    doReturn(IO.pure(petServiceAccount))
      .when(googleExtensions)
      .createUserPetServiceAccount(eqTo(user), eqTo(googleProject), any[SamRequestContext])

    doReturn(Future.successful(expectedToken))
      .when(googleExtensions)
      .getAccessTokenUsingJson(eqTo(expectedKey), eqTo(scopes))

    doReturn(Future.successful(expectedKey))
      .when(googleExtensions)
      .getDefaultServiceAccountForShellProject(eqTo(user), any[SamRequestContext])

    doReturn(IO.pure(expectedKey))
      .when(mockGoogleKeyCache)
      .getKey(eqTo(petServiceAccount))

    "getPetServiceAccountKey" - {
      "gets a key for an email" in {
        clearInvocations(mockDirectoryDAO, googleExtensions, mockGoogleKeyCache)

        val key = runAndWait(googleExtensions.getPetServiceAccountKey(email, googleProject, samRequestContext))

        key should be(Some(expectedKey))

        verify(mockDirectoryDAO).loadSubjectFromEmail(eqTo(email), any[SamRequestContext])
        verify(googleExtensions).createUserPetServiceAccount(eqTo(user), eqTo(googleProject), any[SamRequestContext])
        verify(mockGoogleKeyCache).getKey(eqTo(petServiceAccount))
      }

      "gets a key for a SamUser" in {
        clearInvocations(mockDirectoryDAO, googleExtensions, mockGoogleKeyCache)

        val key = runAndWait(googleExtensions.getPetServiceAccountKey(user, googleProject, samRequestContext))

        key should be(expectedKey)

        verifyNoInteractions(mockDirectoryDAO)
        verify(googleExtensions).createUserPetServiceAccount(eqTo(user), eqTo(googleProject), any[SamRequestContext])
        verify(mockGoogleKeyCache).getKey(eqTo(petServiceAccount))
      }

      "getPetServiceAccountToken" - {
        "gets a token for an email" in {
          clearInvocations(mockDirectoryDAO, googleExtensions, mockGoogleKeyCache)

          val token = runAndWait(googleExtensions.getPetServiceAccountToken(email, googleProject, scopes, samRequestContext))

          token should be(Some(expectedToken))

          verify(mockDirectoryDAO).loadSubjectFromEmail(eqTo(email), any[SamRequestContext])
          verify(googleExtensions).createUserPetServiceAccount(eqTo(user), eqTo(googleProject), any[SamRequestContext])
          verify(mockGoogleKeyCache).getKey(eqTo(petServiceAccount))
          verify(googleExtensions).getAccessTokenUsingJson(eqTo(expectedKey), eqTo(scopes))

        }
        "gets a token for a SamUser" in {
          clearInvocations(mockDirectoryDAO, googleExtensions, mockGoogleKeyCache)

          val token = runAndWait(googleExtensions.getPetServiceAccountToken(user, googleProject, scopes, samRequestContext))

          token should be(expectedToken)

          verifyNoInteractions(mockDirectoryDAO)
          verify(googleExtensions).createUserPetServiceAccount(eqTo(user), eqTo(googleProject), any[SamRequestContext])
          verify(mockGoogleKeyCache).getKey(eqTo(petServiceAccount))
          verify(googleExtensions).getAccessTokenUsingJson(eqTo(expectedKey), eqTo(scopes))
        }
      }

      "getArbitraryPetServiceAccountKey" - {
        "gets a key for an email" in {
          clearInvocations(mockDirectoryDAO, googleExtensions, mockGoogleKeyCache)

          val key = runAndWait(googleExtensions.getArbitraryPetServiceAccountKey(email, samRequestContext))

          key should be(Some(expectedKey))

          verify(mockDirectoryDAO).loadSubjectFromEmail(eqTo(email), any[SamRequestContext])
          verifyNoInteractions(mockGoogleKeyCache)
          verify(googleExtensions).getDefaultServiceAccountForShellProject(eqTo(user), any[SamRequestContext])
        }

        "gets a key for a SamUser" in {
          clearInvocations(mockDirectoryDAO, googleExtensions, mockGoogleKeyCache)

          val key = runAndWait(googleExtensions.getArbitraryPetServiceAccountKey(user, samRequestContext))

          key should be(expectedKey)

          verifyNoInteractions(mockDirectoryDAO)
          verifyNoInteractions(mockGoogleKeyCache)
          verify(googleExtensions).getDefaultServiceAccountForShellProject(eqTo(user), any[SamRequestContext])
        }
      }

      "getArbitraryPetServiceAccountToken" - {
        "gets a token for an email" in {
          clearInvocations(mockDirectoryDAO, googleExtensions, mockGoogleKeyCache)

          val token = runAndWait(googleExtensions.getArbitraryPetServiceAccountToken(email, scopes, samRequestContext))

          token should be(Some(expectedToken))

          verify(mockDirectoryDAO).loadSubjectFromEmail(eqTo(email), any[SamRequestContext])
          verifyNoInteractions(mockGoogleKeyCache)
          verify(googleExtensions).getDefaultServiceAccountForShellProject(eqTo(user), any[SamRequestContext])
          verify(googleExtensions).getAccessTokenUsingJson(eqTo(expectedKey), eqTo(scopes))
        }

        "gets a token for a SamUser" in {
          clearInvocations(mockDirectoryDAO, googleExtensions, mockGoogleKeyCache)

          val token = runAndWait(googleExtensions.getArbitraryPetServiceAccountToken(user, scopes, samRequestContext))

          token should be(expectedToken)

          verifyNoInteractions(mockDirectoryDAO)
          verifyNoInteractions(mockGoogleKeyCache)
          verify(googleExtensions).getDefaultServiceAccountForShellProject(eqTo(user), any[SamRequestContext])
          verify(googleExtensions).getAccessTokenUsingJson(eqTo(expectedKey), eqTo(scopes))
        }
      }
    }
  }
}
