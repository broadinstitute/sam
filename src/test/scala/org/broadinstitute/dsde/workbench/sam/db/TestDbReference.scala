package org.broadinstitute.dsde.workbench.sam.db

import cats.effect.{IO, Resource}
import com.google.common.base.Throwables
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import io.opencensus.trace.AttributeValue
import io.zonky.test.db.postgres.embedded.{EmbeddedPostgres, LiquibasePreparer}
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.{ClassLoaderResourceAccessor, ResourceAccessor}
import liquibase.{Contexts, Liquibase}
import org.broadinstitute.dsde.workbench.sam.config.{DatabaseConfig, LiquibaseConfig}
import org.broadinstitute.dsde.workbench.sam.db.TestDbReference.databaseEnabled
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.broadinstitute.dsde.workbench.util2.ExecutionContexts
import scalikejdbc.config.DBs
import scalikejdbc.{DBSession, IsolationLevel}

import java.security.cert.CertPathBuilderException
import java.sql.SQLTimeoutException
import scala.concurrent.ExecutionContext

object TestDbReference extends LazyLogging {
  private val config = ConfigFactory.load()
  private val databaseEnabled = config.getBoolean("db.enabled")
  val liquibaseChangelog = "liquibase/changelog.xml"
  lazy val liquibasePreparer = LiquibasePreparer.forClasspathLocation(liquibaseChangelog)

  lazy val embeddedDb = EmbeddedPostgres
    .builder()
    .setPort(5432)
    .start()

  // This code is copied over from DbReference. We didnt want to have to refactor DbReference to be able to use it in tests.
  private def initWithLiquibase(liquibaseConfig: LiquibaseConfig, dbName: Symbol, changelogParameters: Map[String, AnyRef] = Map.empty): Unit =
    if (databaseEnabled) {
      val dbConnection = embeddedDb.getPostgresDatabase.getConnection
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

  def init(liquibaseConfig: LiquibaseConfig, dbName: Symbol, dbExecutionContext: ExecutionContext): TestDbReference = {
    DBs.setup(dbName)
    DBs.loadGlobalSettings()
    if (liquibaseConfig.initWithLiquibase) {
      initWithLiquibase(liquibaseConfig, dbName)
    }

    new TestDbReference(dbName, dbExecutionContext)
  }

  def resource(liquibaseConfig: LiquibaseConfig, databaseConfig: DatabaseConfig): Resource[IO, TestDbReference] =
    for {
      dbExecutionContext <- ExecutionContexts.fixedThreadPool[IO](databaseConfig.poolMaxSize)
      dbRef <- Resource.make(
        IO(init(liquibaseConfig, databaseConfig.dbName, dbExecutionContext))
      )(_ => IO(DBs.close(databaseConfig.dbName)))
    } yield dbRef
}

class TestDbReference(dbName: Symbol, dbExecutionContext: ExecutionContext) extends DbReference(dbName, dbExecutionContext) {
  override def readOnly[A](f: DBSession => A): A =
    if (databaseEnabled) {
      super.readOnly(f)
    } else {
      throw new RuntimeException(s"No DB Access for you! You'll need to either set postgres.enabled=true or mock the database access.")
    }

  override def inLocalTransactionWithIsolationLevel[A](isolationLevel: IsolationLevel)(f: DBSession => A): A =
    if (databaseEnabled) {
      super.inLocalTransactionWithIsolationLevel(isolationLevel)(f)
    } else {
      throw new RuntimeException(s"No DB Access for you! You'll need to either set postgres.enabled=true or mock the database access.")
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
      throw new RuntimeException(s"No DB Access for you! You'll need to either set postgres.enabled=true or mock the database access.")
    }
}
