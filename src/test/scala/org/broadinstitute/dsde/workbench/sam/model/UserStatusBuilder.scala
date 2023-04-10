package org.broadinstitute.dsde.workbench.sam.model

import scala.collection.mutable

case class UserStatusBuilder(user: SamUser) {
  private val tosAccepted = "tosAccepted"
  private val google = "google"
  private val ldap = "ldap"
  private val allUsersGroup = "allUsersGroup"
  private val adminEnabled = "adminEnabled"

  private val componentStatuses: mutable.Map[String, Boolean] = mutable.Map(
    tosAccepted -> true,
    google -> true,
    ldap -> true,
    allUsersGroup -> true,
    adminEnabled -> true
  )

  def withTosAccepted(isEnabled: Boolean): UserStatusBuilder = setComponentStatus(tosAccepted, isEnabled)
  def withGoogle(isEnabled: Boolean): UserStatusBuilder = setComponentStatus(google, isEnabled)
  def withLdap(isEnabled: Boolean): UserStatusBuilder = setComponentStatus(ldap, isEnabled)
  def withAllUsersGroup(isEnabled: Boolean): UserStatusBuilder = setComponentStatus(allUsersGroup, isEnabled)
  def withAdminEnabled(isEnabled: Boolean): UserStatusBuilder = setComponentStatus(adminEnabled, isEnabled)

  private def setComponentStatus(componentKey: String, isEnabled: Boolean): UserStatusBuilder = {
    componentStatuses += componentKey -> isEnabled
    this
  }

  def build: UserStatus = UserStatus(UserStatusDetails(user), componentStatuses.toMap)
}
