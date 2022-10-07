package org.broadinstitute.dsde.workbench.sam

import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.server.directives.OnSuccessMagnet
import akka.http.scaladsl.server.util.Tupler
import cats.effect.IO
import cats.effect.unsafe.implicits.global

import scala.concurrent.Future

package object api {
  implicit def ioMarshaller[A, B](implicit m: Marshaller[Future[A], B]): Marshaller[IO[A], B] =
    Marshaller(implicit ec => x => m(x.unsafeToFuture()))
}

object ImplicitConversions {
  import scala.language.implicitConversions

  implicit def ioOnSuccessMagnet[A](ioa: IO[A])(implicit tupler: Tupler[A]): OnSuccessMagnet { type Out = tupler.Out } =
    OnSuccessMagnet.apply(ioa.unsafeToFuture())
}
