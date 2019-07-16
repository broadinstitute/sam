package org.broadinstitute.dsde.workbench.sam.directory

import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchGroupName, WorkbenchSubject}
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup
import org.postgresql.util.PSQLException
import org.scalatest.{BeforeAndAfterEach, FlatSpec, Matchers}

import scala.concurrent.ExecutionContext.Implicits.global

class PostgresDirectoryDAOSpec extends FlatSpec with Matchers with BeforeAndAfterEach {
  val dao = new PostgresDirectoryDAO(TestSupport.dbRef, TestSupport.blockingEc)

  val defaultGroupName = WorkbenchGroupName("group")
  val defaultGroup = BasicWorkbenchGroup(defaultGroupName, Set.empty, WorkbenchEmail("foo@bar.com"))

  override protected def beforeEach(): Unit = {
    TestSupport.truncateAll
  }

  "PostgresDirectoryDAO" should "create a group" in {
    dao.createGroup(defaultGroup).unsafeRunSync() shouldEqual defaultGroup
  }

  it should "create a group with access instructions" in {
    dao.createGroup(defaultGroup, Option("access instructions")).unsafeRunSync() shouldEqual defaultGroup
  }

  it should "not allow groups with duplicate names" in {
    val duplicateGroup = BasicWorkbenchGroup(defaultGroupName, Set.empty, WorkbenchEmail("foo@bar.com"))
    dao.createGroup(defaultGroup).unsafeRunSync()
    assertThrows[PSQLException] {
      dao.createGroup(duplicateGroup).unsafeRunSync()
    }
  }

  it should "create groups with subGroup members" in {
    val subGroup = defaultGroup
    val members: Set[WorkbenchSubject] = Set(subGroup.id)
    val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parentGroup"), members, WorkbenchEmail("bar@baz.com"))

    dao.createGroup(subGroup).unsafeRunSync()
    dao.createGroup(parentGroup).unsafeRunSync()

    val loadedGroup = dao.loadGroup(parentGroup.id).unsafeRunSync().getOrElse(fail(s"Failed to load group ${parentGroup.id}"))
    loadedGroup.members shouldEqual members
  }

  it should "load a group" in {
    dao.createGroup(defaultGroup).unsafeRunSync()
    val loadedGroup = dao.loadGroup(defaultGroupName).unsafeRunSync().getOrElse(fail(s"Failed to load group $defaultGroupName"))
    loadedGroup shouldEqual defaultGroup
  }

  it should "return None when loading a nonexistent group" in {
    dao.loadGroup(WorkbenchGroupName("fakeGroup")).unsafeRunSync() shouldBe None
  }
}
