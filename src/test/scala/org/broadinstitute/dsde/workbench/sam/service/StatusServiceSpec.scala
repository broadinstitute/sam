package org.broadinstitute.dsde.workbench.sam.service

import java.net.URI

import akka.actor.ActorSystem
import com.unboundid.ldap.sdk.{LDAPConnection, LDAPConnectionPool}
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchException}
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.config.DirectoryConfig
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, LdapRegistrationDAO, MockDirectoryDAO, RegistrationDAO}
import org.broadinstitute.dsde.workbench.sam.db.{DatabaseNames, DbReference}
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.broadinstitute.dsde.workbench.util.health.Subsystems.{Database, GoogleGroups, OpenDJ}
import org.broadinstitute.dsde.workbench.util.health.{StatusCheckResponse, SubsystemStatus, Subsystems}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import scalikejdbc.config.DBs

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

class StatusServiceSpec extends AnyFreeSpec with Matchers with BeforeAndAfterAll with TestSupport with Eventually {
  implicit val system = ActorSystem("StatusServiceSpec")
  val allUsersEmail = WorkbenchEmail("allusers@example.com")
  val dbReference = TestSupport.dbRef

  val directoryConfig: DirectoryConfig = TestSupport.directoryConfig
  val dirURI = new URI(directoryConfig.directoryUrl)
  val connectionPool = new LDAPConnectionPool(new LDAPConnection(dirURI.getHost, dirURI.getPort, directoryConfig.user, directoryConfig.password), directoryConfig.connectionPoolSize)

  override def afterAll(): Unit = {
    connectionPool.close()
    system.terminate()
  }

  // We need to mock this because Postgres does some automatic setup and gets unhappy with multiple instances of its DAO existing at once.
  private def mockDirectoryDAOWithPostgresStatusCheck(dbReferenceOverride: DbReference = dbReference) = {
    val mockDirectoryDAO = new MockDirectoryDAO {
      override def checkStatus(samRequestContext: SamRequestContext): Boolean = {
        dbReferenceOverride.inLocalTransaction { session =>
          if (session.connection.isValid((2 seconds).toSeconds.intValue())) {
            true
          } else {
            false
          }
        }
      }
    }
    mockDirectoryDAO
  }

  private def createLdapDaoWithConnectionPool(connectionPoolOverride: LDAPConnectionPool = connectionPool) = {
    new LdapRegistrationDAO(connectionPoolOverride, directoryConfig, TestSupport.blockingEc)
  }

  private def newStatusService(directoryDAO: DirectoryDAO, registrationDAO: RegistrationDAO) = {
    new StatusService(directoryDAO, registrationDAO, new NoExtensions {
      override def checkStatus: Map[Subsystems.Subsystem, Future[SubsystemStatus]] = Map(Subsystems.GoogleGroups -> Future.successful(SubsystemStatus(true, None)))
    }, dbReference, pollInterval = 10 milliseconds)
  }

  private def directoryDAOWithAllUsersGroup(dbReferenceOverride: DbReference = dbReference) = {
    val directoryDAO = mockDirectoryDAOWithPostgresStatusCheck(dbReferenceOverride)
    directoryDAO.createGroup(BasicWorkbenchGroup(NoExtensions.allUsersGroupName, Set.empty, allUsersEmail), samRequestContext = samRequestContext).unsafeRunSync()
    directoryDAO
  }

  private def noOpenDJGroups = {
    val connectionPoolOverride = new LDAPConnectionPool(new LDAPConnection(dirURI.getHost, dirURI.getPort, directoryConfig.user, directoryConfig.password), directoryConfig.connectionPoolSize)
    connectionPoolOverride.close()
    newStatusService(mockDirectoryDAOWithPostgresStatusCheck(), createLdapDaoWithConnectionPool(connectionPoolOverride))
  }

  private def ok = newStatusService(directoryDAOWithAllUsersGroup(), createLdapDaoWithConnectionPool(connectionPool))

  private def failingExtension = {
    val service = new StatusService(directoryDAOWithAllUsersGroup(), createLdapDaoWithConnectionPool(), new NoExtensions {
      override def checkStatus: Map[Subsystems.Subsystem, Future[SubsystemStatus]] = Map(Subsystems.GoogleGroups -> Future.failed(new WorkbenchException("bad google")))
    }, dbReference, pollInterval = 10 milliseconds)
    service
  }

  private def failingDatabase = {
    // background database configured to connect to non existent database
    val dbReferenceOverride = DbReference(DatabaseNames.Background, TestSupport.blockingEc)
    val service = new StatusService(directoryDAOWithAllUsersGroup(dbReferenceOverride), createLdapDaoWithConnectionPool(), NoExtensions, dbReferenceOverride, pollInterval = 10 milliseconds)
    service
  }

  val cases = {
    Seq(
      ("ok", ok, StatusCheckResponse(true, Map(OpenDJ -> SubsystemStatus(true, None), GoogleGroups -> SubsystemStatus(true, None), Database -> SubsystemStatus(true, None)))),
      ("noOpenDJGroups", noOpenDJGroups, StatusCheckResponse(false, Map(OpenDJ -> SubsystemStatus(false, Option(List(s"LDAP database connection invalid or timed out checking"))), GoogleGroups -> SubsystemStatus(true, None), Database -> SubsystemStatus(true, None)))),
      ("failingExtension", failingExtension, StatusCheckResponse(false, Map(OpenDJ -> SubsystemStatus(true, None), GoogleGroups -> SubsystemStatus(false, Option(List(s"bad google"))), Database -> SubsystemStatus(true, None)))),
      ("failingDatabase", failingDatabase, StatusCheckResponse(false, Map(OpenDJ -> SubsystemStatus(true, None), Database -> SubsystemStatus(false, Option(List("The connection attempt failed."))))))
    )
  }

  "StatusService" - {
    cases.foreach { case (name, service, expected) =>
      s"should have correct status for $name" in {
        if (name == "failingDatabase") DBs.setup(DatabaseNames.Background.name)
        implicit val patienceConfig = PatienceConfig(timeout = 1 second)
        eventually {
          assertResult(expected) {
            runAndWait(service.getStatus())
          }
        }
      }
    }
  }

}
