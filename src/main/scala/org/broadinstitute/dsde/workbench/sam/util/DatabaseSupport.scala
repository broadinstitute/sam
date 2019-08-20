package org.broadinstitute.dsde.workbench.sam.util

import cats.effect.{ContextShift, IO}
import org.broadinstitute.dsde.workbench.model.{WorkbenchGroupIdentity, WorkbenchGroupName}
import org.broadinstitute.dsde.workbench.sam.db.DbReference
import org.broadinstitute.dsde.workbench.sam.db.SamParameterBinderFactory._
import org.broadinstitute.dsde.workbench.sam.db.tables.{GroupTable, PolicyTable, ResourceTable, ResourceTypeTable}
import org.broadinstitute.dsde.workbench.sam.model.FullyQualifiedPolicyId
import scalikejdbc.{DBSession, SQLSyntax}

import scala.concurrent.ExecutionContext

trait DatabaseSupport {
  protected val ecForDatabaseIO: ExecutionContext
  protected val cs: ContextShift[IO]
  protected val dbRef: DbReference

  protected def runInTransaction[A](databaseFunction: DBSession => A): IO[A] = {
    cs.evalOn(ecForDatabaseIO)(IO {
      dbRef.inLocalTransaction(databaseFunction)
    })
  }

  protected def workbenchGroupIdentityToGroupPK(groupId: WorkbenchGroupIdentity): SQLSyntax = {
    groupId match {
      case group: WorkbenchGroupName => GroupTable.groupPKQueryForGroup(group)
      case policy: FullyQualifiedPolicyId => groupPKQueryForPolicy(policy)
    }
  }

  protected def groupPKQueryForPolicy(policyId: FullyQualifiedPolicyId,
                                    resourceTypeTableAlias: String = "rt",
                                    resourceTableAlias: String = "r",
                                    policyTableAlias: String = "p"): SQLSyntax = {
    val rt = ResourceTypeTable.syntax(resourceTypeTableAlias)
    val r = ResourceTable.syntax(resourceTableAlias)
    val p = PolicyTable.syntax(policyTableAlias)
    samsqls"""select ${p.groupId}
              from ${ResourceTypeTable as rt}
              join ${ResourceTable as r} on ${rt.id} = ${r.resourceTypeId}
              join ${PolicyTable as p} on ${r.id} = ${p.resourceId}
              where ${rt.name} = ${policyId.resource.resourceTypeName}
              and ${r.name} = ${policyId.resource.resourceId}
              and ${p.name} = ${policyId.accessPolicyName}"""
  }
}
