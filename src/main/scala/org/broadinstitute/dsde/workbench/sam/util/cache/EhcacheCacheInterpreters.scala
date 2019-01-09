package org.broadinstitute.dsde.workbench.sam.util.cache
import cats.effect.IO
import org.ehcache.config.builders.{CacheConfigurationBuilder, CacheManagerBuilder, ExpiryPolicyBuilder, ResourcePoolsBuilder}
import scala.collection.JavaConverters._

object EhcacheCacheInterpreters {
  def ioEhcache[K, V](cacheName: String, maxEntries: Long, timeToLive: java.time.Duration): cats.effect.Resource[IO, Cache[IO, K, V]] = {
    val cacheManager = CacheManagerBuilder.newCacheManagerBuilder
      .withCache(
        cacheName,
        CacheConfigurationBuilder
          .newCacheConfigurationBuilder(classOf[Object], classOf[Object], ResourcePoolsBuilder.heap(maxEntries))
          .withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(timeToLive))
      )
      .build

    cats.effect.Resource.make {
      IO {
        cacheManager.init()
        val underlyingCache = cacheManager.getCache(cacheName, classOf[Object], classOf[Object])
        new EhcacheCacheImpl[K, V](underlyingCache).asInstanceOf[Cache[IO, K, V]]
      }
    } { _ =>
      IO(cacheManager.close())
    }
  }
}

class EhcacheCacheImpl[K, V](underlyingCache: org.ehcache.Cache[Object, Object]) extends Cache[IO, K, V] {
  override def get(key: K): IO[Option[V]] = IO.pure(Option(underlyingCache.get(key.asInstanceOf[Object])).map(_.asInstanceOf[V]))
  override def put(key: K, value: V): IO[Unit] = IO.pure(underlyingCache.put(key.asInstanceOf[Object], value.asInstanceOf[Object]))
  override def remove(key: K): IO[Unit] = IO.pure(underlyingCache.remove(key.asInstanceOf[Object]))
  override def getAll(keys: K*): IO[Map[K, V]] = {
    val results = underlyingCache.getAll(keys.toSet.map{k:K => k.asInstanceOf[Object]}.asJava).asScala.filterNot {
      case (_, value) => value == null
    }.map {
      case (key, value) => key.asInstanceOf[K] -> value.asInstanceOf[V]
    }
    IO.pure(results.toMap)
  }
}