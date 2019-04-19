package org.broadinstitute.dsde.test.api

import org.broadinstitute.dsde.test.util.AuthDomainMatcher
import org.broadinstitute.dsde.workbench.auth.AuthToken
import org.broadinstitute.dsde.workbench.config.{Credentials, UserPool}
import org.broadinstitute.dsde.workbench.fixture.{BillingFixtures, GroupFixtures, WorkspaceFixtures}
import org.broadinstitute.dsde.workbench.service.BillingProject.BillingProjectRole
import org.broadinstitute.dsde.workbench.service.Orchestration.groups.GroupRole
import org.broadinstitute.dsde.workbench.service.{Rawls, RestException, Sam}
import org.scalatest.{FreeSpec, Matchers}

import scala.util.Try

class GroupApiSpec extends FreeSpec with WorkspaceFixtures with BillingFixtures with GroupFixtures with Matchers {

  /*
   * Unless otherwise declared, this auth token will be used for API calls.
   * We are using a curator to prevent collisions with users in tests (who are Students and AuthDomainUsers), not
   *  because we specifically need a curator.
   */

  val defaultUser: Credentials = UserPool.chooseCurator
  val authTokenDefault: AuthToken = defaultUser.makeAuthToken()


  "removing permissions from billing project owners for workspaces with auth domains" - {

    "+ project owner, + group member, create workspace, - group member" in {
      val owner = UserPool.chooseProjectOwner
      val creator = UserPool.chooseStudent
      val user = owner
      implicit val token: AuthToken = creator.makeAuthToken()

      withCleanBillingProject(owner, List(creator.email)) { projectName =>
        withGroup("group", List(user.email)) { groupName =>
          withWorkspace(projectName, "GroupApiSpec_workspace", Set(groupName)) { workspaceName =>
            AuthDomainMatcher.checkVisibleAndAccessible(user, projectName, workspaceName, List(groupName))
            Sam.user.removeUserFromPolicy(groupName, GroupRole.Member.toString, user.email)
            AuthDomainMatcher.checkVisibleNotAccessible(user, projectName, workspaceName)
          }
        }
      }
    }

    "+ project owner, + group member, create workspace, - project owner" in {
      val owner = UserPool.chooseProjectOwner
      val creator = owner
      val user = UserPool.chooseStudent
      implicit val token: AuthToken = creator.makeAuthToken()

      withCleanBillingProject(creator, ownerEmails = List(user.email)) { projectName =>
        withGroup("group", List(user.email)) { groupName =>
          withWorkspace(projectName, "GroupApiSpec_workspace", Set(groupName)) { workspaceName =>
            AuthDomainMatcher.checkVisibleAndAccessible(user, projectName, workspaceName, List(groupName))

            Rawls.billing.removeUserFromBillingProject(projectName, user.email, BillingProjectRole.Owner)
            AuthDomainMatcher.checkNotVisibleNotAccessible(user, projectName, workspaceName)
          }
        }
      }
    }

    "+ project owner, create workspace, + group member, - group member" in {
      val owner = UserPool.chooseProjectOwner
      val creator = UserPool.chooseStudent
      val user = owner
      implicit val token: AuthToken = creator.makeAuthToken()

      withCleanBillingProject(owner, List(creator.email)) { projectName =>
        withGroup("group") { groupName =>
          withWorkspace(projectName, "GroupApiSpec_workspace", Set(groupName)) { workspaceName =>

            AuthDomainMatcher.checkVisibleNotAccessible(user, projectName, workspaceName)

            Sam.user.addUserToPolicy(groupName, GroupRole.Member.toString, user.email)
            register cleanUp Try(Sam.user.removeUserFromPolicy(groupName, GroupRole.Member.toString, user.email)).recover {
              case _: RestException =>
            }
            AuthDomainMatcher.checkVisibleAndAccessible(user, projectName, workspaceName, List(groupName))

            Sam.user.removeUserFromPolicy(groupName, GroupRole.Member.toString, user.email)
            AuthDomainMatcher.checkVisibleNotAccessible(user, projectName, workspaceName)
          }
        }
      }
    }

    "+ project owner, create workspace, + group member, - project owner" in {
      val owner = UserPool.chooseProjectOwner
      val creator = owner
      val user = UserPool.chooseStudent
      implicit val token: AuthToken = creator.makeAuthToken()

      withCleanBillingProject(creator, ownerEmails = List(user.email)) { projectName =>
        withGroup("group") { groupName =>
          withWorkspace(projectName, "GroupApiSpec_workspace", Set(groupName)) { workspaceName =>

            AuthDomainMatcher.checkVisibleNotAccessible(user, projectName, workspaceName)

            Sam.user.addUserToPolicy(groupName, GroupRole.Member.toString, user.email)
            register cleanUp Try(Sam.user.removeUserFromPolicy(groupName, GroupRole.Member.toString, user.email)).recover {
              case _: RestException =>
            }
            AuthDomainMatcher.checkVisibleAndAccessible(user, projectName, workspaceName, List(groupName))

            Rawls.billing.removeUserFromBillingProject(projectName, user.email, BillingProjectRole.Owner)
            AuthDomainMatcher.checkNotVisibleNotAccessible(user, projectName, workspaceName)
          }
        }
      }
    }

    "+ group member, create workspace, + project owner, - group member" in {
      val owner = UserPool.chooseProjectOwner
      val creator = owner
      val user = UserPool.chooseStudent
      implicit val token: AuthToken = creator.makeAuthToken()

      withCleanBillingProject(creator) { projectName =>
        withGroup("group", List(user.email)) { groupName =>
          withCleanUp {
            withWorkspace(projectName, "GroupApiSpec_workspace", Set(groupName)) { workspaceName =>

              AuthDomainMatcher.checkNotVisibleNotAccessible(user, projectName, workspaceName)

              Rawls.billing.addUserToBillingProject(projectName, user.email, BillingProjectRole.Owner)
              register cleanUp Rawls.billing.removeUserFromBillingProject(projectName, user.email, BillingProjectRole.Owner)
              AuthDomainMatcher.checkVisibleAndAccessible(user, projectName, workspaceName, List(groupName))

              Sam.user.removeUserFromPolicy(groupName, GroupRole.Member.toString, user.email)
              AuthDomainMatcher.checkVisibleNotAccessible(user, projectName, workspaceName)
            }
          }
        }
      }
    }

    "+ group member, create workspace, + project owner, - project owner" in {
      val owner = UserPool.chooseProjectOwner
      val creator = owner
      val user = UserPool.chooseStudent
      implicit val token: AuthToken = creator.makeAuthToken()

      withCleanBillingProject(creator) { projectName =>
        withGroup("group", List(user.email)) { groupName =>
          withCleanUp {
            withWorkspace(projectName, "GroupApiSpec_workspace", Set(groupName)) { workspaceName =>

              AuthDomainMatcher.checkNotVisibleNotAccessible(user, projectName, workspaceName)

              Rawls.billing.addUserToBillingProject(projectName, user.email, BillingProjectRole.Owner)
              register cleanUp Try(Rawls.billing.removeUserFromBillingProject(projectName, user.email, BillingProjectRole.Owner)).recover {
                case _: RestException =>
              }
              AuthDomainMatcher.checkVisibleAndAccessible(user, projectName, workspaceName, List(groupName))

              Rawls.billing.removeUserFromBillingProject(projectName, user.email, BillingProjectRole.Owner)
              AuthDomainMatcher.checkNotVisibleNotAccessible(user, projectName, workspaceName)
            }
          }
        }
      }
    }

    "create workspace, + project owner, + group member, - group member" in {
      val owner = UserPool.chooseProjectOwner
      val creator = owner
      val user = UserPool.chooseStudent
      implicit val token: AuthToken = creator.makeAuthToken()

      withCleanBillingProject(creator, ownerEmails = List(user.email)) { projectName =>
        withGroup("group") { groupName =>
          withCleanUp {
            withWorkspace(projectName, "GroupApiSpec_workspace", Set(groupName)) { workspaceName =>

              AuthDomainMatcher.checkVisibleNotAccessible(user, projectName, workspaceName)

              Sam.user.addUserToPolicy(groupName, GroupRole.Member.toString, user.email)
              register cleanUp Try(Sam.user.removeUserFromPolicy(groupName, GroupRole.Member.toString, user.email)).recover {
                case _: RestException =>
              }
              AuthDomainMatcher.checkVisibleAndAccessible(user, projectName, workspaceName, List(groupName))

              Sam.user.removeUserFromPolicy(groupName, GroupRole.Member.toString, user.email)
              AuthDomainMatcher.checkVisibleNotAccessible(user, projectName, workspaceName)
            }
          }
        }
      }
    }

    "create workspace, + project owner, + group member, - project owner" in {
      val owner = UserPool.chooseProjectOwner
      val creator = owner
      val user = UserPool.chooseStudent
      implicit val token: AuthToken = creator.makeAuthToken()

      withCleanBillingProject(creator, ownerEmails = List(user.email)) { projectName =>
        withGroup("group") { groupName =>
          withWorkspace(projectName, "GroupApiSpec_workspace", Set(groupName)) { workspaceName =>

            AuthDomainMatcher.checkVisibleNotAccessible(user, projectName, workspaceName)

            Sam.user.addUserToPolicy(groupName, GroupRole.Member.toString, user.email)
            register cleanUp Try(Sam.user.removeUserFromPolicy(groupName, GroupRole.Member.toString, user.email)).recover {
              case _: RestException =>
            }
            AuthDomainMatcher.checkVisibleAndAccessible(user, projectName, workspaceName, List(groupName))

            Rawls.billing.removeUserFromBillingProject(projectName, user.email, BillingProjectRole.Owner)
            AuthDomainMatcher.checkNotVisibleNotAccessible(user, projectName, workspaceName)
          }
        }
      }
    }

    "create workspace, + group member, + project owner, - group member" in {
      val owner = UserPool.chooseProjectOwner
      val creator = owner
      val user = UserPool.chooseStudent
      implicit val token: AuthToken = creator.makeAuthToken()

      withCleanBillingProject(creator) { projectName =>
        withGroup("group", memberEmails = List(user.email)) { groupName =>
          withCleanUp {
            withWorkspace(projectName, "GroupApiSpec_workspace", Set(groupName)) { workspaceName =>
              AuthDomainMatcher.checkNotVisibleNotAccessible(user, projectName, workspaceName)

              Rawls.billing.addUserToBillingProject(projectName, user.email, BillingProjectRole.Owner)
              register cleanUp Try(Rawls.billing.removeUserFromBillingProject(projectName, user.email, BillingProjectRole.Owner)).recover {
                case _: RestException =>
              }
              AuthDomainMatcher.checkVisibleAndAccessible(user, projectName, workspaceName, List(groupName))

              Sam.user.removeUserFromPolicy(groupName, GroupRole.Member.toString, user.email)
              AuthDomainMatcher.checkVisibleNotAccessible(user, projectName, workspaceName)
            }
          }
        }
      }
    }

    "create workspace, + group member, + project owner, - project owner" in {
      val owner = UserPool.chooseProjectOwner
      val creator = owner
      val user = UserPool.chooseStudent
      implicit val token: AuthToken = creator.makeAuthToken()

      withCleanBillingProject(creator) { projectName =>
        withGroup("group", memberEmails = List(user.email)) { groupName =>
          withCleanUp {
            withWorkspace(projectName, "GroupApiSpec_workspace", Set(groupName)) { workspaceName =>
              AuthDomainMatcher.checkNotVisibleNotAccessible(user, projectName, workspaceName)

              Rawls.billing.addUserToBillingProject(projectName, user.email, BillingProjectRole.Owner)
              register cleanUp Try(Rawls.billing.removeUserFromBillingProject(projectName, user.email, BillingProjectRole.Owner)).recover {
                case _: RestException =>
              }
              AuthDomainMatcher.checkVisibleAndAccessible(user, projectName, workspaceName, List(groupName))

              Rawls.billing.removeUserFromBillingProject(projectName, user.email, BillingProjectRole.Owner)
              AuthDomainMatcher.checkNotVisibleNotAccessible(user, projectName, workspaceName)
            }
          }
        }
      }
    }

  }

}
