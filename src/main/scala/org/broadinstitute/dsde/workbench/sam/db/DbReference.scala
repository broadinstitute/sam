package org.broadinstitute.dsde.workbench.sam.db

import java.sql.SQLTimeoutException

import cats.effect.{Resource, Sync}
import com.google.common.base.Throwables
import com.typesafe.scalalogging.LazyLogging
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.{ClassLoaderResourceAccessor, ResourceAccessor}
import liquibase.{Contexts, Liquibase}
import org.broadinstitute.dsde.workbench.sam.config.LiquibaseConfig
import scalikejdbc.{DB, DBSession}
import scalikejdbc.config.DBs
import sun.security.provider.certpath.SunCertPathBuilderException
import scala.language.higherKinds

object DbReference extends LazyLogging {

  private def initWithLiquibase(liquibaseConfig: LiquibaseConfig, changelogParameters: Map[String, AnyRef] = Map.empty): Unit = {
    val dbConnection = DB.connect()
    try {
      val liquibaseConnection = new JdbcConnection(dbConnection.conn)
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

  private def init(liquibaseConfig: LiquibaseConfig): DbReference = {
    DBs.setupAll()
    if (liquibaseConfig.initWithLiquibase)
      initWithLiquibase(liquibaseConfig)

    DbReference()
  }

  def resource[F[_]: Sync](liquibaseConfig: LiquibaseConfig): Resource[F, DbReference] = Resource.make(
    Sync[F].delay(init(liquibaseConfig))
  )(_ => Sync[F].delay(DBs.closeAll()))
}

case class DbReference() {

  def inReadOnlyTransaction[A](f: DBSession => A): A = {
    DB.readOnly[A] { implicit session =>
      f(session)
    }
  }

  def inLocalTransaction[A](f: DBSession => A): A = {
    DB.localTx[A] { implicit session =>
      f(session)
    }
  }
}

