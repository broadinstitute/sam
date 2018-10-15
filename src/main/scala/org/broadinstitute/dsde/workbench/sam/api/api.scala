package org.broadinstitute.dsde.workbench.sam

import akka.http.scaladsl.marshalling.Marshaller
import cats.effect.IO

import scala.concurrent.Future

package object api {
  implicit def ioMarshaller[A, B](implicit m: Marshaller[Future[A], B]): Marshaller[IO[A], B] =
    Marshaller(implicit ec => (x => m(x.unsafeToFuture())))
}
