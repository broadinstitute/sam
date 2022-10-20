package org.broadinstitute.dsde.workbench.sam.db

import cats.effect.{IO, Resource}
import com.google.common.base.Throwables
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import io.opencensus.trace.AttributeValue
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.{ClassLoaderResourceAccessor, ResourceAccessor}
import liquibase.{Contexts, Liquibase}
import org.broadinstitute.dsde.workbench.sam.config.LiquibaseConfig
import org.broadinstitute.dsde.workbench.sam.db.DatabaseNames.DatabaseName
import org.broadinstitute.dsde.workbench.sam.db.TestDbReference.databaseEnabled
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.broadinstitute.dsde.workbench.util2.ExecutionContexts
import scalikejdbc.config.DBs
import scalikejdbc.{ConnectionPool, DBSession, IsolationLevel}

import java.security.cert.CertPathBuilderException
import java.sql.SQLTimeoutException
import scala.concurrent.ExecutionContext

object TestDbReference extends LazyLogging {
  private val config = ConfigFactory.load()
  private val databaseEnabled = config.getBoolean("db.enabled")

  private def initWithLiquibase(liquibaseConfig: LiquibaseConfig, dbName: DatabaseName, changelogParameters: Map[String, AnyRef] = Map.empty): Unit =
    if (databaseEnabled) {
      val dbConnection = ConnectionPool.borrow(dbName.name)
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

  def init(liquibaseConfig: LiquibaseConfig, dbName: DatabaseName, dbExecutionContext: ExecutionContext): TestDbReference = {
    DBs.setup(dbName.name)
    DBs.loadGlobalSettings()
    if (liquibaseConfig.initWithLiquibase) {
      initWithLiquibase(liquibaseConfig, dbName)
    }

    new TestDbReference(dbName, dbExecutionContext)
  }

  def resource(liquibaseConfig: LiquibaseConfig, dbName: DatabaseName): Resource[IO, TestDbReference] =
    for {
      dbExecutionContext <- ExecutionContexts.fixedThreadPool[IO](DBs.config.getInt(s"db.${dbName.name.name}.poolMaxSize"))
      dbRef <- Resource.make(
        IO(init(liquibaseConfig, dbName, dbExecutionContext))
      )(_ => IO(DBs.close(dbName.name)))
    } yield dbRef
}

class TestDbReference(dbName: DatabaseName, dbExecutionContext: ExecutionContext) extends DbReference(dbName, dbExecutionContext) {
  override def readOnly[A](f: DBSession => A): A =
    if (databaseEnabled) {
      super.readOnly(f)
    } else {
      throw new RuntimeException(s"No DB Access for you!")
    }

  override def inLocalTransactionWithIsolationLevel[A](isolationLevel: IsolationLevel)(f: DBSession => A): A =
    if (databaseEnabled) {
      super.inLocalTransactionWithIsolationLevel(isolationLevel)(f)
    } else {
      throw new RuntimeException(s"No DB Access for you!")
    }

  override def runDatabaseIO[A](
      dbQueryName: String,
      samRequestContext: SamRequestContext,
      databaseIO: IO[A],
      spanAttributes: Map[String, AttributeValue] = Map.empty
  ): IO[A] =
    if (databaseEnabled) {
      super.runDatabaseIO(dbQueryName, samRequestContext, databaseIO, spanAttributes)
    } else {
      throw new RuntimeException(s"No DB Access for you!")
    }
}
