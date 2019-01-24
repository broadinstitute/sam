package org.broadinstitute.dsde.workbench.sam.util.cache
import cats.effect.{IO, Resource}
import cats.implicits._
import redis.clients.jedis.params.SetParams
import redis.clients.jedis.{Jedis, JedisPool}

import scala.collection.JavaConverters._

object RedisCacheInterpreters {
  def ioRedisCache[K, V](
      jedisPool: JedisPool,
      keyPrefix: String,
      serializeKey: K => String,
      serializeValue: V => String,
      deserializeValue: String => V,
      timeToLiveSeconds: Int): Resource[IO, Cache[IO, K, V]] = {

    val cache: Cache[IO, K, V] = new RedisCacheImpl(jedisPool, keyPrefix, serializeKey, serializeValue, deserializeValue, timeToLiveSeconds)
    Resource.make(IO(cache))(_ => IO.unit)
  }
}

class RedisCacheImpl[K, V](jedisPool: JedisPool,
                           keyPrefix: String,
                           serializeKey: K => String,
                           serializeValue: V => String,
                           deserializeValue: String => V,
                           timeToLiveSeconds: Int) extends Cache[IO, K, V] {
  val mgetBatchSize = 1000
  val delBatchSize = 1000

  private val jedisResource = Resource.make[IO, Jedis](IO(jedisPool.getResource))(j => IO(j.close()))

  def createRedisKey(key: K) = {
    keyPrefix + serializeKey(key)
  }

  override def get(key: K): IO[Option[V]] = {
    jedisResource.use { jedis =>
      for {
        valueRaw <- IO(jedis.get(createRedisKey(key)))
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
      val values = jedis.mget(keyBatch.map(createRedisKey): _*).asScala
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
      IO(jedis.set(createRedisKey(key), serializeValue(value), SetParams.setParams().ex(timeToLiveSeconds)))
    }
  }

  override def remove(key: K): IO[Unit] = removeAll(key)

  override def removeAll(keys: K*): IO[Unit] = {
    jedisResource.use { jedis =>
      keys.grouped(delBatchSize).toList.traverse { keyBatch =>
        IO(jedis.del(keyBatch.map(createRedisKey):_*))
      }.void
    }
  }
}