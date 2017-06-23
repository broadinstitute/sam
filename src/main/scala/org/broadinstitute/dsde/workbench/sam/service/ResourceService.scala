package org.broadinstitute.dsde.workbench.sam.service

import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.sam.WorkbenchException
import org.broadinstitute.dsde.workbench.sam.directory.JndiDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.HttpOpenAmDAO

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by mbemis on 5/22/17.
  */
class ResourceService(val openAmDAO: HttpOpenAmDAO, val directoryDAO: JndiDirectoryDAO)(implicit val executionContext: ExecutionContext) extends LazyLogging {

  def listResourceTypes(userInfo: UserInfo): Future[Set[OpenAmResourceType]] = {
    openAmDAO.listResourceTypes(userInfo)
  }

  def createResourceType(resourceType: ResourceType, userInfo: UserInfo): Future[OpenAmResourceType] = {
    val pattern = resourceUrn(resourceType, "*")
    openAmDAO.createResourceType(resourceType, pattern, userInfo)
  }

  def updateResourceType(updatedResourceType: OpenAmResourceType, userInfo: UserInfo): Future[OpenAmResourceType] = {
    openAmDAO.updateResourceType(updatedResourceType, userInfo)
  }

  def getDefaultPolicySet(userInfo: UserInfo): Future[OpenAmPolicySet] = {
    openAmDAO.getDefaultPolicySet(userInfo)
  }

  def updateDefaultPolicySet(updatedPolicySet: OpenAmPolicySet, userInfo: UserInfo): Future[OpenAmPolicySet] = {
    openAmDAO.updateDefaultPolicySet(updatedPolicySet, userInfo)
  }

  def hasPermission(resourceType: ResourceType, resourceId: String, action: String, userInfo: UserInfo): Future[Boolean] = {
    val urn = resourceUrn(resourceType, resourceId)
    openAmDAO.evaluatePolicy(Set(urn), userInfo).map { policyEval =>
      val actionsMap = policyEval.map(p => p.resource -> p.actions).toMap
      actionsMap.getOrElse(urn, throw new WorkbenchException(s"resource ${resourceUrn(resourceType, resourceId)} not found")).getOrElse(action, false)
    }
  }

  def createResource(resourceType: ResourceType, resourceId: String, userInfo: UserInfo): Future[Set[OpenAmPolicy]] = {
    Future.traverse(resourceType.roles) { role =>
      val roleMembers: Set[SamSubject] = role.roleName match {
        case resourceType.ownerRoleName => Set(userInfo.userId)
        case _ => Set.empty
      }
      for {
        group <- directoryDAO.createGroup(SamGroup(SamGroupName(s"${resourceType.name}-${resourceId}-${role.roleName}"), roleMembers))
        adminUserInfo <- getOpenAmAdminUserInfo
        policy <- openAmDAO.createPolicy(
          group.name.value,
          s"policy for ${group.name.value}",
          role.actions.map(_.actionName).toSeq,
          Seq(resourceUrn(resourceType, resourceId)),
          Seq(group.name),
          resourceType.uuid.getOrElse(throw new WorkbenchException("resource type uuid not set")),
          adminUserInfo
        )
      } yield policy
    }
  }

  def syncResourceTypes(configResourceTypes: Set[ResourceType], userInfo: UserInfo): Future[Set[ResourceType]] = {
    listResourceTypes(userInfo).flatMap { existingResourceTypes =>
      val configActionsByName: Map[String, Set[String]] = configResourceTypes.map(rt => rt.name -> rt.actions).toMap
      val openamActionsByName: Map[String, Set[String]] = existingResourceTypes.map(rt => rt.name -> rt.actions.keySet).toMap

      val diff = (configActionsByName.toSet diff openamActionsByName.toSet).toMap
      val newTypes = diff -- openamActionsByName.keySet
      val updatedTypes = diff -- newTypes.keySet
      val orphanTypes = (openamActionsByName.toSet diff configActionsByName.toSet).map(_._1)

      if(orphanTypes.nonEmpty) {
        logger.warn(s"WARNING: the following types exist in OpenAM but were not specified in config: ${orphanTypes.mkString(", ")}")
      }

      val newResourceTypes = configResourceTypes.filter(rt => newTypes.keySet.contains(rt.name))
      val updatedResourceTypes = existingResourceTypes.filter(rt => updatedTypes.keySet.contains(rt.name)).map(x => x.copy(actions = configActionsByName(x.name).map(_ -> false).toMap))

      for {
        created <- Future.traverse(newResourceTypes)(createResourceType(_, userInfo))
        _ <- Future.traverse(updatedResourceTypes)(updateResourceType(_, userInfo))
        defaultPolicySet <- getDefaultPolicySet(userInfo)
        _ <- updateDefaultPolicySet(defaultPolicySet.copy(resourceTypeUuids = (existingResourceTypes ++ created).map(_.uuid)), userInfo)
      } yield {
        val uuidByName = (existingResourceTypes ++ created).map(rt => rt.name -> rt.uuid).toMap
        configResourceTypes.map(rt => rt.copy(uuid = Option(uuidByName(rt.name))))
      }
    }
  }

  private def resourceUrn(resourceType: ResourceType, resourceId: String) = {
    s"${resourceType.name}://$resourceId"
  }

  def getOpenAmAdminUserInfo: Future[UserInfo] = {
    openAmDAO.getAdminUserInfo
  }

}
