package org.broadinstitute.dsde.workbench.sam.identityConcentrator

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import cats.effect.IO
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model.google.ServiceAccount
import org.broadinstitute.dsde.workbench.model.{ErrorReport, GoogleSubjectId, IdentityConcentratorId, WorkbenchEmail}
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.identityConcentrator.IdentityConcentratorModel._
import pdi.jwt.{JwtCirce, JwtOptions}

import scala.util.{Failure, Success}

class IdentityConcentratorService(identityConcentratorApi: IdentityConcentratorApi) extends LazyLogging {
  val googleIssuer = "https://accounts.google.com"

  def linkServiceAccount(accessToken: OAuth2BearerToken, serviceAccount: ServiceAccount): IO[Unit] = identityConcentratorApi.linkServiceAccount(accessToken, serviceAccount)
  def enableUser(userId: IdentityConcentratorId): IO[Unit] = identityConcentratorApi.enableUser(userId)
  def disableUser(userId: IdentityConcentratorId): IO[Unit] = identityConcentratorApi.disableUser(userId)

  def getGoogleIdentities(accessToken: OAuth2BearerToken): IO[Seq[(GoogleSubjectId, WorkbenchEmail)]] = {
    for {
      userInfo <- identityConcentratorApi.getUserInfo(accessToken)
      visaLinkedAccounts <- userInfo.ga4gh_passport_v1.getOrElse(Seq.empty).toList.traverse(getLinkedAccountsFromVisaJwtLogErrors)
    } yield {
      for {
        linkedAccounts <- visaLinkedAccounts if linkedAccounts.forall(_.issuer == googleIssuer)
        // there should be 2 entries for a google account, 1 for subject id and 1 for email
        // the only way to distinguish the 2 is one looks like an email address and the other is a big number
        // whichever contains '@' we treat as email and the other as subject id
        subjectIdEntry <- linkedAccounts.find(!_.subject.contains("@"))
        emailEntry <- linkedAccounts.find(_.subject.contains("@"))
      } yield {
        (GoogleSubjectId(subjectIdEntry.subject), WorkbenchEmail(emailEntry.subject))
      }
    }
  }

  private def getLinkedAccountsFromVisaJwtLogErrors(jwt: String): IO[Seq[LinkedAccount]] = {
    getLinkedAccountsFromVisaJwt(jwt) match {
      case Left(errorReport) =>
        logger.warn(ErrorReport.loggableString(errorReport))
        IO.pure(Seq.empty)

      case Right(linkedAccounts) => IO.pure(linkedAccounts)
    }
  }

  private[identityConcentrator] def getLinkedAccountsFromVisaJwt(jwt: String): Either[ErrorReport, Seq[LinkedAccount]] = {
    val linkedIdPattern = "([^,]+),([^,]+)".r

    // JWT from trusted source
    JwtCirce.decodeJson(jwt, JwtOptions(signature = false, expiration = false)) match {
      case Failure(t) => Left(ErrorReport("jwt not parsable", ErrorReport(t)))
      case Success(jwtJson) =>
        for {
          visaEnvelope <- jwtJson.as[VisaEnvelope].leftMap(t => ErrorReport("visa not parsable", ErrorReport(t)))
        } yield {
          if (visaEnvelope.ga4gh_visa_v1.`type` == "LinkedIdentities") {
            // see https://github.com/ga4gh-duri/ga4gh-duri.github.io/blob/master/researcher_ids/ga4gh_passport_v1.md#linkedidentities
            // The "value" field format is a semicolon-delimited list of "<uri-encoded-sub>,<uri-encoded-iss>" entries with no added whitespace between entries.
            // note that this ignores any entries that not formatted correctly
            visaEnvelope.ga4gh_visa_v1.value.split(";").collect {
              case linkedIdPattern(subject, issuer) => LinkedAccount(urlDecode(subject), urlDecode(issuer))
            }
          } else {
            // visa is not of type LinkedIdentities so return none
            Seq.empty
          }
        }
    }
  }

  private def urlDecode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.toString())
}

