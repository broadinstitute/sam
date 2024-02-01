package org.broadinstitute.dsde.workbench.sam.api

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.UUID

class SwaggerRouteMatcherSpec extends AnyFlatSpec with Matchers {

  "SwaggerRouteMatcher.matchRoute" should "match getAccessInstructions before GetGroupPolicyEmails" in {
    val groupName = "group name"
    val matchedRoute = SwaggerRouteMatcher.matchRoute(s"/api/groups/v1/$groupName/accessInstructions")
    matchedRoute shouldBe defined
    matchedRoute.get.route shouldBe "/api/groups/v1/{groupName}/accessInstructions"
    matchedRoute.get.parametersByName("groupName") shouldBe groupName
  }

  it should "match GetGroupPolicyEmails" in {
    val groupName = "group name"
    val policyName = "policy name"
    val matchedRoute = SwaggerRouteMatcher.matchRoute(s"/api/groups/v1/$groupName/$policyName")
    matchedRoute shouldBe defined
    matchedRoute.get.route shouldBe "/api/groups/v1/{groupName}/{policyName}"
    matchedRoute.get.parametersByName("groupName") shouldBe groupName
    matchedRoute.get.parametersByName("policyName") shouldBe policyName
  }

  it should "match resourceActionV2" in {
    val resourceTypeName = "resource type"
    val resourceId = UUID.randomUUID().toString
    val action = "action"
    val userEmail = "user email"
    val matchedRoute = SwaggerRouteMatcher.matchRoute(
      s"/api/resources/v2/${resourceTypeName}/${resourceId}/action/${action}/userEmail/${userEmail}"
    )
    matchedRoute shouldBe defined
    matchedRoute.get.route shouldBe "/api/resources/v2/{resourceTypeName}/{resourceId}/action/{action}/userEmail/{userEmail}"
    matchedRoute.get.parametersByName("resourceTypeName") shouldBe resourceTypeName
    matchedRoute.get.parametersByName("resourceId") shouldBe resourceId
    matchedRoute.get.parametersByName("action") shouldBe action
    matchedRoute.get.parametersByName("userEmail") shouldBe userEmail
  }

  it should "match with no parameters" in {
    val matchedRoute = SwaggerRouteMatcher.matchRoute("/api/config/v1/resourceTypes")
    matchedRoute shouldBe defined
    matchedRoute.get.route shouldBe "/api/config/v1/resourceTypes"
    matchedRoute.get.parametersByName shouldBe empty
  }

  it should "return None when no match is found" in {
    val matchedRoute = SwaggerRouteMatcher.matchRoute("/api/this/is/not/a/valid/path")
    matchedRoute shouldBe empty
  }
}
