package org.broadinstitute.dsde.workbench.sam.directory

import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.config.DirectoryConfig
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO.Attr
import org.broadinstitute.dsde.workbench.sam.util.JndiSupport

/**
  * Created by dvoet on 6/6/17.
  */
trait DirectorySubjectNameSupport extends JndiSupport {
  protected val directoryConfig: DirectoryConfig
  val peopleOu = s"ou=people,${directoryConfig.baseDn}"
  val groupsOu = s"ou=groups,${directoryConfig.baseDn}"
  // TODO people to pass the IDC check. Look at configuring IDC to look for ou=pets as well.
  val petsOu   = s"ou=pets,${directoryConfig.baseDn}"

  protected def groupDn(groupName: WorkbenchGroupName) = s"cn=${groupName.value},$groupsOu"
  protected def userDn(samUserId: WorkbenchUserId) = s"uid=${samUserId.value},$peopleOu"
  protected def petDn(serviceAccountId: WorkbenchUserServiceAccountId) = s"uid=${serviceAccountId.value},$petsOu"

  protected def subjectDn(subject: WorkbenchSubject) = subject match {
    case g: WorkbenchGroupName => groupDn(g)
    case u: WorkbenchUserId => userDn(u)
    case s: WorkbenchUserServiceAccountId => petDn(s)
  }

  protected def dnToSubject(dn: String): WorkbenchSubject = {
    val groupMatcher = dnMatcher(Seq(Attr.cn), groupsOu)
    val personMatcher = dnMatcher(Seq(Attr.uid), peopleOu)
    val petMatcher = dnMatcher(Seq(Attr.uid), petOu)

    dn match {
      case groupMatcher(cn) => WorkbenchGroupName(cn)
      case personMatcher(uid) => WorkbenchUserId(uid)
      case petMatcher(uid) => WorkbenchUserServiceAccountId(uid)
      case _ => throw new WorkbenchException(s"unexpected dn [$dn]")
    }
  }

  protected def dnToGroupName(dn:String): WorkbenchGroupName = {
    dnToSubject(dn) match {
      case gn: WorkbenchGroupName => gn
      case _ => throw new WorkbenchException(s"not a group dn [$dn]")
    }
  }
}
