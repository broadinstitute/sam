package org.broadinstitute.dsde.workbench.sam.db

import cats.effect.{Async, IO, Resource}
import com.google.common.base.Throwables
import com.typesafe.scalalogging.LazyLogging
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.{ClassLoaderResourceAccessor, ResourceAccessor}
import liquibase.{Contexts, Liquibase}
import org.broadinstitute.dsde.workbench.sam.config.{DatabaseConfig, LiquibaseConfig}
import org.broadinstitute.dsde.workbench.sam.util.OpenTelemetryIOUtils.traceIOWithContext
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.broadinstitute.dsde.workbench.util2.ExecutionContexts
import scalikejdbc.config.DBs
import scalikejdbc.{ConnectionPool, DBSession, IsolationLevel, NamedDB}

import java.security.cert.CertPathBuilderException
import java.sql.Connection.TRANSACTION_READ_COMMITTED
import java.sql.SQLTimeoutException
import scala.concurrent.ExecutionContext

object DbReference extends LazyLogging {
  private def initWithLiquibase(liquibaseConfig: LiquibaseConfig, dbName: Symbol, changelogParameters: Map[String, AnyRef] = Map.empty): Unit = {
    val dbConnection = ConnectionPool.borrow(dbName)
    try {
      val liquibaseConnection = new JdbcConnection(dbConnection)
      val resourceAccessor: ResourceAccessor = new ClassLoaderResourceAccessor()
      val liquibase = new Liquibase(liquibaseConfig.changelog, resourceAccessor, liquibaseConnection)

      changelogParameters.foreach { case (key, value) => liquibase.setChangeLogParameter(key, value) }
      liquibase.update(new Contexts())
    } catch {
      case e: SQLTimeoutException =>
        val isCertProblem = Throwables.getRootCause(e).isInstanceOf[CertPathBuilderException]

        if (isCertProblem) {
          val k = "javax.net.ssl.keyStore"
          if (System.getProperty(k) == null) {
            logger.warn("************")
            logger.warn(s"The system property '${k}' is null. This is likely the cause of the database connection failure.")
            logger.warn("************")
          }
        }
        throw e
    } finally
      dbConnection.close()
  }

  def init(liquibaseConfig: LiquibaseConfig, dbName: Symbol, dbExecutionContext: ExecutionContext): DbReference = {
    DBs.setup(dbName)
    DBs.loadGlobalSettings()
    if (liquibaseConfig.initWithLiquibase) {
      logger.info(s"Initializing $dbName with liquibase")
      initWithLiquibase(liquibaseConfig, dbName)
    } else {
      logger.info(s"Initializing $dbName with without liquibase")
    }

    DbReference(dbName, dbExecutionContext)
  }

  def resource(liquibaseConfig: LiquibaseConfig, databaseConfig: DatabaseConfig): Resource[IO, DbReference] =
    for {
      dbExecutionContext <- ExecutionContexts.fixedThreadPool[IO](databaseConfig.poolMaxSize)
      dbRef <- Resource.make(
        IO(init(liquibaseConfig, databaseConfig.dbName, dbExecutionContext))
      )(_ => IO(DBs.close(databaseConfig.dbName)))
    } yield dbRef
}

/** Sam uses 3 database connection pools. The Read pool is the largest and should be used by read-only transactions in the direct servicing api calls. This is
  * the most important traffic. The Write pool is small to keep the concurrency of serializable write transactions down and thus reduce the number of retries
  * required due to serialization failures. A heavy load of writes should not crowd out reads. The Background pool handles low priority background process reads
  * and writes.
  */
object DatabaseNames {
  sealed trait DatabasePoolName {
    val name: Symbol
  }
  case object Read extends DatabasePoolName {
    val name: Symbol = Symbol("sam_read")
  }
  case object Write extends DatabasePoolName {
    val name: Symbol = Symbol("sam_write")
  }
  case object Background extends DatabasePoolName {
    val name: Symbol = Symbol("sam_background")
  }
}

case class DbReference(dbName: Symbol, dbExecutionContext: ExecutionContext) extends LazyLogging {
  def readOnly[A](f: DBSession => A): A = {
    val db = NamedDB(dbName)
    // https://github.com/scalikejdbc/scalikejdbc/issues/1143
    db.conn.setTransactionIsolation(TRANSACTION_READ_COMMITTED)
    db.readOnly(f)
  }

  def inLocalTransaction[A](f: DBSession => A): A =
    inLocalTransactionWithIsolationLevel(IsolationLevel.ReadCommitted)(f)

  def inLocalTransactionWithIsolationLevel[A](isolationLevel: IsolationLevel)(f: DBSession => A): A =
    NamedDB(dbName).isolationLevel(isolationLevel).localTx[A] { implicit session =>
      f(session)
    }

  def runDatabaseIO[A](
      dbQueryName: String,
      samRequestContext: SamRequestContext,
      databaseIO: IO[A],
      spanAttributes: Attributes = Attributes.empty()
  ): IO[A] =
    Async[IO].evalOnK(dbExecutionContext) {
      traceIOWithContext("postgres-" + dbQueryName, samRequestContext) { samCxt =>
        samCxt.otelContext.map(Span.fromContext).foreach(_.setAllAttributes(spanAttributes))
        databaseIO
      }
    }

}
