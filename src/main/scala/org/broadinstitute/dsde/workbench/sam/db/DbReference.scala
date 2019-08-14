package org.broadinstitute.dsde.workbench.sam.db

import java.sql.SQLTimeoutException

import cats.effect.{IO, Resource}
import com.google.common.base.Throwables
import com.typesafe.scalalogging.LazyLogging
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.{ClassLoaderResourceAccessor, ResourceAccessor}
import liquibase.{Contexts, Liquibase}
import org.broadinstitute.dsde.workbench.sam.config.LiquibaseConfig
import scalikejdbc.{ConnectionPool, DBSession, NamedDB}
import scalikejdbc.config.DBs
import sun.security.provider.certpath.SunCertPathBuilderException

object DbReference extends LazyLogging {

  private def initWithLiquibase(liquibaseConfig: LiquibaseConfig, changelogParameters: Map[String, AnyRef] = Map.empty): Unit = {
    val dbConnection = ConnectionPool.borrow('sam_foreground)
    try {
      val liquibaseConnection = new JdbcConnection(dbConnection)
      val resourceAccessor: ResourceAccessor = new ClassLoaderResourceAccessor()
      val liquibase = new Liquibase(liquibaseConfig.changelog, resourceAccessor, liquibaseConnection)

      changelogParameters.foreach { case (key, value) => liquibase.setChangeLogParameter(key, value) }
      liquibase.update(new Contexts())
    } catch {
      case e: SQLTimeoutException =>
        val isCertProblem = Throwables.getRootCause(e).isInstanceOf[SunCertPathBuilderException]

        if (isCertProblem) {
          val k = "javax.net.ssl.keyStore"
          if (System.getProperty(k) == null) {
            logger.warn("************")
            logger.warn(s"The system property '${k}' is null. This is likely the cause of the database connection failure.")
            logger.warn("************")
          }
        }
        throw e
    } finally {
      dbConnection.close()
    }
  }

  def init(liquibaseConfig: LiquibaseConfig, dbName: Symbol): DbReference = {
    DBs.setup(dbName)
    DBs.loadGlobalSettings()
    if (liquibaseConfig.initWithLiquibase) {
      initWithLiquibase(liquibaseConfig)
    }

    DbReference(dbName)
  }

  def resource(liquibaseConfig: LiquibaseConfig, dbName: Symbol): Resource[IO, DbReference] = Resource.make(
    IO(init(liquibaseConfig, dbName))
  )(_ => IO(DBs.close(dbName)))
}

case class DbReference(dbName: Symbol) extends LazyLogging {
  def inLocalTransaction[A](f: DBSession => A): A = {
    NamedDB(dbName).localTx[A] { implicit session =>
      f(session)
    }
  }
}

