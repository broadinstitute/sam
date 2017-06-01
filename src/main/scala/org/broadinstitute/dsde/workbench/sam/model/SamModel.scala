package org.broadinstitute.dsde.workbench.sam.model

/**
  * Created by dvoet on 5/26/17.
  */
sealed trait SamSubject
case class SamUserId(value: String) extends SamSubject
case class SamUserEmail(value: String)
case class SamUser(id: SamUserId, firstName: String, lastName: String, email: Option[SamUserEmail])

case class SamGroupName(value: String) extends SamSubject
case class SamGroup(name: SamGroupName, members: Set[SamSubject])