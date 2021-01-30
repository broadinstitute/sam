package org.broadinstitute.dsde.workbench.sam.dataAccess

import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.config.DirectoryConfig
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO.Attr

import java.text.SimpleDateFormat
import java.util.Date
import scala.util.matching.Regex

/**
  * Created by dvoet on 6/6/17.
  */
trait DirectorySubjectNameSupport {
  protected val directoryConfig: DirectoryConfig
  val peopleOu = s"ou=people,${directoryConfig.baseDn}"
  val groupsOu = s"ou=groups,${directoryConfig.baseDn}"
  val resourcesOu = s"ou=resources,${directoryConfig.baseDn}"
  val schemaLockOu = s"ou=schemaLock,${directoryConfig.baseDn}"

  protected def groupDn(groupId: WorkbenchGroupIdentity) =
    groupId match {
      case WorkbenchGroupName(name) => s"cn=$name,$groupsOu"
      case rpn: FullyQualifiedPolicyId => policyDn(rpn)
      case _ => throw new WorkbenchException(s"unexpected WorkbenchGroupIdentity $groupId")
    }
  protected def userDn(samUserId: WorkbenchUserId) = s"uid=${samUserId.value},$peopleOu"
  protected def petDn(petServiceAccountId: PetServiceAccountId) = s"${Attr.project}=${petServiceAccountId.project.value},${userDn(petServiceAccountId.userId)}"
  protected def resourceTypeDn(resourceTypeName: ResourceTypeName) = s"${Attr.resourceType}=${resourceTypeName.value},$resourcesOu"
  protected def resourceDn(resource: FullyQualifiedResourceId) = s"${Attr.resourceId}=${resource.resourceId.value},${resourceTypeDn(resource.resourceTypeName)}"
  protected def schemaLockDn(schemaVersion: Int) = s"schemaVersion=$schemaVersion,$schemaLockOu"
  protected def policyDn(policyId: FullyQualifiedPolicyId): String =
    s"${Attr.policy}=${policyId.accessPolicyName.value},${resourceDn(FullyQualifiedResourceId(policyId.resource.resourceTypeName, policyId.resource.resourceId))}"

  protected def subjectDn(subject: WorkbenchSubject) = subject match {
    case g: WorkbenchGroupName => groupDn(g)
    case u: WorkbenchUserId => userDn(u)
    case s: PetServiceAccountId => petDn(s)
    case rpn: FullyQualifiedPolicyId => policyDn(rpn)
    case _ => throw new WorkbenchException(s"unexpected subject [$subject]")
  }

  /**
    * Constructs a regular expression to extract the leading attribute values of a dn. Example: to extract
    * policy namd an resource id from the dn policy=foo,resourceId=bar,resourceType=splat,ou=resources,dc=x,dc=u,dc=com
    * matchAttributeNames would be Seq("policy", "resourceId") and baseDn would be "resourceType=splat,ou=resources,dc=x,dc=u,dc=com".
    *
    * @param matchAttributeNames names of attributes in the leading part of the dn that should match and extract values
    * @param baseDn the trailing part of the dn that should match but we don't care to extract values
    * @return pattern with capture groups for each member of matchAttributeNames
    */
  protected def dnMatcher(matchAttributeNames: Seq[String], baseDn: String): Regex = {
    val partStrings = matchAttributeNames.map { attrName =>
      s"$attrName=([^,]+)"
    }
    partStrings.mkString("(?i)", ",", s",$baseDn").r
  }

  private val groupMatcher = dnMatcher(Seq(Attr.cn), groupsOu)
  private val personMatcher = dnMatcher(Seq(Attr.uid), peopleOu)
  private val petMatcher = dnMatcher(Seq(Attr.project, Attr.uid), peopleOu)
  private val policyMatcher = dnMatcher(Seq(Attr.policy, Attr.resourceId, Attr.resourceType), resourcesOu)

  protected def dnToSubject(dn: String): WorkbenchSubject =
    dn match {
      case groupMatcher(cn) => WorkbenchGroupName(cn)
      case personMatcher(uid) => WorkbenchUserId(uid)
      case petMatcher(petProject, userUid) => PetServiceAccountId(WorkbenchUserId(userUid), GoogleProject(petProject))
      case policyMatcher(policyName, resourceId, resourceTypeName) =>
        FullyQualifiedPolicyId(FullyQualifiedResourceId(ResourceTypeName(resourceTypeName), ResourceId(resourceId)), AccessPolicyName(policyName))
      case _ => throw new WorkbenchException(s"unexpected dn [$dn]")
    }

  protected def dnToGroupIdentity(dn: String): WorkbenchGroupIdentity =
    dnToSubject(dn) match {
      case gn: WorkbenchGroupName => gn
      case policy: FullyQualifiedPolicyId => policy
      case _ => throw new WorkbenchException(s"not a group dn [$dn]")
    }

  protected val dateFormat = "yyyyMMddHHmmss.SSSZ"
  protected def formattedDate(date: Date) = new SimpleDateFormat(dateFormat).format(date)
  protected def parseDate(date: String) = new SimpleDateFormat(dateFormat).parse(date)
}
