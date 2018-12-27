package org.broadinstitute.dsde.workbench.sam.util
import java.util.concurrent.Executors

import cats.effect.IO
import cats.effect.concurrent.Semaphore
import cats.implicits._
import com.unboundid.ldap.sdk.LDAPConnectionPool
import org.broadinstitute.dsde.workbench.model.WorkbenchSubject
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.TestSupport._
import org.broadinstitute.dsde.workbench.sam.config.{CacheConfig, DirectoryConfig}
import org.ehcache.Cache
import org.scalatest.{AsyncFlatSpec, Matchers}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class LdapSupportSpec extends AsyncFlatSpec with Matchers with TestSupport {
  val cacheConfig = CacheConfig(1, java.time.Duration.ofMinutes(10L))
  val config = DirectoryConfig("url", "user", "password", "baseDN", "true", 1, 2, cacheConfig, cacheConfig)

  "LdapSupport executeLdap" should "be able to limit number of executions" in {
    val ioa = IO.sleep(1 seconds)
    val fakeLdapSupport = new FakeLdapSupport(config)
    val executeBoth = (fakeLdapSupport.executeLdap(ioa), fakeLdapSupport.executeLdap(ioa)).parTupled
    val executeThree = (fakeLdapSupport.executeLdap(ioa), fakeLdapSupport.executeLdap(ioa), fakeLdapSupport.executeLdap(ioa)).parTupled
    val result = for {
      start <- timer.clock.monotonic(MILLISECONDS)
      _ <- executeBoth
      end <- timer.clock.monotonic(MILLISECONDS)
      start3 <- timer.clock.monotonic(MILLISECONDS)
      _ <- executeThree
      end3 <- timer.clock.monotonic(MILLISECONDS)
    } yield {
      (end - start) should be <= (2 seconds).toMillis //two ioas can run in paralell
      (end3 - start3) should be >= (2 seconds).toMillis //when 3 ios try to run at the same time, the third one will have to wait until one of ioas finish
    }
    result.unsafeToFuture()
  }
}

//
class FakeLdapSupport(val directoryConfig: DirectoryConfig) extends LdapSupport with TestSupport {
  override protected val ldapConnectionPool: LDAPConnectionPool = null
  override protected val ecForLdapBlockingIO: ExecutionContext = ExecutionContext.fromExecutor(Executors.newCachedThreadPool)
  override val semaphore: Semaphore[IO] = Semaphore[IO](2).unsafeRunSync()
  override protected val memberOfCache: Cache[WorkbenchSubject, Set[String]] = testMemberOfCache
}