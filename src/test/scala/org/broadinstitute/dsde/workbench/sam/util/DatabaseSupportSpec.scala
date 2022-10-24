package org.broadinstitute.dsde.workbench.sam.util

import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.WorkbenchUserId
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.db.{PSQLStateExtensions, TestDbReference}
import org.postgresql.util.PSQLException
import org.scalatest.BeforeAndAfterEach
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import scalikejdbc.DBSession

import java.util.UUID
import java.util.concurrent.CyclicBarrier
import scala.concurrent.Future
import cats.effect.Temporal
import cats.effect.unsafe.implicits.global
import org.broadinstitute.dsde.workbench.sam.TestSupport.{databaseEnabled, databaseEnabledClue}

class DatabaseSupportSpec extends AnyFreeSpec with Matchers with BeforeAndAfterEach with TestSupport {
  implicit val ec = scala.concurrent.ExecutionContext.global
  object DatabaseSupport extends DatabaseSupport {
    override protected lazy val writeDbRef: TestDbReference = TestSupport.dbRef
    override protected lazy val readDbRef: TestDbReference = TestSupport.dbRef

    override def serializableWriteTransaction[A](dbQueryName: String, samRequestContext: SamRequestContext, maxTries: Int = 3)(
        databaseFunction: DBSession => A
    )(implicit timer: Temporal[IO]): IO[A] =
      super.serializableWriteTransaction(dbQueryName, samRequestContext, maxTries)(databaseFunction)
  }
  override protected def beforeEach(): Unit = {
    TestSupport.truncateAll
    super.beforeEach()
  }

  "DatabaseSupport" - {
    "runInSerializableTransaction" - {
      "retry serialization failure" in {
        assume(databaseEnabled, databaseEnabledClue)
        // this should run without error
        causeSerializationFailure(2)
      }

      "fail due to serialization failure when out of retries" in {
        assume(databaseEnabled, databaseEnabledClue)
        val e = intercept[PSQLException] {
          causeSerializationFailure(1)
        }
        e.getSQLState should equal(PSQLStateExtensions.SERIALIZATION_FAILURE)
      }
    }
  }

  /** This does some db stuff that causes a serialization failure. 1) insert a record 2) in serializable transaction A read the record 3) in transaction B
    * update the record 4) in transaction A update the record A CyclicBarrier is used to make sure of the ordering between transactions A and B to force the
    * serialization failure. In the case of a retry we only retry transaction A but the thread running B still needs to use the CyclicBarrier so the thread
    * running A does not get stuck.
    *
    * @param maxTries
    * @return
    */
  private def causeSerializationFailure(maxTries: Int) = {
    import org.broadinstitute.dsde.workbench.sam.db.SamParameterBinderFactory._
    val userId = WorkbenchUserId(UUID.randomUUID().toString)
    TestSupport.dbRef.inLocalTransaction { implicit session =>
      samsql"insert into sam_user(id, email, enabled) values ($userId, '', true)".update().apply()
    }

    val barrier = new CyclicBarrier(2)
    val probe = DatabaseSupport.serializableWriteTransaction("test", samRequestContext, maxTries) { implicit session =>
      samsql"select email from sam_user where id = $userId".map(_.string(1)).single().apply()
      barrier.await()
      barrier.await()
      samsql"update sam_user set email=${UUID.randomUUID().toString} where id=$userId".update().apply()
    }

    Future {
      barrier.await()
      TestSupport.dbRef.inLocalTransaction { implicit session =>
        samsql"update sam_user set email=${UUID.randomUUID().toString} where id=$userId".update().apply()
      }
      barrier.await()

      // need a pair of awaits for each additional try
      for (_ <- 1 until maxTries) {
        barrier.await()
        barrier.await()
      }
    }

    probe.unsafeRunSync()
  }
}
