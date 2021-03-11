package org.broadinstitute.dsde.workbench.sam.microsoft

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import com.microsoft.graph.auth.confidentialClient.ClientCredentialProvider
import com.microsoft.graph.auth.enums.NationalCloud
import com.microsoft.graph.http.GraphServiceException
import com.microsoft.graph.logger.LoggerLevel
import com.microsoft.graph.models.extensions.{DirectoryObject, Group}
import com.microsoft.graph.requests.extensions.{GraphServiceClient, IDirectoryObjectCollectionWithReferencesPage}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.config.AzureConfig
import org.broadinstitute.dsde.workbench.sam.errorReportSource

import scala.jdk.CollectionConverters._

class AzureActiveDirectoryDAO(config: AzureConfig) {
  private val ODATA_USER = "#microsoft.graph.user"
  private val ODATA_GROUP = "#microsoft.graph.group"

  val authProvider = new ClientCredentialProvider(
    config.clientId,
    List("https://graph.microsoft.com/.default").asJava,
    config.clientSecret,
    config.tenant,
    NationalCloud.Global
  )

  val graphClient = GraphServiceClient.builder().authenticationProvider(authProvider).buildClient();

  graphClient.getLogger.setLoggingLevel(LoggerLevel.DEBUG)

  def createGroup(displayName: String, groupEmail: WorkbenchEmail): IO[Unit] = {
    maybeGetGroupId(groupEmail).flatMap {
      case None => createGroupInternal(displayName, groupEmail).void
      case Some(_) => IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"group $groupEmail already exists")))
    }
  }

  def deleteGroup(groupEmail: WorkbenchEmail): IO[Unit] = {
    maybeGetGroupId(groupEmail).flatMap {
      case None => IO.unit
      case Some(groupId) => deleteGroupInternal(groupId)
    }

  }

  private def createGroupInternal(displayName: String, groupEmail: WorkbenchEmail): IO[Group] = IO {
    val group = new Group()
    group.displayName = groupEmail.value
    group.mailEnabled = false
    group.mailNickname = displayName
    group.securityEnabled = true

    graphClient.groups().buildRequest().post(group)
  }

  private def deleteGroupInternal(groupId: AzureGroupId): IO[Unit] = IO {
    graphClient.groups(groupId.value).buildRequest().delete()
  }

  def maybeGetGroupId(groupEmail: WorkbenchEmail): IO[Option[AzureGroupId]] = {
    for {
      request <- IO(graphClient.groups().buildRequest().filter(s"displayName eq '${groupEmail.value}'").select("id").get)
      groups = request.getCurrentPage.asScala.toList
    } yield {
      groups match {
        case Nil => None
        case group :: Nil => Option(AzureGroupId(group.id))
        case _ => throw new WorkbenchException(s"too many groups with display name $groupEmail")
      }
    }
  }

  def getUserId(userEmail: WorkbenchEmail): IO[Option[AzureUserId]] = IO {
    graphClient.users(userEmail.value)
      .buildRequest()
      .select("id")
      .get().id;
  }.map(AzureUserId).map(Option.apply).handleErrorWith {
    case gse: GraphServiceException if gse.getResponseCode == StatusCodes.NotFound.intValue => IO.pure(None)
  }

  def addMemberToGroup(groupEmail: WorkbenchEmail, memberEmail: WorkbenchEmail): IO[Unit] = {
    for {
      groupId <- getGroupId(groupEmail)
      memberId <- determineAzureId(memberEmail)
      _ <- addMemberToGroupInternal(groupId, memberId)
    } yield ()
  }

  private def getGroupId(groupEmail: WorkbenchEmail): IO[AzureGroupId] = {
    maybeGetGroupId(groupEmail).map(_.getOrElse(throw new WorkbenchException(s"group $groupEmail does not exist")))
  }

  private def determineAzureId(email: WorkbenchEmail): IO[String] = {
    for {
      maybeUserId <- getUserId(email)
      azureId <- maybeUserId match {
        case None => maybeGetGroupId(email).map {
          case Some(AzureGroupId(id)) => id
          case None => throw new WorkbenchException(s"member $email does not exist as a user or group")
        }
        case Some(AzureUserId(id)) => IO.pure(id)
      }
    } yield azureId
  }

  private def addMemberToGroupInternal(groupId: AzureGroupId, memberId: String): IO[Unit] = IO {
    val directoryObject = new DirectoryObject();
    directoryObject.id = s"$memberId";

    graphClient.groups(groupId.value).members().references()
      .buildRequest()
      .post(directoryObject);
  }

  def removeMemberFromGroup(groupEmail: WorkbenchEmail, memberEmail: WorkbenchEmail): IO[Unit] = {
    for {
      groupId <- getGroupId(groupEmail)
      memberId <- determineAzureId(memberEmail)
      _ <- removeMemberFromGroupInternal(groupId, memberId)
    } yield ()
  }

  private def removeMemberFromGroupInternal(groupId: AzureGroupId, memberId: String): IO[Unit] = IO {
    graphClient.groups(groupId.value).members(memberId).reference()
      .buildRequest()
      .delete();
  }

  def listGroupMembers(groupEmail: WorkbenchEmail): IO[Option[Seq[String]]] = {
    for {
      maybeGroupId <- maybeGetGroupId(groupEmail)
      memberDirectoryObjects <- maybeGroupId match {
        case None => IO.pure(Seq.empty[DirectoryObject])
        case Some(groupId) => listGroupMembersInternal(groupId)
      }
    } yield {
      maybeGroupId match {
        case None => None
        case Some(_) => Option(memberDirectoryObjects.map { o =>
          o.oDataType match {
            case ODATA_GROUP if !o.getRawObject.get("displayName").isJsonNull =>
              o.getRawObject.get("displayName").getAsString
            case ODATA_USER if !o.getRawObject.get("mail").isJsonNull =>
              o.getRawObject.get("mail").getAsString()
            case ODATA_USER if !o.getRawObject.get("userPrincipalName").isJsonNull =>
              o.getRawObject.get("userPrincipalName").getAsString()
            case _ => "" // we will ignore these
          }
        }.filterNot(_ == ""))
      }
    }
  }

  private def listGroupMembersInternal(groupId: AzureGroupId): IO[Seq[DirectoryObject]] = IO {
    val firstPage = graphClient.groups(groupId.value).members()
      .buildRequest().select("displayName,mail,userPrincipalName")
      .get()
    pageThroughMembers(firstPage)
  }

  def pageThroughMembers(page: IDirectoryObjectCollectionWithReferencesPage): Seq[DirectoryObject] = {
    page.getCurrentPage.asScala.toSeq ++ (Option(page.getNextPage) match {
      case None => Seq.empty
      case Some(nextPage) => pageThroughMembers(nextPage.buildRequest().get())
    })
  }

}

final case class AzureGroupId(value: String) extends ValueObject
final case class AzureUserId(value: String) extends ValueObject
