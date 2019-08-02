package org.broadinstitute.dsde.workbench.sam.directory

import java.net.URI
import java.util.UUID

import com.unboundid.ldap.sdk.{LDAPConnection, LDAPConnectionPool}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.{Generator, TestSupport}
import org.broadinstitute.dsde.workbench.sam.TestSupport._
import org.broadinstitute.dsde.workbench.sam.config.DirectoryConfig
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.LdapAccessPolicyDAO
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO
import org.ehcache.Cache
import org.ehcache.config.builders.{CacheConfigurationBuilder, CacheManagerBuilder, ExpiryPolicyBuilder, ResourcePoolsBuilder}
import org.scalatest._

import scala.concurrent.ExecutionContext.Implicits.global
import cats.implicits._

/**
  * Created by dvoet on 5/30/17.
  */
class LdapDirectoryDAOSpec extends FreeSpec with Matchers with BeforeAndAfter with BeforeAndAfterAll with DirectorySubjectNameSupport with DirectoryDAOBehaviors {
  override lazy val directoryConfig: DirectoryConfig = TestSupport.directoryConfig
  val dirURI = new URI(directoryConfig.directoryUrl)
  val connectionPool = new LDAPConnectionPool(new LDAPConnection(dirURI.getHost, dirURI.getPort, directoryConfig.user, directoryConfig.password), directoryConfig.connectionPoolSize)
  val dao = new LdapDirectoryDAO(connectionPool, directoryConfig, TestSupport.blockingEc, TestSupport.testMemberOfCache)
  val schemaDao = new JndiSchemaDAO(directoryConfig, schemaLockConfig)
  val accessPolicyDAO = new LdapAccessPolicyDAO(connectionPool, directoryConfig, TestSupport.blockingEc, TestSupport.testMemberOfCache, TestSupport.testResourceCache)

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    runAndWait(schemaDao.init())
  }

  before {
    runAndWait(schemaDao.clearDatabase())
    runAndWait(schemaDao.createOrgUnits())
  }

  "LdapDirectoryDAO" - {
    "should" - {
      behave like directoryDAO(dao, accessPolicyDAO)
    }

    "loadSubjectEmail" - {
      "fail if the user has not been created" in {
        val userId = WorkbenchUserId(UUID.randomUUID().toString)
        val user = WorkbenchUser(userId, None, WorkbenchEmail("foo@bar.com"))

        assertResult(None) {
          dao.loadUser(user.id).unsafeRunSync()
        }

        dao.loadSubjectEmail(userId).unsafeRunSync() shouldEqual None
      }

      "succeed if the user has been created" in {
        val userId = WorkbenchUserId(UUID.randomUUID().toString)
        val user = WorkbenchUser(userId, None, WorkbenchEmail("foo@bar.com"))

        assertResult(None) {
          dao.loadUser(user.id).unsafeRunSync()
        }

        assertResult(user) {
          dao.createUser(user).unsafeRunSync()
        }

        assertResult(Some(user)) {
          dao.loadUser(user.id).unsafeRunSync()
        }

        assertResult(Some(user.email)) {
          dao.loadSubjectEmail(userId).unsafeRunSync()
        }
      }
    }

    "loadSubjectFromEmail" - {
      "be able to load subject given an email" in {
        val user1 = Generator.genWorkbenchUser.sample.get
        val user2 = user1.copy(email = WorkbenchEmail(user1.email.value + "2"))
        val res = for {
          _ <- dao.createUser(user1)
          subject1 <- dao.loadSubjectFromEmail(user1.email)
          subject2 <- dao.loadSubjectFromEmail(user2.email)
        } yield {
          subject1 shouldEqual (Some(user1.id))
          subject2 shouldEqual (None)
        }
        res.unsafeRunSync()
      }
    }

    "loadSubjectEmail" - {
      "be able to load a subject's email" in {
        val user1 = Generator.genWorkbenchUser.sample.get
        val user2 = user1.copy(id = WorkbenchUserId(user1.id.value + "2"))
        val res = for {
          _ <- dao.createUser(user1)
          email1 <- dao.loadSubjectEmail(user1.id)
          email2 <- dao.loadSubjectEmail(user2.id)
        } yield {
          email1 shouldEqual (Some(user1.email))
          email2 shouldEqual (None)
        }
        res.unsafeRunSync()
      }
    }

    "loadSubjectFromGoogleSubjectId" - {
      "be able to load subject given an google subject Id" in {
        val user = Generator.genWorkbenchUser.sample.get
        val user1 = user.copy(googleSubjectId = Some(GoogleSubjectId(user.id.value)))
        val user2 = user1.copy(googleSubjectId = Some(GoogleSubjectId(user.id.value + "2")))

        val res = for {
          _ <- dao.createUser(user1)
          subject1 <- dao.loadSubjectFromGoogleSubjectId(user1.googleSubjectId.get)
          subject2 <- dao.loadSubjectFromGoogleSubjectId(user2.googleSubjectId.get)
        } yield {
          subject1 shouldEqual (Some(user1.id))
          subject2 shouldEqual (None)
        }
        res.unsafeRunSync()
      }
    }

    "cache" - {
      "return existing item" in {
        val cache: Cache[WorkbenchSubject, Set[String]] = createMemberOfCache("test-memberof-1")

        val testDao = new LdapDirectoryDAO(connectionPool, directoryConfig, blockingEc, cache)

        val workbenchSubject = WorkbenchUserId("snarglepup")
        val group = WorkbenchGroupName("testgroup")
        cache.put(workbenchSubject, Set(subjectDn(group)))
        // note that this user and group are not even in ldap but this should work because we manually put them in the cache
        val actual = testDao.listUsersGroups(workbenchSubject).unsafeRunSync()

        actual should contain theSameElementsAs Set(group)
      }

      "retain non-existing item" in {
        val cache: Cache[WorkbenchSubject, Set[String]] = createMemberOfCache("test-memberof-2")

        val testDao = new LdapDirectoryDAO(connectionPool, directoryConfig, blockingEc, cache)

        val workbenchSubject = WorkbenchUserId("snarglepup")
        val group = WorkbenchGroupName("testgroup")

        testDao.createUser(WorkbenchUser(workbenchSubject, None, WorkbenchEmail("foo"))).unsafeRunSync()
        testDao.createGroup(BasicWorkbenchGroup(group, Set(workbenchSubject), WorkbenchEmail("bar"))).unsafeRunSync()

        assert(!cache.containsKey(workbenchSubject))
        val actual = testDao.listUsersGroups(workbenchSubject).unsafeRunSync()
        actual should contain theSameElementsAs Set(group)
        assert(cache.containsKey(workbenchSubject))
      }
    }

    "listFlattenedMembers" - {
      "flatten nested groups" in {
        val genSingleUserGroup = for {
          user <- Generator.genWorkbenchUser
          subGroupName <- Generator.genWorkbenchGroupName
          email <- Generator.genNonPetEmail
        } yield {
          BasicWorkbenchGroup(subGroupName, Set(user.id), email)
        }

        val genNestedGroupStructure = for {
          level3 <- genSingleUserGroup
          level2 <- genSingleUserGroup.map(BasicWorkbenchGroup.members.modify(_ + level3.id))
          level1 <- genSingleUserGroup.map(BasicWorkbenchGroup.members.modify(_ + level2.id))
        } yield {
          (level1, level2, level3)
        }

        val (level1, level2, level3) = genNestedGroupStructure.sample.get

        val users = level1.members.collect { case u: WorkbenchUserId => Generator.genWorkbenchUser.sample.get.copy(id = u) } ++
          level2.members.collect { case u: WorkbenchUserId => Generator.genWorkbenchUser.sample.get.copy(id = u) } ++
          level3.members.collect { case u: WorkbenchUserId => Generator.genWorkbenchUser.sample.get.copy(id = u) }

        val actualIO = for {
          _ <- users.toList.traverse(dao.createUser)
          _ <- List(level3, level2, level1).traverse(dao.createGroup(_))
          results <- dao.listFlattenedMembers(level1.id)
        } yield {
          results
        }

        actualIO.unsafeRunSync() should contain theSameElementsAs users.map(_.id)
      }

      "tolerate cyclic groups" in {
        val genSingleUserGroup = for {
          user <- Generator.genWorkbenchUser
          subGroupName <- Generator.genWorkbenchGroupName
          email <- Generator.genNonPetEmail
        } yield {
          BasicWorkbenchGroup(subGroupName, Set(user.id), email)
        }

        val genNestedGroupStructure = for {
          level3 <- genSingleUserGroup
          level2 <- genSingleUserGroup.map(BasicWorkbenchGroup.members.modify(_ + level3.id))
          level1 <- genSingleUserGroup.map(BasicWorkbenchGroup.members.modify(_ + level2.id))
        } yield {
          (level1, level2, BasicWorkbenchGroup.members.modify(_ + level1.id)(level3))
        }

        val (level1, level2, level3) = genNestedGroupStructure.sample.get

        val users = level1.members.collect { case u: WorkbenchUserId => Generator.genWorkbenchUser.sample.get.copy(id = u) } ++
          level2.members.collect { case u: WorkbenchUserId => Generator.genWorkbenchUser.sample.get.copy(id = u) } ++
          level3.members.collect { case u: WorkbenchUserId => Generator.genWorkbenchUser.sample.get.copy(id = u) }

        val actualIO = for {
          _ <- users.toList.traverse(dao.createUser)
          _ <- List(level3, level2, level1).traverse(dao.createGroup(_))
          results <- dao.listFlattenedMembers(level1.id)
        } yield {
          results
        }

        actualIO.unsafeRunSync() should contain theSameElementsAs users.map(_.id)
      }
    }
  }

  private def createMemberOfCache(cacheName: String) = {
    val cacheManager = CacheManagerBuilder.newCacheManagerBuilder
      .withCache(
        cacheName,
        CacheConfigurationBuilder
          .newCacheConfigurationBuilder(classOf[WorkbenchSubject], classOf[Set[String]], ResourcePoolsBuilder.heap(10))
          .withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(java.time.Duration.ofMinutes(10)))
      )
      .build
    cacheManager.init()
    val cache = cacheManager.getCache(cacheName, classOf[WorkbenchSubject], classOf[Set[String]])
    cache
  }
}


