package org.broadinstitute.dsde.workbench.sam.directory

import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.config.DirectoryConfig

/**
  * Created by dvoet on 6/6/17.
  */
trait DirectorySubjectNameSupport {
  protected val directoryConfig: DirectoryConfig
  val peopleOu = s"ou=people,${directoryConfig.baseDn}"
  val groupsOu = s"ou=groups,${directoryConfig.baseDn}"
  val petsOu   = s"ou=pets,ou=people,${directoryConfig.baseDn}"

  protected def groupDn(groupName: WorkbenchGroupName) = s"cn=${groupName.value},$groupsOu"
  protected def userDn(samUserId: WorkbenchUserId) = s"uid=${samUserId.value},$peopleOu"

  // TODO this needs to use peopleOu to pass the open_idc check. Look at configuring IDC to allow ou=pets as well.
  protected def petDn(serviceAccountId: WorkbenchUserServiceAccountId) = s"uid=${serviceAccountId.value},$petsOu"

  protected def subjectDn(subject: WorkbenchSubject) = subject match {
    case g: WorkbenchGroupName => groupDn(g)
    case u: WorkbenchUserId => userDn(u)
    case s: WorkbenchUserServiceAccountId => petDn(s)
  }

  protected def dnToSubject(dn: String): WorkbenchSubject = {
    val splitDn = dn.split(",")

    splitDn.lift(1) match {
      case Some(ou) => {
        if(ou.equalsIgnoreCase("ou=groups")) WorkbenchGroupName(splitDn(0).stripPrefix("cn="))
        else if(ou.equalsIgnoreCase("ou=people")) WorkbenchUserId(splitDn(0).stripPrefix("uid="))
        else if(ou.equalsIgnoreCase("ou=pets")) WorkbenchUserServiceAccountId(splitDn(0).stripPrefix("uid="))
        else throw new WorkbenchException(s"unexpected dn [$dn]")
      }
      case None => throw new WorkbenchException(s"unexpected dn [$dn]")
    }
  }

  protected def dnToGroupName(dn:String): WorkbenchGroupName = {
    dnToSubject(dn) match {
      case gn: WorkbenchGroupName => gn
      case _ => throw new WorkbenchException(s"not a group dn [$dn]")
    }
  }
}
