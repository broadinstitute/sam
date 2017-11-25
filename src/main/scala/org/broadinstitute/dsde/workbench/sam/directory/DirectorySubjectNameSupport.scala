package org.broadinstitute.dsde.workbench.sam.directory

import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.config.DirectoryConfig
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO.Attr
import org.broadinstitute.dsde.workbench.sam.util.JndiSupport

/**
  * Created by dvoet on 6/6/17.
  */
trait DirectorySubjectNameSupport extends JndiSupport {
  protected val directoryConfig: DirectoryConfig
  val peopleOu = s"ou=people,${directoryConfig.baseDn}"
  val groupsOu = s"ou=groups,${directoryConfig.baseDn}"
  val resourcesOu = s"ou=resources,${directoryConfig.baseDn}"

  protected def groupDn(groupId: WorkbenchGroupIdentity) = {
    groupId match {
      case WorkbenchGroupName(name) => s"cn=$name,$groupsOu"
      case rpn: ResourceAndPolicyName => policyDn(rpn)
      case _ => throw new WorkbenchException(s"unexpected WorkbenchGroupIdentity $groupId")
    }
  }
  protected def userDn(samUserId: WorkbenchUserId) = s"uid=${samUserId.value},$peopleOu"
  protected def petDn(petServiceAccountId: PetServiceAccountId) = s"uid=${petServiceAccountId.petId.value},${userDn(petServiceAccountId.userId)}"
  protected def resourceTypeDn(resourceTypeName: ResourceTypeName) = s"${Attr.resourceType}=${resourceTypeName.value},$resourcesOu"
  protected def resourceDn(resource: Resource) = s"${Attr.resourceId}=${resource.resourceId.value},${resourceTypeDn(resource.resourceTypeName)}"
  protected def policyDn(resourceAndPolicyName: ResourceAndPolicyName): String = s"${Attr.policy}=${resourceAndPolicyName.accessPolicyName.value},${resourceDn(resourceAndPolicyName.resource)}"

  protected def subjectDn(subject: WorkbenchSubject) = subject match {
    case g: WorkbenchGroupName => groupDn(g)
    case u: WorkbenchUserId => userDn(u)
    case s: PetServiceAccountId => petDn(s)
    case rpn: ResourceAndPolicyName => policyDn(rpn)
    case _ => throw new WorkbenchException(s"unexpected subject [$subject]")
  }

  protected def dnToSubject(dn: String): WorkbenchSubject = {
    val groupMatcher = dnMatcher(Seq(Attr.cn), groupsOu)
    val personMatcher = dnMatcher(Seq(Attr.uid), peopleOu)
    val petMatcher = dnMatcher(Seq(Attr.uid, Attr.uid), peopleOu)
    val policyMatcher = dnMatcher(Seq(Attr.policy, Attr.resourceId, Attr.resourceType), resourcesOu)

    dn match {
      case groupMatcher(cn) => WorkbenchGroupName(cn)
      case personMatcher(uid) => WorkbenchUserId(uid)
      case petMatcher(petUid, userUid) => PetServiceAccountId(WorkbenchUserId(userUid), WorkbenchUserServiceAccountSubjectId(petUid))
      case policyMatcher(policyName, resourceId, resourceTypeName) => ResourceAndPolicyName(Resource(ResourceTypeName(resourceTypeName), ResourceId(resourceId)), AccessPolicyName(policyName))
      case _ => throw new WorkbenchException(s"unexpected dn [$dn]")
    }
  }

  protected def dnToGroupIdentity(dn:String): WorkbenchGroupIdentity = {
    dnToSubject(dn) match {
      case gn: WorkbenchGroupName => gn
      case policy: ResourceAndPolicyName => policy
      case _ => throw new WorkbenchException(s"not a group dn [$dn]")
    }
  }
}
