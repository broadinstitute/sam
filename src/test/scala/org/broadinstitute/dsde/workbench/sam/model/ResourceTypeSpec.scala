package org.broadinstitute.dsde.workbench.sam.model

import org.broadinstitute.dsde.workbench.sam.Generator
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

class ResourceTypeSpec extends AnyFlatSpecLike with Matchers {
  behavior of "ResourceType.getRoleActions"

  it should "return the correct actions for a resource type" in {
    val resourceType = Generator.genResourceType.sample.get
    resourceType.roles.foreach { role =>
      resourceType.getRoleActions(role.roleName) should be(role.actions)
    }
  }

  it should "return the correct actions for a resource type with includedRoles" in {
    val resourceType = Generator.genResourceType.sample.get
    val testRole = ResourceRoleName("includedRole")
    val withIncludedRoles = resourceType.copy(
      roles = resourceType.roles + ResourceRole(testRole, Set.empty, resourceType.roles.map(_.roleName))
    )
    resourceType.roles.foreach { role =>
      withIncludedRoles.getRoleActions(testRole) should contain allElementsOf role.actions
    }
  }

  it should "tolerate cycles in includedRoles" in {
    val resourceType = Generator.genResourceType.sample.get
    val testRole = ResourceRoleName("includedRole")
    val withIncludedRoles = resourceType.copy(
      roles = resourceType.roles + ResourceRole(testRole, Set.empty, Set(testRole))
    )
    withIncludedRoles.getRoleActions(testRole) shouldBe empty
  }
}
