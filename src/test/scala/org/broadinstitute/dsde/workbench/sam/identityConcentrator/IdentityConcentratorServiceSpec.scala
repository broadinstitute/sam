package org.broadinstitute.dsde.workbench.sam.identityConcentrator
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.{GoogleSubjectId, WorkbenchEmail}
import org.scalatest.{FlatSpec, Matchers}
import IdentityConcentratorModel._
import pdi.jwt.JwtCirce

class IdentityConcentratorServiceSpec extends FlatSpec with Matchers {
  class TestICApi(userInfo: UserInfo) extends IdentityConcentratorApi {
    override def getUserInfo(accessToken: OAuth2BearerToken): IO[UserInfo] = {
      IO.pure(userInfo)
    }
  }

  "getGoogleIdentities" should "return google LinkedIdentities" in {
    val googleIdentities = Seq(
      (GoogleSubjectId("239845729308745239"), WorkbenchEmail("foo@gmail.com")),
      (GoogleSubjectId("239845729308745223"), WorkbenchEmail("bar@gmail.com"))
    )

    val claims = googleIdentities.map { case (googleSubjectId, email) =>
      JwtCirce.encode(s"""{"ga4gh_visa_v1":{"type": "LinkedIdentities","value": "${urlEncode(googleSubjectId.value)},https%3A%2F%2Faccounts.google.com;${urlEncode(email.value)},https%3A%2F%2Faccounts.google.com"}}""")
    }

    val service = new IdentityConcentratorService(new TestICApi(UserInfo(Some(claims))))

    service.getGoogleIdentities(OAuth2BearerToken("foo")).unsafeRunSync() should contain theSameElementsAs googleIdentities
  }

  it should "ignore other LinkedIdentities" in {
    val identities = Seq(
      (GoogleSubjectId("239845729308745239"), WorkbenchEmail("foo@gmail.com")),
      (GoogleSubjectId("239845729308745223"), WorkbenchEmail("bar@gmail.com"))
    )

    val claims = identities.map { case (googleSubjectId, email) =>
      JwtCirce.encode(s"""{"ga4gh_visa_v1":{"type": "LinkedIdentities","value": "${urlEncode(googleSubjectId.value)},https%3A%2F%2Faccounts.foo.com;${urlEncode(email.value)},https%3A%2F%2Faccounts.foo.com"}}""")
    }

    val service = new IdentityConcentratorService(new TestICApi(UserInfo(Some(claims))))

    service.getGoogleIdentities(OAuth2BearerToken("foo")).unsafeRunSync() should be(empty)
  }

  it should "ignore other types of claims" in {
    val claims = Seq(JwtCirce.encode(s"""{"ga4gh_visa_v1":{"type": "ControlledAccessGrants","value": "something"}}"""))

    val service = new IdentityConcentratorService(new TestICApi(UserInfo(Some(claims))))

    service.getGoogleIdentities(OAuth2BearerToken("foo")).unsafeRunSync() should be(empty)
  }

  it should "tolerate no claims" in {
    val service = new IdentityConcentratorService(new TestICApi(UserInfo(None)))
    service.getGoogleIdentities(OAuth2BearerToken("foo")).unsafeRunSync() should be(empty)
  }

  it should "tolerate empty claims" in {
    val service = new IdentityConcentratorService(new TestICApi(UserInfo(Some(Seq.empty))))
    service.getGoogleIdentities(OAuth2BearerToken("foo")).unsafeRunSync() should be(empty)
  }

  it should "tolerate invalid claim jwt" in {
    val service = new IdentityConcentratorService(new TestICApi(UserInfo(Some(Seq("not a jwt")))))
    service.getGoogleIdentities(OAuth2BearerToken("foo")).unsafeRunSync() should be(empty)
  }

  it should "tolerate invalid visa format" in {
    val jwt = JwtCirce.encode(s"""{"ga4gh_visa_v1":{}}""")
    val service = new IdentityConcentratorService(new TestICApi(UserInfo(Some(Seq(jwt)))))
    service.getGoogleIdentities(OAuth2BearerToken("foo")).unsafeRunSync() should be(empty)
  }

  "getLinkedAccountsFromVisaJwt" should "fail for invalid claim jwt" in {
    val service = new IdentityConcentratorService(new TestICApi(UserInfo(None)))

    service.getLinkedAccountsFromVisaJwt("not a jwt") match {
      case Left(errorReport) => errorReport.message shouldEqual "jwt not parsable"
      case Right(_) => fail("expected jwt not parsable error")
    }
  }

  it should "fail for invalid visa format" in {
    val jwt = JwtCirce.encode(s"""{"ga4gh_visa_v1":{}}""")

    val service = new IdentityConcentratorService(new TestICApi(UserInfo(None)))
    service.getLinkedAccountsFromVisaJwt(jwt) match {
      case Left(errorReport) => errorReport.message shouldEqual "visa not parsable"
      case Right(_) => fail("expected visa not parsable error")
    }
  }

  private def urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
}

