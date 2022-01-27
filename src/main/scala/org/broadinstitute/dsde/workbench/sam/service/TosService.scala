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

//  private def createTosGroupIfNeeded: IO[Option[BasicWorkbenchGroup]] = {
//    val currentGroupEmail = WorkbenchEmail(s"GROUP_${getGroupName(tosConfig.version)}@$appsDomain")
//    getTosGroup() flatMap {
//      case Some(group) => IO.none
//      case None => directoryDao.createGroup(BasicWorkbenchGroup(WorkbenchGroupName(getGroupName(tosConfig.version)), Set.empty, currentGroupEmail), None, SamRequestContext(None)).map { Option(_) }
//    }
//  }
//
//  private def disableAllHumanIdentities: IO[Unit] = {
//    if(!tosConfig.allowPreaccept) registrationDao.disableAllHumanIdentities(SamRequestContext(None))
//    else IO.unit
//  }
//
//  private def disableAllHumanIdentitiesUnlessPreaccepted: IO[Unit] = {
//    if(tosConfig.allowPreaccept) directoryDao.loadGroup(WorkbenchGroupName(getGroupName(tosConfig.version)), SamRequestContext(None)).map {
//      case Some(group) =>
//        val x = group.members.map(subjectDn)
//        registrationDao.disableAllHumanIdentities(SamRequestContext(None), x)
//      case None => IO.unit //this case shouldn't be possible
//    } else IO.unit
//  }

  def resetTermsOfServiceGroupsIfNeeded(): IO[Option[BasicWorkbenchGroup]] = {

    if(tosConfig.enabled) {
      if(tosConfig.version == 1) {
        getTosGroup().flatMap {
          case None =>
            logger.info(s"creating new ToS group ${getGroupName()}")
            val groupEmail = WorkbenchEmail(s"GROUP_${getGroupName(tosConfig.version)}@$appsDomain")
            directoryDao.createGroup(BasicWorkbenchGroup(WorkbenchGroupName(getGroupName(tosConfig.version)), Set.empty, groupEmail), None, SamRequestContext(None)).flatMap { createdGroup =>
              if(tosConfig.enforced) {
                directoryDao.loadGroup(WorkbenchGroupName(getGroupName(tosConfig.version)), SamRequestContext(None)).map {
                  case Some(group) => group.members
                  case None => Set[WorkbenchSubject]()
                } flatMap { exemptedUsers =>
                  registrationDao.disableAllHumanIdentities(SamRequestContext(None), exemptedUsers).map { _ => Option(createdGroup) }
                }
              } else IO.pure(Option(createdGroup))

            }
          case group => IO.pure(group)
        }
      } else {
        getTosGroup().flatMap {
          case None =>
            logger.info(s"creating new ToS group ${getGroupName()}")
            val groupEmail = WorkbenchEmail(s"GROUP_${getGroupName(tosConfig.version)}@$appsDomain")
            directoryDao.createGroup(BasicWorkbenchGroup(WorkbenchGroupName(getGroupName(tosConfig.version)), Set.empty, groupEmail), None, SamRequestContext(None)).flatMap { createdGroup =>
              if(tosConfig.enforced) registrationDao.disableAllHumanIdentities(SamRequestContext(None)).map { _ => Option(createdGroup)}
              else IO.pure(Option(createdGroup))
            }
          case group => IO.pure(group)
        }
      }
    } else IO.none





//    if(tosConfig.enabled) {
//      if(!tosConfig.enforced && tosConfig.version == 1) {
//        getTosGroup().flatMap {
//          case None =>
//            logger.info(s"creating new ToS group ${getGroupName()}")
//            val groupEmail = WorkbenchEmail(s"GROUP_${getGroupName(tosConfig.version)}@$appsDomain")
//            directoryDao.createGroup(BasicWorkbenchGroup(WorkbenchGroupName(getGroupName(tosConfig.version)), Set.empty, groupEmail), None, SamRequestContext(None)).map { x => Option(x) }
//          case group => IO.pure(group)
//        }
//      } else if(tosConfig.enforced && tosConfig.version == 1) {
//          getTosGroup().flatMap {
//            case None =>
//              //this case can be merged with the above identical case
//              logger.info(s"creating new ToS group ${getGroupName()}")
//              val groupEmail = WorkbenchEmail(s"GROUP_${getGroupName(tosConfig.version)}@$appsDomain")
//              directoryDao.createGroup(BasicWorkbenchGroup(WorkbenchGroupName(getGroupName(tosConfig.version)), Set.empty, groupEmail), None, SamRequestContext(None)).map { x => Option(x) }
//            case group => IO.pure(group)
//          }.flatMap { createdGroup =>
//            directoryDao.loadGroup(WorkbenchGroupName(getGroupName(tosConfig.version)), SamRequestContext(None)).map{case Some(group) => group.members.map(subjectDn)}.flatMap { exemptedUsers =>
//              registrationDao.disableAllHumanIdentities(SamRequestContext(None), exemptedUsers).map { _ => createdGroup }
//            }
//          }
//        } else {
//          getTosGroup().flatMap {
//            case None =>
//              logger.info(s"creating new ToS group ${getGroupName()}")
//              val groupEmail = WorkbenchEmail(s"GROUP_${getGroupName(tosConfig.version)}@$appsDomain")
//              directoryDao.createGroup(BasicWorkbenchGroup(WorkbenchGroupName(getGroupName(tosConfig.version)), Set.empty, groupEmail), None, SamRequestContext(None)).flatMap { createdGroup =>
//                if(tosConfig.enforced) registrationDao.disableAllHumanIdentities(SamRequestContext(None)).map { _ => Option(createdGroup)}
//                else IO.pure(Option(createdGroup))
//              }
//            case group => IO.pure(group)
//          }
//      }
//    } else IO.none


//
//    if(tosConfig.enabled) {
//      val currentGroupEmail = WorkbenchEmail(s"GROUP_${getGroupName(tosConfig.version)}@$appsDomain")
//      getTosGroup() flatMap {
//        case Some(group) => IO.none
//        case None if !tosConfig.enforced && tosConfig.version == 1 => {
//          directoryDao.createGroup(BasicWorkbenchGroup(WorkbenchGroupName(getGroupName(tosConfig.version)), Set.empty, currentGroupEmail), None, SamRequestContext(None)).map { Option(_) }
//        }
//        case None if tosConfig.enforced && tosConfig.version == 1 => {
//          directoryDao.createGroup(BasicWorkbenchGroup(WorkbenchGroupName(getGroupName(tosConfig.version)), Set.empty, currentGroupEmail), None, SamRequestContext(None)).map { Option(_) }
//        }
//        case None if tosConfig.version != 1 => {
//          directoryDao.createGroup(BasicWorkbenchGroup(WorkbenchGroupName(getGroupName(tosConfig.version)), Set.empty, currentGroupEmail), None, SamRequestContext(None)).flatMap { createdGroup =>
//            if(tosConfig.enforced) registrationDao.disableAllHumanIdentities(SamRequestContext(None)).map { _ => Option(createdGroup)}
//            else IO.pure(Option(createdGroup))
//          }
//        }
//      }
//    } else IO.none




//    if(tosConfig.enabled) {
//      getTosGroup().flatMap {
//        case None =>
//          logger.info(s"creating new ToS group ${getGroupName()}")
//          val groupEmail = WorkbenchEmail(s"GROUP_${getGroupName(tosConfig.version)}@$appsDomain")
//          directoryDao.createGroup(BasicWorkbenchGroup(WorkbenchGroupName(getGroupName(tosConfig.version)), Set.empty, groupEmail), None, SamRequestContext(None)).flatMap { createdGroup =>
//            if(tosConfig.enforced) registrationDao.disableAllHumanIdentities(SamRequestContext(None)).map { _ => Option(createdGroup)}
//            else IO.pure(Option(createdGroup))
//          }
//        case group => IO.pure(group)
//      }
//    } else IO.none
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
