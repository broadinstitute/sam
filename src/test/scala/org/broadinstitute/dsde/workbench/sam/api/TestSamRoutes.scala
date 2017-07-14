package org.broadinstitute.dsde.workbench.sam.api

import akka.actor.ActorSystem
import akka.stream.Materializer
import org.broadinstitute.dsde.workbench.sam.model.{ResourceType, ResourceTypeName, UserInfo}
import org.broadinstitute.dsde.workbench.sam.service.{ResourceService, UserService}

import scala.concurrent.ExecutionContext

/**
  * Created by dvoet on 7/14/17.
  */
class TestSamRoutes(val resourceTypes: Map[ResourceTypeName, ResourceType], resourceService: ResourceService, userService: UserService, val userInfo: UserInfo)(implicit override val system: ActorSystem, override val materializer: Materializer, override val executionContext: ExecutionContext)
  extends SamRoutes(resourceService, userService) with MockUserInfoDirectives

