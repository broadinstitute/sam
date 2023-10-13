package org.broadinstitute.dsde.workbench.sam.config

import java.time.Instant

/** Terms of Service configuration.
  * @param isGracePeriodEnabled
  *   Set to true if the grace period for ToS acceptance is active
  * @param version
  *   The latest version of the Terms of Service
  * @param baseUrl
  *   The url to the Terra Terms of Service. Used for validation and will be displayed to user in error messages
  * @param rollingAcceptanceWindowExpiration
  *  The expiration time for the rolling acceptance window. If the user has not accepted the new ToS by this time,
  *  they will be denied access to the system. Must be a valid UTC datetime string in ISO 8601 format
  *  example: 2007-12-03T10:15:30.00Z
  */

case class TermsOfServiceConfig(isTosEnabled: Boolean, isGracePeriodEnabled: Boolean, version: String, baseUrl: String, rollingAcceptanceWindowExpiration: Instant)
