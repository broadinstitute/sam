package org.broadinstitute.dsde.workbench.sam.util.cache
import cats.effect.{IO, Resource}
import cats.implicits._
import redis.clients.jedis.params.SetParams
import redis.clients.jedis.{Jedis, JedisPool}

import scala.collection.JavaConverters._

object RedisCacheInterpreters {
  val mgetBatchSize = 1000

  def ioRedisCache[K, V](jedisPool: JedisPool,
                         serializeKey: K => String,
                         serializeValue: V => String,
                         deserializeValue: String => V,
                         timeToLiveSeconds: Int
                        ): Resource[IO, Cache[IO, K, V]] = {
    val cache: Cache[IO, K, V] = new Cache[IO, K, V] {
      private val jedisResource = Resource.make[IO, Jedis](IO(jedisPool.getResource))(j => IO(j.close()))

      override def get(key: K): IO[Option[V]] = {
        jedisResource.use { jedis =>
          for {
            valueRaw <- IO(jedis.get(serializeKey(key)))
          } yield {
            Option(valueRaw).map(deserializeValue)
          }
        }
      }

      override def getAll(keys: K*): IO[Map[K, V]] = {
        jedisResource.use { jedis =>
          for {
            batches <- keys.grouped(mgetBatchSize).toList.traverse { keyBatch =>
              fetchEntries(jedis, keyBatch)
            }
          } yield batches.reduce(_ ++ _)
        }
      }

      private def fetchEntries(jedis: Jedis, keyBatch: Seq[K]): IO[Map[K, V]] = {
        IO {
          val values = jedis.mget(keyBatch.map(serializeKey): _*).asScala
          val results = for {
            (key, valueRaw) <- keyBatch.zip(values)
            if valueRaw != null
            value = deserializeValue(valueRaw)
          } yield key -> value
          results.toMap
        }
      }

      override def put(key: K, value: V): IO[Unit] = {
        jedisResource.use { jedis =>
          IO(jedis.set(serializeKey(key), serializeValue(value), SetParams.setParams().ex(timeToLiveSeconds)))
        }
      }

      override def remove(key: K): IO[Unit] = {
        jedisResource.use { jedis =>
          IO(jedis.del(serializeKey(key)))
        }
      }
    }

    Resource.make(IO(cache))(_ => IO.unit)
  }
}
