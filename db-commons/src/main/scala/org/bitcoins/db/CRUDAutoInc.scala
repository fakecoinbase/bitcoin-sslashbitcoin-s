package org.bitcoins.db

import org.bitcoins.core.util.FutureUtil

import scala.concurrent.{ExecutionContext, Future}

abstract class CRUDAutoInc[T <: DbRowAutoInc[T]](implicit
    ec: ExecutionContext,
    override val appConfig: AppConfig)
    extends CRUD[T, Long]()(ec, appConfig)
    with TableAutoIncComponent[T] {
  import profile.api._

  /** The table inside our database we are inserting into */
  override val table: profile.api.TableQuery[_ <: TableAutoInc[T]]

  override def createAll(ts: Vector[T]): Future[Vector[T]] = {
    val idQuery = table.map(_.id)
    val idAutoInc = table.returning(idQuery)
    val query = {
      idAutoInc.into((t, id) => t.copyWithId(id = id))
    }
    val actions = query.++=(ts)
    safeDatabase.runVec(actions.transactionally)
  }

  // FIXME: This is a temporary fix for https://github.com/bitcoin-s/bitcoin-s/issues/1586
  // This is an inefficient solution that does each update individually
  override def updateAll(ts: Vector[T]): Future[Vector[T]] = {
    FutureUtil.foldLeftAsync(Vector.empty[T], ts) { (accum, t) =>
      super.updateAll(Vector(t)).map(accum ++ _)
    }
  }

  override def findByPrimaryKeys(
      ids: Vector[Long]): Query[TableAutoInc[T], T, Seq] = {
    table.filter { t =>
      t.id.inSet(ids)
    }
  }

  override def findAll(ts: Vector[T]): Query[Table[_], T, Seq] = {
    val ids = ts.filter(_.id.isDefined).map(_.id.get)
    findByPrimaryKeys(ids)
  }
}

/** Defines a table that has an auto incremented fields that is named id.
  * This is useful for things we want to store that don't have an
  * inherent id such as a hash.
  * @param tag
  * @param tableName
  * @tparam T
  */
trait TableAutoIncComponent[T <: DbRowAutoInc[T]] { self: CRUDAutoInc[T] =>
  import profile.api._

  abstract class TableAutoInc[T](tag: profile.api.Tag, tableName: String)
      extends profile.api.Table[T](tag, tableName) {
    def id: Rep[Long] = column[Long]("id", O.PrimaryKey, O.AutoInc)
  }
}
