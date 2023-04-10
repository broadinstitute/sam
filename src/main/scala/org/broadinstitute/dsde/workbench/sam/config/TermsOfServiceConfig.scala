package org.broadinstitute.dsde.workbench.sam.config

/** Terms of Service configuration.
  * @param isGracePeriodEnabled
  *   Set to true if the grace period for ToS acceptance is active
  * @param version
  *   The latest version of the Terms of Service
  * @param url
  *   The url to the Terra Terms of Service. Used for validation and will be displayed to user in error messages
  */

case class TermsOfServiceConfig(isTosEnabled: Boolean, isGracePeriodEnabled: Boolean, version: String, url: String)
