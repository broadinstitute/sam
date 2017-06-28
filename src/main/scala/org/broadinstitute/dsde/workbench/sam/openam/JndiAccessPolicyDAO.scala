package org.broadinstitute.dsde.workbench.sam.openam

import javax.naming.NameAlreadyBoundException
import javax.naming.directory._

import akka.http.scaladsl.model.StatusCodes
import org.broadinstitute.dsde.workbench.sam.WorkbenchExceptionWithErrorReport
import org.broadinstitute.dsde.workbench.sam.directory.{DirectoryConfig, DirectoryDAO, DirectorySubjectNameSupport}
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.util.{BaseDirContext, JndiSupport}

import scala.concurrent.{ExecutionContext, Future}
import scala.collection.JavaConverters._
import scala.util.Try

/**
  * Created by dvoet on 6/26/17.
  */
class JndiAccessPolicyDAO(protected val directoryConfig: DirectoryConfig)(implicit executionContext: ExecutionContext) extends AccessPolicyDAO with DirectorySubjectNameSupport with JndiSupport {

  private object Attr {
    val resource = "resource"
    val resourceType = "resourceType"
    val subject = "subject"
    val action = "action"
    val policyId = "policyId"
  }

  def removePolicySchema(): Future[Unit] = withContext { ctx =>
    val schema = ctx.getSchema("")

    Try { schema.destroySubcontext("ClassDefinition/policy") }
    Try { schema.destroySubcontext("AttributeDefinition/" + Attr.resourceType) }
    Try { schema.destroySubcontext("AttributeDefinition/" + Attr.resource) }
    Try { schema.destroySubcontext("AttributeDefinition/" + Attr.subject) }
    Try { schema.destroySubcontext("AttributeDefinition/" + Attr.action) }
    Try { schema.destroySubcontext("AttributeDefinition/" + Attr.policyId) }
  }

  def createPolicySchema(): Future[Unit] = withContext { ctx =>
    val schema = ctx.getSchema("")

    createAttributeDefinition(schema, "1.3.6.1.4.1.18060.0.4.3.2.1", Attr.resourceType, "type of a resource", true)
    createAttributeDefinition(schema, "1.3.6.1.4.1.18060.0.4.3.2.2", Attr.resource, "name of a resource", true)
    createAttributeDefinition(schema, "1.3.6.1.4.1.18060.0.4.3.2.3", Attr.subject, "subject applicable to a policy", true)
    createAttributeDefinition(schema, "1.3.6.1.4.1.18060.0.4.3.2.4", Attr.action, "actions applicable to a policy", false)
    createAttributeDefinition(schema, "1.3.6.1.4.1.18060.0.4.3.2.5", Attr.policyId, "id of a policy", true)

    val attrs = new BasicAttributes(true) // Ignore case
    attrs.put("NUMERICOID", "1.3.6.1.4.1.18060.0.4.3.2.0")
    attrs.put("NAME", "policy")
    attrs.put("DESC", "list actions for a subject on a resource")
    attrs.put("SUP", "top")
    attrs.put("STRUCTURAL", "true")

    val must = new BasicAttribute("MUST")
    must.add("objectclass")
    must.add(Attr.resourceType)
    must.add(Attr.resource)
    must.add(Attr.subject)
    must.add(Attr.policyId)
    attrs.put(must)

    val may = new BasicAttribute("MAY", Attr.action)
    attrs.put(may)


    // Add the new schema object for "fooObjectClass"
    schema.createSubcontext("ClassDefinition/policy", attrs)
  }

  def createPoliciesOrgUnit(): Future[Unit] = withContext { ctx =>
    try {
      val policiesContext = new BaseDirContext {
        override def getAttributes(name: String): Attributes = {
          val myAttrs = new BasicAttributes(true)  // Case ignore

          val oc = new BasicAttribute("objectclass")
          Seq("top", "organizationalUnit").foreach(oc.add)
          myAttrs.put(oc)

          myAttrs
        }
      }

      ctx.bind(policiesOu, policiesContext)

    } catch {
      case e: NameAlreadyBoundException => // ignore
    }
  }

  private def createAttributeDefinition(schema: DirContext, numericOID: String, name: String, description: String, singleValue: Boolean) = {
    val attributes = new BasicAttributes(true)
    attributes.put("NUMERICOID", numericOID)
    attributes.put("NAME", name)
    attributes.put("DESC", description)
    if (singleValue) attributes.put("SINGLE-VALUE", singleValue.toString) // note absence of this attribute means multi-value and presence means single, value does not matter
    schema.createSubcontext(s"AttributeDefinition/$name", attributes)
  }

  override def createPolicy(policy: AccessPolicy): Future[AccessPolicy] = withContext { ctx =>
    try {
      val policyContext = new BaseDirContext {
        override def getAttributes(name: String): Attributes = {
          val myAttrs = new BasicAttributes(true)  // Case ignore

          val oc = new BasicAttribute("objectclass")
          Seq("top", "policy").foreach(oc.add)
          myAttrs.put(oc)

          if (policy.actions.nonEmpty) {
            val actions = new BasicAttribute(Attr.action)
            policy.actions.foreach(action => actions.add(action.value))
            myAttrs.put(actions)
          }

          myAttrs.put(Attr.resourceType, policy.resourceType.value)
          myAttrs.put(Attr.resource, policy.resource.value)
          myAttrs.put(Attr.subject, subjectDn(policy.subject))
          myAttrs.put(Attr.policyId, policy.id.value)

          myAttrs
        }
      }

      ctx.bind(policyDn(policy), policyContext)
      policy

    } catch {
      case e: NameAlreadyBoundException =>
        throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, e.getMessage))
    }
  }

  private val policiesOu = s"ou=policies,${directoryConfig.baseDn}"
  private def policyDn(policy: AccessPolicy): String = policyDn(policy.id)
  private def policyDn(id: AccessPolicyId): String = s"${Attr.policyId}=${id.value},$policiesOu"

  override def listAccessPolicies(resourceType: ResourceTypeName, resourceName: ResourceName): Future[TraversableOnce[AccessPolicy]] = withContext { ctx =>
    val searchAttrs = new BasicAttributes(true)  // Case ignore
    searchAttrs.put(Attr.resourceType, resourceType.value)
    searchAttrs.put(Attr.resource, resourceName.value)

    val policies = ctx.search(policiesOu, searchAttrs).asScala.map { searchResult =>
      AccessPolicy(
        AccessPolicyId(searchResult.getAttributes.get(Attr.policyId).get().toString),
        searchResult.getAttributes.get(Attr.action).getAll.asScala.map(a => ResourceAction(a.toString)).toSet,
        ResourceTypeName(searchResult.getAttributes.get(Attr.resourceType).get().toString),
        ResourceName(searchResult.getAttributes.get(Attr.resource).get().toString),
        dnToSubject(searchResult.getAttributes.get(Attr.subject).get().toString)
      )
    }

    policies
  }

  override def deletePolicy(policyId: AccessPolicyId): Future[Unit] = withContext { ctx =>
    ctx.unbind(policyDn(policyId))
  }

  private def withContext[T](op: InitialDirContext => T): Future[T] = withContext(directoryConfig.directoryUrl, directoryConfig.user, directoryConfig.password)(op)
}
