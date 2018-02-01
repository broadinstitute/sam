package org.broadinstitute.dsde.workbench.sam.schema

import javax.naming.{NameAlreadyBoundException, NameNotFoundException}
import javax.naming.directory._

import org.broadinstitute.dsde.workbench.sam.config.DirectoryConfig
import org.broadinstitute.dsde.workbench.sam.util.{BaseDirContext, JndiSupport}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import JndiSchemaDAO._
import org.broadinstitute.dsde.workbench.sam.directory.DirectorySubjectNameSupport

import scala.collection.JavaConverters._

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
    val member = "member"
    val memberOf = "isMemberOf"
    val givenName = "givenName"
    val sn = "sn"
    val uid = "uid"
    val project = "project"
    val proxyEmail = "proxyEmail"
  }

  object ObjectClass {
    val workbenchPerson = "workbenchPerson"
    val workbenchGroup = "workbenchGroup"
    val petServiceAccount = "petServiceAccount"
    val resourceType = "resourceType"
    val resource = "resource"
    val policy = "policy"
  }
}

class JndiSchemaDAO(protected val directoryConfig: DirectoryConfig)(implicit executionContext: ExecutionContext) extends JndiSupport with DirectorySubjectNameSupport {

  def init(): Future[Unit] = {
    for {
      _ <- destroySchema()
      _ <- createSchema()
    } yield ()
  }

  def createSchema(): Future[Unit] = {
    for {
      _ <- createWorkbenchPersonSchema()
      _ <- createWorkbenchGroupSchema()
      _ <- createOrgUnits()
      _ <- createPolicySchema()
      _ <- createWorkbenchPetServiceAccountSchema()
    } yield ()
  }

  def createOrgUnits(): Future[Unit] = {
    for {
      _ <- createOrgUnit(peopleOu)
      _ <- createOrgUnit(groupsOu)
      _ <- createOrgUnit(resourcesOu)
    } yield ()
  }

  def destroySchema(): Future[Unit] = {
    for {
      _ <- removePolicySchema()
      _ <- removeWorkbenchGroupSchema()
      _ <- removeWorkbenchPetServiceAccountSchema()
      _ <- removeWorkbenchPersonSchema()
    } yield ()
  }

  private def createWorkbenchPersonSchema(): Future[Unit] = withContext { ctx =>
    val schema = ctx.getSchema("")

    createAttributeDefinition(schema, "1.3.6.1.4.1.18060.0.4.3.2.30", Attr.proxyEmail, "proxy group email", true)

    val attrs = new BasicAttributes(true) // Ignore case
    attrs.put("NUMERICOID", "1.3.6.1.4.1.18060.0.4.3.2.300")
    attrs.put("NAME", ObjectClass.workbenchPerson)
    attrs.put("SUP", "inetOrgPerson")
    attrs.put("STRUCTURAL", "true")

    val must = new BasicAttribute("MUST")
    must.add("objectclass")
    must.add(Attr.uid)
    attrs.put(must)

    val may = new BasicAttribute("MAY")
    may.add(Attr.proxyEmail)
    attrs.put(may)

    // Add the new schema object for "fooObjectClass"
    schema.createSubcontext("ClassDefinition/" + ObjectClass.workbenchPerson, attrs)
  }

  private def createWorkbenchGroupSchema(): Future[Unit] = withContext { ctx =>
    val schema = ctx.getSchema("")

    createAttributeDefinition(schema, "1.3.6.1.4.1.18060.0.4.3.2.200", Attr.groupUpdatedTimestamp, "time when group was updated", true, Option("generalizedTimeMatch"), Option("generalizedTimeOrderingMatch"), Option("1.3.6.1.4.1.1466.115.121.1.24"))
    createAttributeDefinition(schema, "1.3.6.1.4.1.18060.0.4.3.2.201", Attr.groupSynchronizedTimestamp, "time when group was synchronized", true, Option("generalizedTimeMatch"), Option("generalizedTimeOrderingMatch"), Option("1.3.6.1.4.1.1466.115.121.1.24"))

    val attrs = new BasicAttributes(true) // Ignore case
    attrs.put("NUMERICOID", "1.3.6.1.4.1.18060.0.4.3.2.100")
    attrs.put("NAME", ObjectClass.workbenchGroup)
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
    schema.createSubcontext("ClassDefinition/" + ObjectClass.workbenchGroup, attrs)
  }

  private def removeWorkbenchPersonSchema(): Future[Unit] = withContext { ctx =>
    val schema = ctx.getSchema("")

    // Intentionally ignores errors
    Try { schema.destroySubcontext("ClassDefinition/" + ObjectClass.workbenchPerson) }
    Try { schema.destroySubcontext("AttributeDefinition/" + Attr.proxyEmail) }
  }

  private def removeWorkbenchGroupSchema(): Future[Unit] = withContext { ctx =>
    val schema = ctx.getSchema("")

    // Intentionally ignores errors
    Try { schema.destroySubcontext("ClassDefinition/" + ObjectClass.workbenchGroup) }
    Try { schema.destroySubcontext("AttributeDefinition/" + Attr.groupSynchronizedTimestamp) }
    Try { schema.destroySubcontext("AttributeDefinition/" + Attr.groupUpdatedTimestamp) }
  }

