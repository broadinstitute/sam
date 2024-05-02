package org.broadinstitute.dsde.workbench.sam.model

case class Lock(name: String, lockType: String, lockDetails: Map[String, String])
