package org.broadinstitute.dsde.workbench.sam.config

/**
  * created by mtalbott 1/25/18
  *
  * @param project google project for the key
  * @param location location for the key
  * @param keyRingId name of the key ring that contains the key
  * @param keyId name of the key
  */

final case class GoogleKmsConfig(
                                  project: String,
                                  location: String,
                                  keyRingId: String,
                                  keyId: String
                          )