  private def createPolicySchema(): Future[Unit] = withContext { ctx =>
    val schema = ctx.getSchema("")

    createAttributeDefinition(schema, "1.3.6.1.4.1.18060.0.4.3.2.1", Attr.resourceType, "the type of the resource", true, equality = Option("caseIgnoreMatch"))
    createAttributeDefinition(schema, "1.3.6.1.4.1.18060.0.4.3.2.8", Attr.resourceId, "the id of the resource", true, equality = Option("caseIgnoreMatch"))
    createAttributeDefinition(schema, "1.3.6.1.4.1.18060.0.4.3.2.4", Attr.action, "the actions applicable to a policy", false)
    createAttributeDefinition(schema, "1.3.6.1.4.1.18060.0.4.3.2.6", Attr.role, "the roles for the policy, if any", false)
    createAttributeDefinition(schema, "1.3.6.1.4.1.18060.0.4.3.2.7", Attr.policy, "the policy name", true, equality = Option("caseIgnoreMatch"))

    val policyAttrs = new BasicAttributes(true) // Ignore case
    policyAttrs.put("NUMERICOID", "1.3.6.1.4.1.18060.0.4.3.2.0")
    policyAttrs.put("NAME", ObjectClass.policy)
    policyAttrs.put("DESC", "list subjects for a policy")
    policyAttrs.put("SUP", ObjectClass.workbenchGroup)
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
    resourceTypeAttrs.put("NAME", ObjectClass.resourceType)
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
    resourceAttrs.put("NAME", ObjectClass.resource)
    resourceAttrs.put("DESC", "the resource")
    resourceAttrs.put("SUP", "top")
    resourceAttrs.put("STRUCTURAL", "true")

    val resourceMust = new BasicAttribute("MUST")
    resourceMust.add("objectclass")
    resourceMust.add(Attr.resourceId)
    resourceMust.add(Attr.resourceType)
    resourceAttrs.put(resourceMust)

    // Add the new schema object for "fooObjectClass"
    schema.createSubcontext("ClassDefinition/" + ObjectClass.resourceType, resourceTypeAttrs)
    schema.createSubcontext("ClassDefinition/" + ObjectClass.resource, resourceAttrs)
    schema.createSubcontext("ClassDefinition/" + ObjectClass.policy, policyAttrs)
  }

  private def removePolicySchema(): Future[Unit] = withContext { ctx =>
    val schema = ctx.getSchema("")

    // Intentionally ignores errors
    Try { schema.destroySubcontext("ClassDefinition/" + ObjectClass.policy) }
    Try { schema.destroySubcontext("ClassDefinition/" + ObjectClass.resource) }
    Try { schema.destroySubcontext("ClassDefinition/" + ObjectClass.resourceType) }
    Try { schema.destroySubcontext("AttributeDefinition/" + Attr.resourceType) }
    Try { schema.destroySubcontext("AttributeDefinition/" + Attr.resourceId) }
    Try { schema.destroySubcontext("AttributeDefinition/" + Attr.action) }
    Try { schema.destroySubcontext("AttributeDefinition/" + Attr.role) }
    Try { schema.destroySubcontext("AttributeDefinition/" + Attr.policy) }
  }

  private def createOrgUnit(dn: String): Future[Unit] = withContext { ctx =>
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

      ctx.bind(dn, resourcesContext)

    } catch {
      case e: NameAlreadyBoundException => // ignore
    }
  }

  def createWorkbenchPetServiceAccountSchema(): Future[Unit] = withContext { ctx =>
    val schema = ctx.getSchema("")

    createAttributeDefinition(schema, "1.3.6.1.4.1.18060.0.4.3.2.70", Attr.project, "google project", true, equality = Option("caseIgnoreMatch"))

    val attrs = new BasicAttributes(true) // Ignore case
    attrs.put("NUMERICOID", "1.3.6.1.4.1.18060.0.4.3.2.700")
    attrs.put("NAME", ObjectClass.petServiceAccount)
    attrs.put("SUP", "inetOrgPerson")
    attrs.put("STRUCTURAL", "true")

    val must = new BasicAttribute("MUST")
    must.add("objectclass")
    must.add(Attr.project)
    must.add(Attr.uid)
    attrs.put(must)

    // Add the new schema object for "petServiceAccount"
    schema.createSubcontext("ClassDefinition/" + ObjectClass.petServiceAccount, attrs)
  }

  def removeWorkbenchPetServiceAccountSchema(): Future[Unit] = withContext { ctx =>
    val schema = ctx.getSchema("")

    // Intentionally ignores errors
    Try { schema.destroySubcontext("ClassDefinition/" + ObjectClass.petServiceAccount) }
    Try { schema.destroySubcontext("AttributeDefinition/" + Attr.project) }
  }

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

  def clearDatabase(): Future[Unit] = withContext { ctx =>
    clear(ctx, resourcesOu)
    clear(ctx, groupsOu)
    clear(ctx, peopleOu)
  }

  private def clear(ctx: DirContext, dn: String): Unit = Try {
    ctx.list(dn).asScala.foreach { nameClassPair =>
      val fullName = if (nameClassPair.isRelative) s"${nameClassPair.getName},$dn" else nameClassPair.getName
      clear(ctx, fullName)
    }
    ctx.unbind(dn)
  } recover {
    case _: NameNotFoundException =>
  }
}
