package org.broadinstitute.dsde.workbench.sam.mock

import com.google.api.services.cloudresourcemanager.model.Operation
import org.broadinstitute.dsde.workbench.google.mock.MockGoogleProjectDAO

import scala.concurrent.Future

class MockSamGoogleProjectDAO extends MockGoogleProjectDAO {

  override def pollOperation(operationId: String): Future[Operation] = {
    val operation = new com.google.api.services.cloudresourcemanager.model.Operation
    operation.setDone(true)
    Future.successful(operation)
  }
}
