package org.broadinstitute.dsde.workbench.sam.model

import org.broadinstitute.dsde.workbench.model.{WorkbenchUserEmail, WorkbenchUserId}

/**
  * Created by dvoet on 6/5/17.
  */
case class UserInfo(accessToken: String, userId: WorkbenchUserId, userEmail: WorkbenchUserEmail, tokenExpiresIn: Long)
