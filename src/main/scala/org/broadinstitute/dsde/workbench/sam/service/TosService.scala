package org.broadinstitute.dsde.workbench.sam.service

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchEmail, WorkbenchExceptionWithErrorReport, WorkbenchGroupName, WorkbenchSubject}
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, RegistrationDAO}
import org.broadinstitute.dsde.workbench.sam.errorReportSource
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.broadinstitute.dsde.workbench.sam.config.TermsOfServiceConfig

import scala.concurrent.ExecutionContext
import java.io.{FileNotFoundException, IOException}
import scala.io.Source


class TosService (val directoryDao: DirectoryDAO, val registrationDao: RegistrationDAO, val appsDomain: String, val tosConfig: TermsOfServiceConfig)(implicit val executionContext: ExecutionContext) extends LazyLogging {
  val termsOfServiceFile = "termsOfService.md"

  def resetTermsOfServiceGroupsIfNeeded(): IO[Option[BasicWorkbenchGroup]] = {

    if(tosConfig.enabled) {
      //This first clause is the special case for the first verison of ToS, where we will allow users to "pre-accept" the ToS
      //In this version, we care about the distinction of "enforced" versus "unenforced"
      //In the unenforced case, we must ensure that the Postgres group is created. This will allow users to call the "accept ToS" endpoint
      //and get added to the group. But at this point in time, the membership of the Postgres group should have no bearing on who is in the
      //enabledUsers LDAP group. So to summarize, we will create the Postgres group but do nothing to the LDAP group.
      //In the enforced case, we will now start to modify the membership of the enabledUsers LDAP group. Once enforcement is turned on,
      //we will calculate who should be a member of the enabledUsers LDAP group by querying the Postgres group for ToS Version 1, and then allowing
      //them to stay in the enabledUsers LDAP group. We will disable all human users who are not a member of the Postgres group.
      if(tosConfig.version == 1) {
        getTosGroup().flatMap {
          case None =>
            logger.info(s"creating new ToS group ${getGroupName()}")
            val groupEmail = WorkbenchEmail(s"GROUP_${getGroupName(tosConfig.version)}@$appsDomain")
            directoryDao.createGroup(BasicWorkbenchGroup(WorkbenchGroupName(getGroupName(tosConfig.version)), Set.empty, groupEmail), None, SamRequestContext(None)).flatMap { createdGroup =>
              if(tosConfig.enforced) {
                directoryDao.loadGroup(WorkbenchGroupName(getGroupName(tosConfig.version)), SamRequestContext(None)).map {
                  case Some(group) => group.members
                  case None => Set[WorkbenchSubject]() //this case shouldn't be possible
                } flatMap { preAcceptedUsers =>
                  logger.info(s"disabling all human identities, except for ${preAcceptedUsers.size} pre-accepted identities")
                  registrationDao.disableAllHumanIdentities(SamRequestContext(None), preAcceptedUsers).map { _ => Option(createdGroup) }
                }
              } else IO.pure(Option(createdGroup))

            }
          case group => IO.pure(group)
        }
      } else {
        //This else clause is considered the "standard" case. Once ToS version 2 lands, the above clause is defunct
        //and can be removed entirely to simplify the code base.
        getTosGroup().flatMap {
          case None =>
            logger.info(s"creating new ToS group ${getGroupName()}")
            val groupEmail = WorkbenchEmail(s"GROUP_${getGroupName(tosConfig.version)}@$appsDomain")
            directoryDao.createGroup(BasicWorkbenchGroup(WorkbenchGroupName(getGroupName(tosConfig.version)), Set.empty, groupEmail), None, SamRequestContext(None)).flatMap { createdGroup =>
              if(tosConfig.enforced) {
                logger.info(s"disabling all human identities. ToS version is now ${tosConfig.version}")
                registrationDao.disableAllHumanIdentities(SamRequestContext(None)).map { _ => Option(createdGroup)}
              }
              else IO.pure(Option(createdGroup))
            }
          case group => IO.pure(group)
        }
      }
    } else IO.none
  }


  def getGroupName(currentVersion:Int = tosConfig.version): String = {
    s"tos_accepted_${currentVersion}"
  }

  def getTosGroup(): IO[Option[BasicWorkbenchGroup]] = {
    directoryDao.loadGroup(WorkbenchGroupName(getGroupName()), SamRequestContext(None))
  }

  def acceptTosStatus(user: WorkbenchSubject): IO[Option[Boolean]] =
    if (tosConfig.enabled) {
      resetTermsOfServiceGroupsIfNeeded().flatMap {
        case Some(group) => directoryDao.addGroupMember(group.id, user, SamRequestContext(None)).map(Option(_))
        case None => IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"Terms of Service group ${getGroupName()} failed to create.")))
      }
    } else IO.pure(None)

  /**
    * Check if Terms of service is enabled and if the user has accepted the latest version
    * @return IO[Some(true)] if ToS is enabled and the user has accepted
    *         IO[Some(false)] if ToS is enabled and the user hasn't accepted
    *         IO[None] if ToS is disabled
    */
  def getTosStatus(user: WorkbenchSubject): IO[Option[Boolean]] = {
    if (tosConfig.enabled) {
      getTosGroup().flatMap {
        case Some(group) => directoryDao.isGroupMember(group.id, user, SamRequestContext(None)).map(Option(_))
        case None => IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"Terms of Service group ${getGroupName()} not found.")))
      }
    } else IO.none
  }

  /**
    * Get the terms of service text and send it to the caller
    * @return terms of service text
    */
  def getText: String = {
    val tosFileStream = try {
      logger.debug("Reading terms of service")
      Source.fromResource(termsOfServiceFile)
    } catch {
      case e: FileNotFoundException =>
        logger.error("Terms Of Service file not found", e)
        throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, e))
      case e: IOException =>
        logger.error("Failed to read Terms of Service fail due to IO exception", e)
        throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, e))
    }
    logger.debug("Terms of service file found")
    try {
      tosFileStream.mkString
    } finally {
      tosFileStream.close
    }
  }
}
