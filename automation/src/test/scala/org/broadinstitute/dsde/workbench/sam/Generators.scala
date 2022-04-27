package org.broadinstitute.dsde.workbench.sam

import org.broadinstitute.dsde.workbench.google2.{CollectionName, Document}
import org.broadinstitute.dsde.workbench.google2.util.LockPath
import org.scalacheck.Gen
import scala.concurrent.duration._

object Generators {
  val genLockPath: Gen[LockPath] = for {
    collectionName <- Gen.alphaStr.map(x => CollectionName(s"test$x"))
    document <- Gen.alphaStr.map(x => Document(s"test$x"))
  } yield LockPath(collectionName, document, 5 seconds)
}
