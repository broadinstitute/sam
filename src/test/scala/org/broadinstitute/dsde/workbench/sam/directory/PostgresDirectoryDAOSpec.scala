package org.broadinstitute.dsde.workbench.sam.directory

import cats.effect.IO
import com.typesafe.config.ConfigFactory
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchGroupName}
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.config.LiquibaseConfig
import org.broadinstitute.dsde.workbench.sam.config.AppConfig._
import org.broadinstitute.dsde.workbench.sam.db.DbReference
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.ExecutionContext.Implicits.global

class PostgresDirectoryDAOSpec extends FlatSpec with Matchers with TestSupport {
  val config = ConfigFactory.load()

  private def initDao: PostgresDirectoryDAO = {
    val dbRef = DbReference.resource[IO](config.as[LiquibaseConfig]("liquibase")).use(dbRef => IO(dbRef)).unsafeRunSync()
    new PostgresDirectoryDAO(dbRef, TestSupport.blockingEc)
  }

  "PostgresDirectoryDAO" should "create a group" in {
    val dao = initDao
    val group = BasicWorkbenchGroup(WorkbenchGroupName("group"), Set.empty, WorkbenchEmail("foo@bar.com"))
    dao.createGroup(group)
  }

  it should "create a group with access instructions" in {
    val dao = initDao
    val group = BasicWorkbenchGroup(WorkbenchGroupName("group"), Set.empty, WorkbenchEmail("foo@bar.com"))
    dao.createGroup(group, Option("access instructions"))
  }

  it should "load a group" in {
    val dao = initDao
    val groupName = WorkbenchGroupName("group")
    val group = BasicWorkbenchGroup(groupName, Set.empty, WorkbenchEmail("foo@bar.com"))
    for {
      _ <- dao.createGroup(group)
      loadedGroupOpt <- dao.loadGroup(groupName)
    } yield {
      loadedGroupOpt.map(loadedGroup => loadedGroup shouldEqual group)
    }
  }
}
