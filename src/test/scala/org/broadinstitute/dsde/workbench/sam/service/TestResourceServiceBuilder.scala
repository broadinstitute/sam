package org.broadinstitute.dsde.workbench.sam.service

import cats.effect.IO
import org.broadinstitute.dsde.workbench.openTelemetry.OpenTelemetryMetrics
import org.broadinstitute.dsde.workbench.sam.Generator.{genResourceType, genWorkspaceResourceType}
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
)(implicit val executionContext: ExecutionContext, val openTelemetry: OpenTelemetryMetrics[IO])
    extends MockitoSugar {
  private val emailDomain = "example.com"
  private var maybeDefaultResourceType: Option[ResourceType] = None
  private var maybeWorkspaceResourceType: Option[ResourceType] = None

  def withResourceTypes(): TestResourceServiceBuilder = {
    maybeDefaultResourceType = Some(genResourceType.sample.get)
    maybeWorkspaceResourceType = Some(genWorkspaceResourceType.sample.get)
    this
  }

  def build: ResourceService = {
    val resourceTypes: mutable.Map[ResourceTypeName, ResourceType] = new TrieMap()

    maybeDefaultResourceType match {
      case Some(rt) =>
        resourceTypes += rt.name -> rt
      case _ => ()
    }

    maybeWorkspaceResourceType match {
      case Some(rt) =>
        resourceTypes += rt.name -> rt
      case _ => ()
    }

    new ResourceService(resourceTypes.toMap, policyEvaluatorService, accessPolicyDAO, directoryDAO, cloudExtensions, emailDomain, Set())
  }
}
