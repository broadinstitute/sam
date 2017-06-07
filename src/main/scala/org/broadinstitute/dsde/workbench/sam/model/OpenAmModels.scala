package org.broadinstitute.dsde.workbench.sam.model

/**
  * Created by mbemis on 6/5/17.
  */

case class OpenAmResourceType(
                               name: String,
                               description: String,
                               patterns: Set[String],
                               actions: Map[String, Boolean],
                               _id: String,
                               uuid: String
                             ) {
  def asPayload: ResourceType = {
    ResourceType(this.name, this.description, this.patterns, this.actions)
  }
}

case class OpenAmResourceTypesResponse(
                                       result: Set[OpenAmResourceType]
                                     )



case class OpenAmPolicySet(
                            name: String,
                            conditions: Set[String],
                            applicationType: String,
                            resourceTypeUuids: Set[String],
                            description: String,
                            subjects: Set[String]
                          )
