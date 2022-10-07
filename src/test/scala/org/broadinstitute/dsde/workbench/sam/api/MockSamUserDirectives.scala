package org.broadinstitute.dsde.workbench.sam
package api

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives._
import cats.effect.unsafe.implicits.global
import org.broadinstitute.dsde.workbench.sam.model.SamUser
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

/** Created by dvoet on 6/7/17.
  */
trait MockSamUserDirectives extends SamUserDirectives {
  val user: SamUser
  val newSamUser: Option[SamUser] = None

  private lazy val fakeOidcHeaders =
    OIDCHeaders(OAuth2BearerToken("dummy token"), user.googleSubjectId.toLeft(user.azureB2CId.get), user.email, user.googleSubjectId)

  override def withActiveUser(samRequestContext: SamRequestContext): Directive1[SamUser] = onSuccess {
    StandardSamUserDirectives.getActiveSamUser(fakeOidcHeaders, directoryDAO, tosService, samRequestContext).unsafeToFuture()
  }

  override def withUserAllowInactive(samRequestContext: SamRequestContext): Directive1[SamUser] = onSuccess {
    StandardSamUserDirectives.getSamUser(fakeOidcHeaders, directoryDAO, samRequestContext).unsafeToFuture()
  }

  override def withNewUser(samRequestContext: SamRequestContext): Directive1[SamUser] = newSamUser match {
    case None => failWith(new Exception("samUser not specified"))
    case Some(u) => provide(u)
  }
}
