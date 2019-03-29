package org.broadinstitute.dsde.workbench.sam.config

import org.broadinstitute.dsde.workbench.google.{KeyId, KeyRingId, Location}
import org.broadinstitute.dsde.workbench.model.google.GoogleProject

import scala.concurrent.duration.FiniteDuration

/**
  * created by mtalbott 1/25/18
  *
  * @param project google project for the key
  * @param location location for the key
  * @param keyRingId name of the key ring that contains the key
  * @param keyId name of the key
  */

final case class GoogleKmsConfig(
                                  project: GoogleProject,
                                  location: Location,
                                  keyRingId: KeyRingId,
                                  keyId: KeyId,
                                  rotationPeriod: FiniteDuration
                          )
