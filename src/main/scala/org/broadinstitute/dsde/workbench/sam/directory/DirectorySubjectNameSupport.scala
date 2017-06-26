package org.broadinstitute.dsde.workbench.sam.directory

import org.broadinstitute.dsde.workbench.sam.WorkbenchException
import org.broadinstitute.dsde.workbench.sam.model.{SamGroupName, SamSubject, SamUserId}

/**
  * Created by dvoet on 6/6/17.
  */
trait DirectorySubjectNameSupport {
  protected val directoryConfig: DirectoryConfig

  protected def groupDn(groupName: SamGroupName) = s"cn=${groupName.value},ou=groups,${directoryConfig.baseDn}"
  //TODO: Is this seriously what we have to do to make policies work?
  protected def policyGroupDn(groupName: SamGroupName) = s"id=${groupName.value},ou=group,${directoryConfig.baseDn}"
  protected def userDn(samUserId: SamUserId) = s"uid=${samUserId.value},ou=people,${directoryConfig.baseDn}"

  protected def subjectDn(subject: SamSubject) = subject match {
    case g: SamGroupName => groupDn(g)
    case u: SamUserId => userDn(u)
  }

  protected def policySubjectDn(subject: SamSubject) = subject match {
    case g: SamGroupName => policyGroupDn(g)
    case u: SamUserId => userDn(u)
  }

  protected def dnToSubject(dn: String): SamSubject = {
    dn.split(",").toList match {
      case name :: "ou=groups" :: tail => SamGroupName(name.stripPrefix("cn="))
      case name :: "ou=people" :: tail => SamUserId(name.stripPrefix("uid="))
      case _ => throw new WorkbenchException(s"unexpected dn [$dn]")
    }
  }

  protected def dnToGroupName(dn:String): SamGroupName = {
    dnToSubject(dn) match {
      case gn: SamGroupName => gn
      case _ => throw new WorkbenchException(s"not a group dn [$dn]")
    }
  }
}
