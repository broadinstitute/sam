package org.broadinstitute.dsde.workbench.sam.config

import org.broadinstitute.dsde.workbench.model.google.GoogleProject

case class JanitorConfig(
    enabled: Boolean,
    clientCredential: ServiceAccountCredentialJson,
    trackResourceProjectId: GoogleProject,
    trackResourceTopicName: String
)
