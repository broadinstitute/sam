package org.broadinstitute.dsde.workbench.sam.directory

import org.broadinstitute.dsde.workbench.model.{WorkbenchException, WorkbenchGroupName, WorkbenchSubject, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.config.DirectoryConfig

/**
  * Created by dvoet on 6/6/17.
  */
trait DirectorySubjectNameSupport {
  protected val directoryConfig: DirectoryConfig
  val peopleOu = s"ou=people,${directoryConfig.baseDn}"
  val groupsOu = s"ou=groups,${directoryConfig.baseDn}"

  protected def groupDn(groupName: WorkbenchGroupName) = s"cn=${groupName.value},$groupsOu"
  protected def userDn(samUserId: WorkbenchUserId) = s"uid=${samUserId.value},$peopleOu"

  protected def subjectDn(subject: WorkbenchSubject) = subject match {
    case g: WorkbenchGroupName => groupDn(g)
    case u: WorkbenchUserId => userDn(u)
  }

  protected def dnToSubject(dn: String): WorkbenchSubject = {
    val splitDn = dn.split(",")

    splitDn.lift(1) match {
      case Some(ou) => {
        if(ou.equalsIgnoreCase("ou=groups")) WorkbenchGroupName(splitDn(0).stripPrefix("cn="))
        else if(ou.equalsIgnoreCase("ou=people")) WorkbenchUserId(splitDn(0).stripPrefix("uid="))
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
