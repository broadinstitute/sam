package org.broadinstitute.dsde.workbench.sam.google

import akka.actor.ActorSystem
import cats.effect.IO
import cats.effect.unsafe.{IORuntime, IORuntimeBuilder}
import com.google.api.gax.core.{FixedCredentialsProvider, InstantiatingExecutorProvider}
import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.cloud.pubsub.v1.{MessageReceiver, Subscriber}
import com.google.pubsub.v1.ProjectSubscriptionName
import org.broadinstitute.dsde.workbench.google.GooglePubSubDAO
import org.broadinstitute.dsde.workbench.sam.config.{GooglePubSubConfig, ServiceAccountCredentialJson}

import java.io.FileInputStream
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

class GooglePubSubMonitor(
    pubSubDao: GooglePubSubDAO,
    config: GooglePubSubConfig,
    pathToSACreds: ServiceAccountCredentialJson,
    messageReceiver: MessageReceiver
) {
  private val subscriber = Subscriber
    .newBuilder(
      ProjectSubscriptionName.newBuilder().setProject(config.project).setSubscription(config.subscription).build(),
      messageReceiver
    )
    .setCredentialsProvider(
      FixedCredentialsProvider.create(ServiceAccountCredentials.fromStream(new FileInputStream(pathToSACreds.defaultServiceAccountJsonPath.asString)))
    )
    .setExecutorProvider(InstantiatingExecutorProvider.newBuilder.setExecutorThreadCount(config.workerCount).build)
    .build()

  def startAndRegisterTermination()(implicit system: ActorSystem): IO[Unit] =
    init.map { _ =>
      subscriber.startAsync()
      system.registerOnTermination(terminate())
    }

  def terminate(): Unit =
    subscriber.stopAsync().awaitTerminated()

  /** Base implementation creates the topic and subscription, override to init other things
    * @return
    */
  protected def init: IO[Unit] = for {
    _ <- IO.fromFuture(IO(pubSubDao.createTopic(config.topic)))
    _ <- IO.fromFuture(IO(pubSubDao.createSubscription(config.topic, config.subscription)))
  } yield ()
}

object GooglePubSubMonitor {
  def createReceiverIORuntime(config: GooglePubSubConfig)(implicit system: ActorSystem): IO[IORuntime] =
    for {
      executor <- IO(ExecutionContext.fromExecutor(Executors.newFixedThreadPool(config.workerCount)))
      ioRuntime = IORuntimeBuilder().setCompute(executor, () => ()).build()
    } yield {
      system.registerOnTermination(ioRuntime.shutdown())
      ioRuntime
    }
}
