package org.broadinstitute.dsde.workbench.sam.schema

import javax.naming.NameAlreadyBoundException
import javax.naming.directory._

import org.broadinstitute.dsde.workbench.sam.config.DirectoryConfig
import org.broadinstitute.dsde.workbench.sam.util.{BaseDirContext, JndiSupport}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import JndiSchemaDAO._

/**
  * Created by mbemis on 10/3/17.
  */
object JndiSchemaDAO {
  object Attr {
    val resourceId = "resourceId"
    val resourceType = "resourceType"
    val subject = "subject"
    val action = "action"
    val role = "role"
    val email = "mail"
    val ou = "ou"
    val cn = "cn"
    val policy = "policy"
    val uniqueMember = "uniqueMember"
    val groupUpdatedTimestamp = "groupUpdatedTimestamp"
    val groupSynchronizedTimestamp = "groupSynchronizedTimestamp"
    val petServiceAccount = "petServiceAccount"
    val member = "member"
    val memberOf = "isMemberOf"
    val givenName = "givenName"
    val sn = "sn"
    val uid = "uid"
  }
}

class JndiSchemaDAO(protected val directoryConfig: DirectoryConfig)(implicit executionContext: ExecutionContext) extends JndiSupport {

  def init(): Future[Unit] = {
    for {
      _ <- destroySchema()
      _ <- createSchema()
    } yield ()
  }

  def createSchema(): Future[Unit] = {
    for {
      _ <- createWorkbenchGroupSchema()
      _ <- createResourcesOrgUnit()
      _ <- createPolicySchema()
      _ <- createWorkbenchPersonSchema()
    } yield ()
  }

  def destroySchema(): Future[Unit] = {
    for {
      _ <- removePolicySchema()
      _ <- removeWorkbenchGroupSchema()
      _ <- removeWorkbenchPersonSchema()
    } yield ()
  }

  //
  // workbenchGroup
  //

  private def createWorkbenchGroupSchema(): Future[Unit] = withContext { ctx =>
    val schema = ctx.getSchema("")

    createAttributeDefinition(schema, "1.3.6.1.4.1.18060.0.4.3.2.200", Attr.groupUpdatedTimestamp, "time when group was updated", true, Option("generalizedTimeMatch"), Option("generalizedTimeOrderingMatch"), Option("1.3.6.1.4.1.1466.115.121.1.24"))
    createAttributeDefinition(schema, "1.3.6.1.4.1.18060.0.4.3.2.201", Attr.groupSynchronizedTimestamp, "time when group was synchronized", true, Option("generalizedTimeMatch"), Option("generalizedTimeOrderingMatch"), Option("1.3.6.1.4.1.1466.115.121.1.24"))

    val attrs = new BasicAttributes(true) // Ignore case
    attrs.put("NUMERICOID", "1.3.6.1.4.1.18060.0.4.3.2.100")
    attrs.put("NAME", "workbenchGroup")
    attrs.put("SUP", "groupofuniquenames")
    attrs.put("STRUCTURAL", "true")

    val must = new BasicAttribute("MUST")
    must.add("objectclass")
    must.add(Attr.email)
    attrs.put(must)

    val may = new BasicAttribute("MAY")
    may.add(Attr.groupUpdatedTimestamp)
    may.add(Attr.groupSynchronizedTimestamp)
    attrs.put(may)

    // Add the new schema object for "fooObjectClass"
    schema.createSubcontext("ClassDefinition/workbenchGroup", attrs)
  }

  private def removeWorkbenchGroupSchema(): Future[Unit] = withContext { ctx =>
    val schema = ctx.getSchema("")

    // Intentionally ignores errors
    Try { schema.destroySubcontext("ClassDefinition/workbenchGroup") }
    Try { schema.destroySubcontext("AttributeDefinition/" + Attr.groupSynchronizedTimestamp) }
    Try { schema.destroySubcontext("AttributeDefinition/" + Attr.groupUpdatedTimestamp) }
  }

  //
  // policy
  //

