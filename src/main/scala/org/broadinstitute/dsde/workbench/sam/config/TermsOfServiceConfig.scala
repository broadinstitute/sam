package org.broadinstitute.dsde.workbench.sam.config

/**
  * Terms of Service configuration.
  * @param enabled Set to false to disable Terms of Service enforcement
  * @param version The latest version of the Terms of Service
  */

case class TermsOfServiceConfig(enabled: Boolean, version: Int)
