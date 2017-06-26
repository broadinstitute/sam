package org.broadinstitute.dsde.workbench.sam.openam

import org.broadinstitute.dsde.workbench.sam.model._

import scala.concurrent.Future

/**
  * Created by mbemis on 6/23/17.
  */
trait OpenAmDAO {
  def getAdminUserInfo: Future[UserInfo]
  def getAuthToken(username: String, password: String): Future[AuthenticateResponse]

  def listResourceTypes(userInfo: UserInfo): Future[Set[OpenAmResourceType]]
  def createResourceType(resourceType: ResourceType, pattern: String, userInfo: UserInfo): Future[OpenAmResourceType]
  def updateResourceType(updatedResourceType: OpenAmResourceType, userInfo: UserInfo): Future[OpenAmResourceType]

  def getDefaultPolicySet(userInfo: UserInfo): Future[OpenAmPolicySet]
  def updateDefaultPolicySet(updatedPolicySet: OpenAmPolicySet, userInfo: UserInfo): Future[OpenAmPolicySet]

  def createPolicy(name: String, description: String, actions: Seq[String], resources: Seq[String], subjects: Seq[SamSubject], resourceType: String, userInfo: UserInfo): Future[OpenAmPolicy]
  def evaluatePolicy(urns: Set[String], userInfo: UserInfo): Future[List[OpenAmPolicyEvaluation]]
}
