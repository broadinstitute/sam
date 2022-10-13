package org.broadinstitute.dsde.workbench.sam.config

case class OidcConfig(authorityEndpoint: String, clientId: String, clientSecret: Option[String], legacyGoogleClientId: Option[String])
