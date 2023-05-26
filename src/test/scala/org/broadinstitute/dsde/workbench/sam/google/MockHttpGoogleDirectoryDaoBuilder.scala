package org.broadinstitute.dsde.workbench.sam.google

import org.broadinstitute.dsde.workbench.google.HttpGoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchGroupName}
import org.mockito.ArgumentMatchersSugar.any
import org.mockito.IdiomaticMockito
import org.mockito.Mockito.RETURNS_SMART_NULLS

import scala.concurrent.Future

case class MockHttpGoogleDirectoryDaoBuilder() extends IdiomaticMockito {
  val mockGoogleDirectoryDao: HttpGoogleDirectoryDAO = mock[HttpGoogleDirectoryDAO](RETURNS_SMART_NULLS)

  mockGoogleDirectoryDao.createGroup(any[WorkbenchGroupName], any[WorkbenchEmail]) returns Future.successful(())
  mockGoogleDirectoryDao.addMemberToGroup(any[WorkbenchEmail], any[WorkbenchEmail]) returns Future.successful(())

  def build: HttpGoogleDirectoryDAO = mockGoogleDirectoryDao
}
