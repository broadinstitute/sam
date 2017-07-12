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
    val role = "role"
  }

  def init(): Future[Unit] = {
    for {
      _ <- withContext { createOrgUnit(_, policiesOu) }
    } yield ()
  }

  override def createPolicy(policy: AccessPolicy): Future[AccessPolicy] = withContext { ctx =>
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
        policy.role.foreach(role => myAttrs.put(Attr.role, role.value))

        myAttrs
      }
    }

    ctx.bind(policyDn(policy), policyContext)
    policy
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
        dnToSubject(searchResult.getAttributes.get(Attr.subject).get().toString),
        Option(searchResult.getAttributes.get(Attr.role)).map(attr => ResourceRoleName(attr.get().toString))
      )
    }

    policies
  }

  override def deletePolicy(policyId: AccessPolicyId): Future[Unit] = withContext { ctx =>
    ctx.unbind(policyDn(policyId))
  }

  private def withContext[T](op: InitialDirContext => T): Future[T] = withContext(directoryConfig.directoryUrl, directoryConfig.user, directoryConfig.password)(op)
}
