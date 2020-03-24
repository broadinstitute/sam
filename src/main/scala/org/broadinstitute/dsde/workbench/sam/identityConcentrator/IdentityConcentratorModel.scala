package org.broadinstitute.dsde.workbench.sam.identityConcentrator
import cats.effect.IO
import cats.implicits._
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder
import org.http4s.circe.jsonOf
import org.http4s.{EntityDecoder, Uri}

object IdentityConcentratorModel {
  implicit val uriDecoder: Decoder[Uri] = Decoder.decodeString.emap { string =>
    Uri.fromString(string).toTry.toEither.leftMap(_.getMessage)
  }

  implicit val openIdConfigurationDecoder: Decoder[OpenIdConfiguration] = deriveDecoder[OpenIdConfiguration]
  implicit val userInfoDecoder: Decoder[UserInfo] = deriveDecoder[UserInfo]
  implicit val visaDecoder: Decoder[Visa] = deriveDecoder[Visa]
  implicit val visaEnvelopeDecoder: Decoder[VisaEnvelope] = deriveDecoder[VisaEnvelope]

  implicit val openIdConfigurationEntityDecoder: EntityDecoder[IO, OpenIdConfiguration] = jsonOf[IO, OpenIdConfiguration]
  implicit val userInfoEntityDecoder: EntityDecoder[IO, UserInfo] = jsonOf[IO, UserInfo]

  // see https://openid.net/specs/openid-connect-discovery-1_0.html#ProviderMetadata
  final case class OpenIdConfiguration(userinfo_endpoint: Uri)

  // see https://github.com/ga4gh-duri/ga4gh-duri.github.io/blob/master/researcher_ids/ga4gh_passport_v1.md#passport-claim
  final case class UserInfo(ga4gh_passport_v1: Option[Seq[String]])

  // see https://github.com/ga4gh-duri/ga4gh-duri.github.io/blob/master/researcher_ids/ga4gh_passport_v1.md#passport-visa-fields
  final case class Visa(`type`: String, value: String)

  // see https://github.com/ga4gh-duri/ga4gh-duri.github.io/blob/master/researcher_ids/ga4gh_passport_v1.md#passport-claim
  final case class VisaEnvelope(ga4gh_visa_v1: Visa)

  // see https://github.com/ga4gh-duri/ga4gh-duri.github.io/blob/master/researcher_ids/ga4gh_passport_v1.md#linkedidentities
  final case class LinkedIdentity(subject: String, issuer: String)
}
