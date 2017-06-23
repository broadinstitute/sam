package org.broadinstitute.dsde.workbench.sam.openam

import java.util.UUID

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory
import org.scalatest.{FlatSpec, Matchers}
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.sam.directory.DirectoryConfig
import org.broadinstitute.dsde.workbench.sam.model._

import scala.concurrent.{Await, Awaitable}
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Created by mbemis on 6/23/17.
  */
class HttpOpenAmDAOSpec extends FlatSpec with Matchers {
  implicit val system = ActorSystem("sam")
  implicit val materializer = ActorMaterializer()

  val openAmConfig = ConfigFactory.load().as[OpenAmConfig]("openam")
  val directoryConfig = ConfigFactory.load().as[DirectoryConfig]("directory")
  val resourceTypesConfig = ConfigFactory.load().as[Set[ResourceType]]("resourceTypes")
  val dao = new HttpOpenAmDAO(openAmConfig, directoryConfig)

  private def runAndWait[T](f: Awaitable[T]): T = Await.result(f, Duration.Inf)

  "HttpOpenAmDAO" should "get the admin user info and validate it" in {
    val adminUserInfo = runAndWait(dao.getAdminUserInfo)

    // Validate the admin user info by trying to perform an admin operation
    assert(resourceTypesConfig.map(_.name).subsetOf(runAndWait(dao.listResourceTypes(adminUserInfo)).map(_.name)))
  }

  it should "create a new resource type" in {
    val adminUserInfo = runAndWait(dao.getAdminUserInfo)

    val testResourceTypeName = s"testResourceType-${UUID.randomUUID().toString}"
    val resourceType = ResourceType(testResourceTypeName, Set("action1", "action2"), Set(ResourceRole("owner", Set(ResourceAction("action1"), ResourceAction("action2")))), "owner", None)
    runAndWait(dao.createResourceType(resourceType, s"$testResourceTypeName://*", adminUserInfo))

    assert(runAndWait(dao.listResourceTypes(adminUserInfo)).map(_.name).contains(testResourceTypeName))
  }
}
