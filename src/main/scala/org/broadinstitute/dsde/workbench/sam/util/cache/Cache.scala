package org.broadinstitute.dsde.workbench.sam.util.cache

import scala.language.higherKinds

trait Cache[F[_], K, V] {
  def get(key: K): F[Option[V]]
  def getAll(keys: K*): F[Map[K, V]]
  def put(key: K, value: V): F[Unit]
  def remove(key: K): F[Unit]
  def removeAll(keys: K*): F[Unit]
}
