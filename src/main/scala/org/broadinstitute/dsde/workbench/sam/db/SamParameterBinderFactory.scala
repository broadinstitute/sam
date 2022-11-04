package org.broadinstitute.dsde.workbench.sam.db

import org.broadinstitute.dsde.workbench.model.ValueObject
import scalikejdbc.{NoExtractor, ParameterBinderFactory, SQL, SQLInterpolationString}
import scalikejdbc.interpolation.SQLSyntax

object SamParameterBinderFactory {
  implicit def databaseKeyPbf[T <: DatabaseKey]: ParameterBinderFactory[T] = ParameterBinderFactory[T] { value => (stmt, idx) =>
    stmt.setLong(idx, value.value)
  }

  implicit def valueObjectPbf[T <: ValueObject]: ParameterBinderFactory[T] = ParameterBinderFactory[T] { value => (stmt, idx) =>
    stmt.setString(idx, value.value)
  }

  implicit def databaseArrayPbf[T <: DatabaseArray]: ParameterBinderFactory[T] = ParameterBinderFactory[T] { value => (stmt, idx) =>
    stmt.setArray(idx, value.asSqlArray(stmt.getConnection))
  }

  implicit class SqlInterpolationWithSamBinders(val sc: StringContext) extends AnyVal {
    def samsql[A](params: Any*): SQL[A, NoExtractor] =
      new SQLInterpolationString(sc).sql(addParameterBinders(params): _*)

    def samsqls(params: Any*): SQLSyntax =
      new SQLInterpolationString(sc).sqls(addParameterBinders(params): _*)

    /** Iterates through all params and replaces any with one of the above binders if appropriate. Called recursively for Option and Traversable.
      * @param args
      * @return
      */
    private def addParameterBinders(args: Seq[Any]): Seq[Any] =
      args.map {
        case v: ValueObject => valueObjectPbf(v)
        case pk: DatabaseKey => databaseKeyPbf(pk)
        case pa: DatabaseArray => databaseArrayPbf(pa)
        case t: Iterable[Any] => addParameterBinders(t.toSeq)
        case opt: Option[Any] => opt.flatMap(value => addParameterBinders(Seq(value)).headOption)
        case a => a
      }
  }
}
