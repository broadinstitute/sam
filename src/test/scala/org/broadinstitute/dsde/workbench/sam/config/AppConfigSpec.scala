package org.broadinstitute.dsde.workbench.sam.config

import com.typesafe.config.{ConfigException, ConfigFactory, ConfigValueFactory}
import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.sam.model.api.AccessPolicyMembershipRequest
import org.broadinstitute.dsde.workbench.sam.model.{
  AccessPolicyDescendantPermissions,
  AccessPolicyName,
  FullyQualifiedPolicyId,
  FullyQualifiedResourceId,
  ResourceId,
  ResourceRoleName,
  ResourceTypeName,
  SamResourceTypes
}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AppConfigSpec extends AnyFlatSpec with Matchers {
  "AppConfig" should "fallback to sam.conf if no environment variable is defined" in {

    val appConfig = AppConfig.load
    appConfig.prometheusConfig.endpointPort should be(1)
  }

  it should "correctly read multiple service admin accounts from env variables" in {

    val appConfig = AppConfig.load
    appConfig.adminConfig.serviceAccountAdmins.size shouldEqual 2
  }

  it should "not parse google stanza if disabled" in {
    val samConfig = ConfigFactory.parseResourcesAnySyntax("sam").resolve()
    val config = ConfigFactory.load()
    val combinedConfig = samConfig.withFallback(config)

    // test that config is read correctly when google is disabled even when googleServices.appName is missing
    AppConfig
      .readConfig(
        combinedConfig
          .withValue("googleServices.googleEnabled", ConfigValueFactory.fromAnyRef(false))
          .withoutPath("googleServices.appName")
      )
      .googleConfig shouldBe None

    // confirm that missing googleServices.appName otherwise throws an exception
    intercept[ConfigException.Missing] {
      AppConfig.readConfig(combinedConfig.withoutPath("googleServices.appName"))
    }
  }

  it should "load resourceAccessPolicies" in {
    val appConfig = AppConfig.load
    appConfig.resourceAccessPolicies should contain theSameElementsAs Map(
      FullyQualifiedPolicyId(FullyQualifiedResourceId(SamResourceTypes.resourceTypeAdminName, ResourceId("workspace")), AccessPolicyName("rawls-policy")) ->
        AccessPolicyMembershipRequest(
          Set(WorkbenchEmail("rawls@test.firecloud.org")),
          Set.empty,
          Set.empty,
          Some(
            Set(
              AccessPolicyDescendantPermissions(
                ResourceTypeName("workspace"),
                Set.empty,
                Set(ResourceRoleName("owner"))
              )
            )
          ),
          None
        ),
      FullyQualifiedPolicyId(FullyQualifiedResourceId(SamResourceTypes.resourceTypeAdminName, ResourceId("kubernetes-app")), AccessPolicyName("leo-policy")) ->
        AccessPolicyMembershipRequest(Set(WorkbenchEmail("leo@test.firecloud.org")), Set.empty, Set(ResourceRoleName("support")), None, None)
    )
  }
}