  private def createPolicySchema(): Future[Unit] = withContext { ctx =>
    val schema = ctx.getSchema("")

    createAttributeDefinition(schema, "1.3.6.1.4.1.18060.0.4.3.2.1", Attr.resourceType, "the type of the resource", true)
    createAttributeDefinition(schema, "1.3.6.1.4.1.18060.0.4.3.2.8", Attr.resourceId, "the id of the resource", true)
    createAttributeDefinition(schema, "1.3.6.1.4.1.18060.0.4.3.2.4", Attr.action, "the actions applicable to a policy", false)
    createAttributeDefinition(schema, "1.3.6.1.4.1.18060.0.4.3.2.6", Attr.role, "the roles for the policy, if any", false)
    createAttributeDefinition(schema, "1.3.6.1.4.1.18060.0.4.3.2.7", Attr.policy, "the policy name", true)

    val policyAttrs = new BasicAttributes(true) // Ignore case
    policyAttrs.put("NUMERICOID", "1.3.6.1.4.1.18060.0.4.3.2.0")
    policyAttrs.put("NAME", "policy")
    policyAttrs.put("DESC", "list subjects for a policy")
    policyAttrs.put("SUP", "workbenchGroup")
    policyAttrs.put("STRUCTURAL", "true")

    val policyMust = new BasicAttribute("MUST")
    policyMust.add("objectclass")
    policyMust.add(Attr.resourceType)
    policyMust.add(Attr.resourceId)
    policyMust.add(Attr.cn)
    policyMust.add(Attr.policy)
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
    resourceTypeMust.add(Attr.ou)
    resourceTypeAttrs.put(resourceTypeMust)

    val resourceAttrs = new BasicAttributes(true) // Ignore case
    resourceAttrs.put("NUMERICOID", "1.3.6.1.4.1.18060.0.4.3.2.1001")
    resourceAttrs.put("NAME", "resource")
    resourceAttrs.put("DESC", "the resource")
    resourceAttrs.put("SUP", "top")
    resourceAttrs.put("STRUCTURAL", "true")

    val resourceMust = new BasicAttribute("MUST")
    resourceMust.add("objectclass")
    resourceMust.add(Attr.resourceId)
    resourceMust.add(Attr.resourceType)
    resourceAttrs.put(resourceMust)

    // Add the new schema object for "fooObjectClass"
    schema.createSubcontext("ClassDefinition/resourceType", resourceTypeAttrs)
    schema.createSubcontext("ClassDefinition/resource", resourceAttrs)
    schema.createSubcontext("ClassDefinition/policy", policyAttrs)
  }

  private def removePolicySchema(): Future[Unit] = withContext { ctx =>
    val schema = ctx.getSchema("")

    // Intentionally ignores errors
    Try { schema.destroySubcontext("ClassDefinition/policy") }
    Try { schema.destroySubcontext("ClassDefinition/resourceType") }
    Try { schema.destroySubcontext("ClassDefinition/resource") }
    Try { schema.destroySubcontext("AttributeDefinition/" + Attr.resourceType) }
    Try { schema.destroySubcontext("AttributeDefinition/" + Attr.resourceId) }
    Try { schema.destroySubcontext("AttributeDefinition/" + Attr.action) }
    Try { schema.destroySubcontext("AttributeDefinition/" + Attr.role) }
    Try { schema.destroySubcontext("AttributeDefinition/" + Attr.policy) }
  }

  //
  // Organizational units
  //

  private def createResourcesOrgUnit(): Future[Unit] = withContext { ctx =>
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

  private def createPeopleOrgUnit(): Future[Unit] = withContext { ctx =>
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

//      ctx.bind(peop, resourcesContext)

    } catch {
      case e: NameAlreadyBoundException => // ignore
    }
  }

  private def createGroupsOrgUnit(): Future[Unit] = withContext { ctx =>
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

  // Workbench Person

  def createWorkbenchPersonSchema(): Future[Unit] = withContext { ctx =>
    val schema = ctx.getSchema("")

    createAttributeDefinition(schema, "1.3.6.1.4.1.18060.0.4.3.2.400", Attr.petServiceAccount, "pet service account of the user", true)

    val attrs = new BasicAttributes(true) // Ignore case
    attrs.put("NUMERICOID", "1.3.6.1.4.1.18060.0.4.3.2.300")
    attrs.put("NAME", "workbenchPerson")
    attrs.put("SUP", "inetOrgPerson")
    attrs.put("STRUCTURAL", "true")

    val must = new BasicAttribute("MUST")
    must.add("objectclass")
    attrs.put(must)

    val may = new BasicAttribute("MAY")
    may.add(Attr.petServiceAccount)
    attrs.put(may)

    // Add the new schema object for "workbenchPerson"
    schema.createSubcontext("ClassDefinition/workbenchPerson", attrs)
  }

  def removeWorkbenchPersonSchema(): Future[Unit] = withContext { ctx =>
    val schema = ctx.getSchema("")

    // Intentionally ignores errors
    Try { schema.destroySubcontext("ClassDefinition/workbenchPerson") }
    Try { schema.destroySubcontext(s"AttributeDefinition/${Attr.petServiceAccount}") }
  }

  private val resourcesOu = s"ou=resources,${directoryConfig.baseDn}"

  private def createAttributeDefinition(schema: DirContext, numericOID: String, name: String, description: String, singleValue: Boolean, equality: Option[String] = None, ordering: Option[String] = None, syntax: Option[String] = None) = {
    val attributes = new BasicAttributes(true)
    attributes.put("NUMERICOID", numericOID)
    attributes.put("NAME", name)
    attributes.put("DESC", description)
    equality.foreach(attributes.put("EQUALITY", _))
    ordering.foreach(attributes.put("ORDERING", _))
    syntax.foreach(attributes.put("SYNTAX", _))
    if (singleValue) attributes.put("SINGLE-VALUE", singleValue.toString) // note absence of this attribute means multi-value and presence means single, value does not matter
    schema.createSubcontext(s"AttributeDefinition/$name", attributes)
  }

  private def withContext[T](op: InitialDirContext => T): Future[T] = withContext(directoryConfig.directoryUrl, directoryConfig.user, directoryConfig.password)(op)

}
