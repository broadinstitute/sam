package org.broadinstitute.dsde.workbench.sam.model

import org.broadinstitute.dsde.workbench.model.{WorkbenchUser, WorkbenchEmail, WorkbenchUserId}

/**
  * Created by dvoet on 6/5/17.
  */
case class UserInfo2(accessToken: String, userId: WorkbenchUserId, userEmail: WorkbenchEmail, tokenExpiresIn: Long) {
  def toWorkbenchUser = WorkbenchUser(userId, userEmail)
}
