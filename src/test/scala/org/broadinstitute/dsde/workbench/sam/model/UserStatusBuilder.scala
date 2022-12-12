package org.broadinstitute.dsde.workbench.sam.model

import scala.collection.mutable

class UserStatusBuilder(user: SamUser) {
  private val tosAccepted = "tosAccepted"
  private val google = "google"
  private val ldap = "ldap"
  private val allUsersGroup = "allUsersGroup"
  private val adminEnabled = "adminEnabled"

  private val enabledComponents: mutable.Map[String, Boolean] = mutable.Map(
    tosAccepted -> true,
    google -> true,
    ldap -> true,
    allUsersGroup -> true,
    adminEnabled -> true
  )

  def withTosAccepted(isEnabled: Boolean): UserStatusBuilder = setComponent(tosAccepted, isEnabled)
  def withGoogle(isEnabled: Boolean): UserStatusBuilder = setComponent(google, isEnabled)
  def withLdap(isEnabled: Boolean): UserStatusBuilder = setComponent(ldap, isEnabled)
  def withAllUsersGroup(isEnabled: Boolean): UserStatusBuilder = setComponent(allUsersGroup, isEnabled)
  def withAdminEnabled(isEnabled: Boolean): UserStatusBuilder = setComponent(adminEnabled, isEnabled)

  private def setComponent(componentKey: String, isEnabled: Boolean): UserStatusBuilder = {
    enabledComponents += componentKey -> isEnabled
    this
  }

  def build: UserStatus = UserStatus(UserStatusDetails(user), enabledComponents.toMap)
}
