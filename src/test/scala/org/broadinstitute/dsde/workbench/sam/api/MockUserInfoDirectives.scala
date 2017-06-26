package org.broadinstitute.dsde.workbench.sam.api
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives.provide
import org.broadinstitute.dsde.workbench.sam.model.UserInfo

/**
  * Created by dvoet on 6/7/17.
  */
trait MockUserInfoDirectives extends UserInfoDirectives {
  val userInfo: UserInfo
  override def requireUserInfo: Directive1[UserInfo] = provide(userInfo)
}
