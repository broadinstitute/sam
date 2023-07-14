package org.broadinstitute.dsde.workbench.sam.dataAccess

import org.broadinstitute.dsde.workbench.google.GoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.mockito.ArgumentMatchersSugar.any
import org.mockito.{IdiomaticMockito, Strictness}
import com.google.api.services.groupssettings.model.{Groups => GroupSettings}

import scala.concurrent.Future
object MockGoogleDirectoryDaoBuilder {
  def apply() = new MockGoogleDirectoryDaoBuilder()
}

case class MockGoogleDirectoryDaoBuilder() extends IdiomaticMockito {
  val mockedGoogleDirectoryDAO: GoogleDirectoryDAO = mock[GoogleDirectoryDAO](withSettings.strictness(Strictness.Lenient))

  mockedGoogleDirectoryDAO.createGroup(any[String], any[WorkbenchEmail], any[Option[GroupSettings]]) returns Future.unit

  // def withExistingResource(resource: Resource): MockAccessPolicyDaoBuilder = withExistingResources(Set(resource))

  // def withExistingResources(resources: Iterable[Resource]): MockAccessPolicyDaoBuilder = {
  //   resources.toSet.foreach(makeResourceExist)
  //  this
  // }

  def build: GoogleDirectoryDAO = mockedGoogleDirectoryDAO
}
