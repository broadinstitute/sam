package org.broadinstitute.dsde.workbench.sam.config

object OidcConfig {
  def fromMap(config: Map[String, String]): OidcConfig = {
    OidcConfig(
      // todo: pick environment variable names and defaults
      authorityEndpoint = config.getOrElse("SAM_OIDC_AUTHORITY", new RuntimeException("SAM_OIDC_AUTHORITY not set.")),
      clientId = config.getOrElse(???, ???),
      clientSecret = config.get(???),
      legacyGoogleClientId = config.get(???)
    )
  }
}
case class OidcConfig(authorityEndpoint: String, clientId: String, clientSecret: Option[String], legacyGoogleClientId: Option[String])
