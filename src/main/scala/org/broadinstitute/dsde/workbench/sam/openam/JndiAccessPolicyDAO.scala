package org.broadinstitute.dsde.workbench.sam.openam

import javax.naming.NameAlreadyBoundException
import javax.naming.directory._

import akka.http.scaladsl.model.StatusCodes
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchException, WorkbenchExceptionWithErrorReport}
import org.broadinstitute.dsde.workbench.sam.config.DirectoryConfig
import org.broadinstitute.dsde.workbench.sam.directory.{DirectoryDAO, DirectorySubjectNameSupport}
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.util.{BaseDirContext, JndiSupport}
import org.broadinstitute.dsde.workbench.sam._

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
    val role = "role"
    val mail = "mail"
    val policy = "policy"
    val uniqueMember = "uniqueMember"
  }

  def init(): Future[Unit] = {
    for {
      _ <- removePolicySchema()
      _ <- createPolicySchema()
      _ <- createResourcesOrgUnit()
//      _ <- createResourceTypeOrgUnits()
    } yield ()
  }

  def removePolicySchema(): Future[Unit] = withContext { ctx =>
    val schema = ctx.getSchema("")

    Try { schema.destroySubcontext("ClassDefinition/policy") }
    Try { schema.destroySubcontext("ClassDefinition/resourceType") }
    Try { schema.destroySubcontext("ClassDefinition/resource") }
    Try { schema.destroySubcontext("AttributeDefinition/" + Attr.resourceType) }
    Try { schema.destroySubcontext("AttributeDefinition/" + Attr.resource) }
    Try { schema.destroySubcontext("AttributeDefinition/" + Attr.action) }
    Try { schema.destroySubcontext("AttributeDefinition/" + Attr.role) }
  }


  def createPolicySchema(): Future[Unit] = withContext { ctx =>
    val schema = ctx.getSchema("")

    createAttributeDefinition(schema, "1.3.6.1.4.1.18060.0.4.3.2.1", Attr.resourceType, "type of a resource", true)
    createAttributeDefinition(schema, "1.3.6.1.4.1.18060.0.4.3.2.2", Attr.resource, "name of a resource", true)
    createAttributeDefinition(schema, "1.3.6.1.4.1.18060.0.4.3.2.4", Attr.action, "actions applicable to a policy", false)
    createAttributeDefinition(schema, "1.3.6.1.4.1.18060.0.4.3.2.6", Attr.role, "role for the policy if it is for a role", true)

    val policyAttrs = new BasicAttributes(true) // Ignore case
    policyAttrs.put("NUMERICOID", "1.3.6.1.4.1.18060.0.4.3.2.0")
    policyAttrs.put("NAME", "policy")
    policyAttrs.put("DESC", "list subjects for a policy")
    policyAttrs.put("SUP", "groupofuniquenames")
    policyAttrs.put("STRUCTURAL", "true")

    val policyMust = new BasicAttribute("MUST")
    policyMust.add("objectclass")
    policyMust.add(Attr.resourceType)
    policyMust.add(Attr.resource)
    policyMust.add(Attr.uniqueMember)
    policyAttrs.put(policyMust)

    val policyMay = new BasicAttribute("MAY")
    policyMay.add(Attr.action)
    policyMay.add(Attr.role)
    policyAttrs.put(policyMay)

    val resourceTypeAttrs = new BasicAttributes(true) // Ignore case
    resourceTypeAttrs.put("NUMERICOID", "1.3.6.1.4.1.18060.0.4.3.2.1000")
    resourceTypeAttrs.put("NAME", "resourceType")
    resourceTypeAttrs.put("DESC", "type of the resource")
    resourceTypeAttrs.put("SUP", "top")
    resourceTypeAttrs.put("STRUCTURAL", "true")

    val resourceTypeMust = new BasicAttribute("MUST")
    resourceTypeMust.add("objectclass")
    resourceTypeMust.add(Attr.resourceType)
    resourceTypeAttrs.put(resourceTypeMust)

    val resourceAttrs = new BasicAttributes(true) // Ignore case
    resourceAttrs.put("NUMERICOID", "1.3.6.1.4.1.18060.0.4.3.2.1001")
    resourceAttrs.put("NAME", "resource")
    resourceAttrs.put("DESC", "the resource")
    resourceAttrs.put("SUP", "top")
    resourceAttrs.put("STRUCTURAL", "true")

    val resourceMust = new BasicAttribute("MUST")
    resourceMust.add("objectclass")
    resourceMust.add(Attr.resource)
    resourceAttrs.put(resourceMust)

    // Add the new schema object for "fooObjectClass"
    schema.createSubcontext("ClassDefinition/resourceType", resourceTypeAttrs)
    schema.createSubcontext("ClassDefinition/resource", resourceAttrs)
    schema.createSubcontext("ClassDefinition/policy", policyAttrs)
  }

  def createResourcesOrgUnit(): Future[Unit] = withContext { ctx =>
    try {
      val resourcesContext = new BaseDirContext {
        override def getAttributes(name: String): Attributes = {
          val myAttrs = new BasicAttributes(true)  // Case ignore

          val oc = new BasicAttribute("objectclass")
          Seq("top", "organizationalUnit").foreach(oc.add)
          myAttrs.put(oc)

          myAttrs
        }
      }

      ctx.bind(resourcesOu, resourcesContext)

    } catch {
      case e: NameAlreadyBoundException => // ignore
    }
  }

  override def createResource(resource: Resource): Future[Resource] = withContext { ctx =>
    try {
      val resourceContext = new BaseDirContext {
        override def getAttributes(name: String): Attributes = {
          val myAttrs = new BasicAttributes(true) // Case ignore

          val oc = new BasicAttribute("objectclass")
          Seq("top", "organizationalUnit").foreach(oc.add)
          myAttrs.put(oc)

          myAttrs
        }
      }

      ctx.bind(resourceDn(resource), resourceContext)
      resource
    } catch {
      case _: NameAlreadyBoundException => throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, "A resource of this type and name already exists"))
    }
  }

  override def createResourceType(resourceTypeName: ResourceTypeName): Future[ResourceTypeName] = withContext { ctx =>
    try {
      val resourceContext = new BaseDirContext {
        override def getAttributes(name: String): Attributes = {
          val myAttrs = new BasicAttributes(true) // Case ignore

          val oc = new BasicAttribute("objectclass")
          Seq("top", "organizationalUnit").foreach(oc.add)
          myAttrs.put(oc)

          myAttrs
        }
      }

      println(resourceTypeDn(resourceTypeName))


      ctx.bind(resourceTypeDn(resourceTypeName), resourceContext)
      resourceTypeName
    } catch {
      case _: NameAlreadyBoundException => throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, "A resource of this type and name already exists"))
    }
  }

  override def createPolicy(policy: AccessPolicy): Future[AccessPolicy] = withContext { ctx =>
    try {
      val policyContext = new BaseDirContext {
        override def getAttributes(name: String): Attributes = {
          val myAttrs = new BasicAttributes(true) // Case ignore

          val oc = new BasicAttribute("objectclass")
          Seq("top", "policy").foreach(oc.add)
          myAttrs.put(oc)

          if (policy.actions.nonEmpty) {
            val actions = new BasicAttribute(Attr.action)
            policy.actions.foreach(action => actions.add(action.value))
            myAttrs.put(actions)
          }

          if (policy.role.isDefined) {
            val role = new BasicAttribute(Attr.role, policy.role.get.value)
            myAttrs.put(role)
          }

          myAttrs.put(Attr.resourceType, policy.resource.resourceTypeName.value)
          myAttrs.put(Attr.resource, policy.resource.resourceName.value)
          myAttrs
        }
      }

      ctx.bind(policyDn(policy), policyContext)
      policy
    } catch {
      case _: NameAlreadyBoundException => throw new WorkbenchException("foo bar") //WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, "A policy for this subject already exists for this resource"))
    }
  }

  private val resourcesOu = s"ou=resources,${directoryConfig.baseDn}"
  private def resourceTypeDn(resourceTypeName: ResourceTypeName) = s"${Attr.resourceType}=${resourceTypeName.value},$resourcesOu"
  private def resourceDn(resource: Resource) = s"${Attr.resource}=${resource.resourceName.value},${resourceTypeDn(resource.resourceTypeName)}"
  private def policyDn(policy: AccessPolicy): String = s"${Attr.policy}=${policy.name},${resourceDn(policy.resource)}"

  override def listAccessPolicies(resource: Resource): Future[TraversableOnce[AccessPolicy]] = withContext { ctx =>
    val searchAttrs = new BasicAttributes(true)  // Case ignore

    val policies = ctx.search(resourceDn(resource), searchAttrs).asScala.map { searchResult =>
      val members = searchResult.getAttributes.get(Attr.uniqueMember).getAll.asScala.map(_.asInstanceOf[String]).map(dnToSubject).toSet

      AccessPolicy(
        searchResult.getAttributes.get(Attr.policy).get().toString,
        Resource(
          ResourceTypeName(searchResult.getAttributes.get(Attr.resourceType).get().toString),
          ResourceName(searchResult.getAttributes.get(Attr.resource).get().toString)
        ),
        members,
        Option(ResourceRoleName(searchResult.getAttributes.get(Attr.role).get().toString)),
        searchResult.getAttributes.get(Attr.action).getAll.asScala.map(a => ResourceAction(a.toString)).toSet
      )
    }

    policies
  }

  override def deleteResource(resource: Resource): Future[Unit] = withContext { ctx =>
    ctx.unbind(resourceDn(resource))
  }

  override def deletePolicy(policy: AccessPolicy): Future[Unit] = withContext { ctx =>
    ctx.unbind(policyDn(policy))
  }

  private def withContext[T](op: InitialDirContext => T): Future[T] = withContext(directoryConfig.directoryUrl, directoryConfig.user, directoryConfig.password)(op)
}
