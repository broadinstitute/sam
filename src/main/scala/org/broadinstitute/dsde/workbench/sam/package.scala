package org.broadinstitute.dsde.workbench

import akka.http.scaladsl.marshalling.Marshaller
import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.ErrorReportSource
import scala.concurrent.Future

/**
  * Created by dvoet on 5/18/17.
  */
package object sam {
  implicit val errorReportSource = ErrorReportSource("sam")
  implicit def ioMarshaller[A, B](implicit m: Marshaller[Future[A], B]): Marshaller[IO[A], B] =
    Marshaller(implicit ec => (x => m(x.unsafeToFuture())))
}
