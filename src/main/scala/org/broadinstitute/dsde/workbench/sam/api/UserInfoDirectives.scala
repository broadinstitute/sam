package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.server.Directive1
import org.broadinstitute.dsde.workbench.sam.model.UserInfo

import scala.concurrent.ExecutionContext

/**
 * Directives to get user information.
 */
trait UserInfoDirectives {
  def requireUserInfo: Directive1[UserInfo]
}
