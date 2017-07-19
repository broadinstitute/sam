package org.broadinstitute.dsde.workbench.sam.directory

import java.util.UUID

import com.typesafe.config.ConfigFactory
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.config.DirectoryConfig
import org.broadinstitute.dsde.workbench.sam.model._
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Created by dvoet on 5/30/17.
  */
class JndiDirectoryDAOSpec extends FlatSpec with Matchers with TestSupport {
  val directoryConfig = ConfigFactory.load().as[DirectoryConfig]("directory")
  val dao = new JndiDirectoryDAO(directoryConfig)

  "JndiGroupDirectoryDAO" should "create, read, delete groups" in {
    val groupName = SamGroupName(UUID.randomUUID().toString)
    val group = SamGroup(groupName, Set.empty)

    assertResult(None) {
      runAndWait(dao.loadGroup(group.name))
    }

    assertResult(group) {
      runAndWait(dao.createGroup(group))
    }

    assertResult(Some(group)) {
      runAndWait(dao.loadGroup(group.name))
    }

    runAndWait(dao.deleteGroup(group.name))

    assertResult(None) {
      runAndWait(dao.loadGroup(group.name))
    }
  }

  it should "create, read, delete users" in {
    val userId = SamUserId(UUID.randomUUID().toString)
    val user = SamUser(userId, Option(SamUserEmail("foo@bar.com")))

    assertResult(None) {
      runAndWait(dao.loadUser(user.id))
    }

    assertResult(user) {
      runAndWait(dao.createUser(user))
    }

    assertResult(Some(user)) {
      runAndWait(dao.loadUser(user.id))
    }

    runAndWait(dao.deleteUser(user.id))

    assertResult(None) {
      runAndWait(dao.loadUser(user.id))
    }
  }

  it should "list groups" in {
    val userId = SamUserId(UUID.randomUUID().toString)
    val user = SamUser(userId, Option(SamUserEmail("foo@bar.com")))

    val groupName1 = SamGroupName(UUID.randomUUID().toString)
    val group1 = SamGroup(groupName1, Set(userId))

    val groupName2 = SamGroupName(UUID.randomUUID().toString)
    val group2 = SamGroup(groupName2, Set(groupName1))

    runAndWait(dao.createUser(user))
    runAndWait(dao.createGroup(group1))
    runAndWait(dao.createGroup(group2))

    try {
      assertResult(Set(groupName1, groupName2)) {
        runAndWait(dao.listUsersGroups(userId))
      }
    } finally {
      runAndWait(dao.deleteUser(userId))
      runAndWait(dao.deleteGroup(groupName1))
      runAndWait(dao.deleteGroup(groupName2))
    }
  }

  it should "add/remove groups" in {
    val userId = SamUserId(UUID.randomUUID().toString)
    val user = SamUser(userId, Option(SamUserEmail("foo@bar.com")))

    val groupName1 = SamGroupName(UUID.randomUUID().toString)
    val group1 = SamGroup(groupName1, Set.empty)

    val groupName2 = SamGroupName(UUID.randomUUID().toString)
    val group2 = SamGroup(groupName2, Set.empty)

    runAndWait(dao.createUser(user))
    runAndWait(dao.createGroup(group1))
    runAndWait(dao.createGroup(group2))

    try {

      runAndWait(dao.addGroupMember(groupName1, userId))

      assertResult(Some(group1.copy(members = Set(userId)))) {
        runAndWait(dao.loadGroup(groupName1))
      }

      runAndWait(dao.addGroupMember(groupName1, groupName2))

      assertResult(Some(group1.copy(members = Set(userId, groupName2)))) {
        runAndWait(dao.loadGroup(groupName1))
      }

      runAndWait(dao.removeGroupMember(groupName1, userId))

      assertResult(Some(group1.copy(members = Set(groupName2)))) {
        runAndWait(dao.loadGroup(groupName1))
      }

      runAndWait(dao.removeGroupMember(groupName1, groupName2))

      assertResult(Some(group1)) {
        runAndWait(dao.loadGroup(groupName1))
      }

    } finally {
      runAndWait(dao.deleteUser(userId))
      runAndWait(dao.deleteGroup(groupName1))
      runAndWait(dao.deleteGroup(groupName2))
    }
  }

}
