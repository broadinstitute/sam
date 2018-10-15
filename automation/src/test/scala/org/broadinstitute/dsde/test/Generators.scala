package org.broadinstitute.dsde.workbench.test

import org.broadinstitute.dsde.workbench.google.{CollectionName, Document}
import org.broadinstitute.dsde.workbench.google.util.LockPath
import org.scalacheck.Gen
import scala.concurrent.duration._

object Generators {
  val genLockPath: Gen[LockPath] = for {
    collectionName <- Gen.alphaStr.map(x => CollectionName(s"test$x"))
    document <- Gen.alphaStr.map(x => Document(s"test$x"))
  } yield LockPath(collectionName, document, 5 seconds)
}
