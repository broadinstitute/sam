package org.broadinstitute.dsde.workbench.sam.redRing

import akka.actor.ActorSystem
import org.broadinstitute.dsde.workbench.google.GoogleCredentialModes.GoogleCredentialMode
import org.broadinstitute.dsde.workbench.google.{GooglePubSubDAO, HttpGooglePubSubDAO}
import org.broadinstitute.dsde.workbench.sam.config.{GoogleConfig, GooglePubSubConfig}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.google.GoogleCredentialModes.Pem
import org.scalatest.flatspec.AnyFlatSpec

import java.io.File
import scala.concurrent.ExecutionContext

class GooglePubsubSpec extends AnyFlatSpec {
  implicit val system = ActorSystem("sam")
  implicit val executionContext = ExecutionContext.global
  val workspaceMetricBaseName = "google"
  val config: GoogleConfig = ???
  val pubSubconfig : GooglePubSubConfig = ???
  val dao : GooglePubSubDAO = new HttpGooglePubSubDAO(
    config.googleServicesConfig.appName,
    Pem(WorkbenchEmail(config.googleServicesConfig.serviceAccountClientId), new File(config.googleServicesConfig.pemFile)),
    workspaceMetricBaseName,
    config.googleServicesConfig.groupSyncPubSubConfig.project
  )

  //publish a message successfully to pubsub
  "PubSubDao" should "successfully publish messages to Google" {
    //arrange
    // create test specific topic
    val topic: String = "test_successfullyPublish"

    //act
    dao.publishMessages(topic, Seq());
    //assert
    //TODO: write assertion
    //dao.pullMessages()

  }

}
// Find all the google calls within Sam.user.createGroup(managedGroupId)(user1AuthToken)

// create a pubsub dao with real configs (could be a testing version)


// Test this
// googleGroupSyncPubSubDAO.publishMessages(googleServicesConfig.groupSyncPubSubConfig.topic, Seq(MessageRequest(id.toJson.compactPrint)))