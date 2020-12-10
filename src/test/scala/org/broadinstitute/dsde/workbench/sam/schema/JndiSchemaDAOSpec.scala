package org.broadinstitute.dsde.workbench.sam.schema

import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.schema.SchemaStatus._
import org.scalatest.{Ignore => _, _}

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Created by mbemis on 3/12/18.
  */
class JndiSchemaDAOSpec extends AnyFlatSpec with Matchers with TestSupport with BeforeAndAfter with BeforeAndAfterAll {
  val directoryConfig = TestSupport.appConfig.directoryConfig
  val schemaLockConfig = TestSupport.appConfig.schemaLockConfig
  val schemaDao = new JndiSchemaDAO(directoryConfig, schemaLockConfig)

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    runAndWait(schemaDao.init())
  }

  before {
    runAndWait(schemaDao.clearDatabase())
    runAndWait(schemaDao.clearSchemaLock())
    runAndWait(schemaDao.createOrgUnits())
  }

  "JndiSchemaDAO" should "insert a schema version record when applying a new schema" taggedAs SchemaInit in {
    //First check to make sure that the schema can be applied
    Await.result(schemaDao.readSchemaStatus(), Duration.Inf) shouldEqual Proceed

    //Apply the schema for the first time
    Await.result(schemaDao.initWithSchemaLock(), Duration.Inf)

    //We've applied the schema, so if we read the status it should tell us to ignore
    Await.result(schemaDao.readSchemaStatus(), Duration.Inf) shouldEqual Ignore
  }

  it should "not update the schema when trying to apply an out-of-date version" taggedAs SchemaInit in {
    val schemaDaoOlder = new JndiSchemaDAO(directoryConfig, schemaLockConfig, JndiSchemaDAO.schemaVersion-1)

    //First check to make sure that the schema can be applied
    Await.result(schemaDaoOlder.readSchemaStatus(), Duration.Inf) shouldEqual Proceed

    //Apply schema version 0 for the first time
    Await.result(schemaDaoOlder.initWithSchemaLock(), Duration.Inf)

    //Make sure it was applied
    Await.result(schemaDaoOlder.readSchemaStatus(), Duration.Inf) shouldEqual Ignore

    //First check to make sure that the schema can be applied
    Await.result(schemaDao.readSchemaStatus(), Duration.Inf) shouldEqual Proceed

    //Apply schema version 1 for the first time
    Await.result(schemaDao.initWithSchemaLock(), Duration.Inf)

    //Make sure it was applied
    Await.result(schemaDao.readSchemaStatus(), Duration.Inf) shouldEqual Ignore

    //Try to re-apply schema version 0 after we've updated to version 1
    Await.result(schemaDaoOlder.readSchemaStatus(), Duration.Inf) shouldEqual Ignore
  }

}

object SchemaInit extends Tag("org.broadinstitute.tags.SchemaInit")
