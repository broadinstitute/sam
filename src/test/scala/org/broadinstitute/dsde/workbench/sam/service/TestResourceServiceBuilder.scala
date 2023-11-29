package org.broadinstitute.dsde.workbench.sam.service

import org.broadinstitute.dsde.workbench.sam.dataAccess.{AccessPolicyDAO, DirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.model._
import org.mockito.scalatest.MockitoSugar

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.ExecutionContext

case class TestResourceServiceBuilder(
    policyEvaluatorService: PolicyEvaluatorService,
    accessPolicyDAO: AccessPolicyDAO,
    directoryDAO: DirectoryDAO,
    cloudExtensions: CloudExtensions
)(implicit val executionContext: ExecutionContext)
    extends MockitoSugar {
  private val emailDomain = "example.com"
  private val resourceTypes: mutable.Map[ResourceTypeName, ResourceType] = new TrieMap()

  def withResourceTypes(resourceTypeCollection: Set[ResourceType]): TestResourceServiceBuilder = {
    resourceTypeCollection.foreach(rt => resourceTypes += rt.name -> rt)
    this
  }

  def build: ResourceService =
    new ResourceService(resourceTypes.toMap, policyEvaluatorService, accessPolicyDAO, directoryDAO, cloudExtensions, emailDomain, Set())
}
