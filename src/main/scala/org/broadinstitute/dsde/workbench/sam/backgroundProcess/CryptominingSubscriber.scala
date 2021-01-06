package org.broadinstitute.dsde.workbench.sam
package backgroundProcess

import cats.effect.{ContextShift, IO}
import fs2.Stream
import io.chrisdavenport.log4cats.StructuredLogger
import io.circe.Decoder
import org.broadinstitute.dsde.workbench.google2.{Event, GoogleSubscriber}
import org.broadinstitute.dsde.workbench.model.WorkbenchUserId
import org.broadinstitute.dsde.workbench.sam.service.UserService
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

// Each instance of Sam will run a subscriber, since they all subscribe to the same topic via same subscription,
// messages will be distributed among subscribers instead of distributed to all of them, which is what we want.
class CryptominingSubscriber(subscriber: GoogleSubscriber[IO, CryptominingUserMessage],
                             userService: UserService
                            )(implicit logger: StructuredLogger[IO], cs: ContextShift[IO]) {
  val process: Stream[IO, Unit] = subscriber.messages
    .evalMap(messageHandler)
    .handleErrorWith(error => Stream.eval(logger.error(error)("Failed to initialize message processor")))

  private def messageHandler(event: Event[CryptominingUserMessage]): IO[Unit] = {
    for {
      res <- messageResponder(event.msg).attempt
      _ <- res match {
        case Left(e)  => logger.error(e)("Fail to process pubsub message")
        case Right(_) => IO.unit
      }
      // acking the message whether or not we successfully processed the message since this is not essential part of
      // terra functionality. That is, if we didn't disable a bad user, it's no big deal.
      // To conditionally acking message, we'll need to config a dead letter topic where messages reach max retry can go to,
      // which we're not doing for this use case.
    _ <- IO{event.consumer.ack()}
    } yield ()
  }

  private def messageResponder(
                                         message: CryptominingUserMessage
                                       ): IO[Unit] = IO.fromFuture(IO(userService.disableUser(message.userSubjectId, SamRequestContext(None)))).void
}

object CryptominingSubscriber {
  implicit val workbenchUserIdMessageDecoder: Decoder[WorkbenchUserId] = Decoder.decodeString.map(WorkbenchUserId)
  implicit val cryptominingUserMessageDecoder: Decoder[CryptominingUserMessage] = Decoder.forProduct1("userSubjectId")(CryptominingUserMessage.apply)
}

final case class CryptominingUserMessage(userSubjectId: WorkbenchUserId) extends AnyVal
