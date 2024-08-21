package io.github.jamestrandung.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import io.github.jamestrandung.cache.CaffeineWrapper.BasicCaffeineWrapper;
import io.github.jamestrandung.cache.CaffeineWrapper.NoopCaffeineWrapper;
import io.github.jamestrandung.cache.StringRedisWrapper.BasicStringRedisWrapper;
import io.github.jamestrandung.cache.StringRedisWrapper.NoopStringRedisWrapper;
import io.github.jamestrandung.cache.StringRedisWrapper.RedisConfigs;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.index.qual.NonNegative;

@Slf4j
@NoArgsConstructor
public class CaffeineRedisCache<K, V> implements MultiLayerCache<K, V> {
  public static final UnaryOperator<Caffeine<Object, Object>> NOOP_CAFFEINE_CONFIGURATOR = UnaryOperator.identity();
  private Function<K, String> keyMapper;
  private CaffeineWrapper<V> inMemoryCache;
  private StringRedisWrapper<V> redis;
  private Supplier<Duration> ttlSupplier;

  public CaffeineRedisCache(
      Function<K, String> keyMapper,
      UnaryOperator<Caffeine<Object, Object>> caffeineConfigurator,
      RedisConfigs<V> configs,
      Supplier<Duration> ttlSupplier
  ) {
    this.keyMapper = keyMapper;
    this.inMemoryCache = this.createCaffeineWrapper(caffeineConfigurator);
    this.redis = this.createRedisWrapper(configs, caffeineConfigurator != null);
    this.ttlSupplier = ttlSupplier;
  }

  CaffeineWrapper<V> createCaffeineWrapper(UnaryOperator<Caffeine<Object, Object>> caffeineConfigurator) {
    if (caffeineConfigurator == null) {
      return new NoopCaffeineWrapper<>();
    }

    Cache<String, CachedRecord<V>> cache = caffeineConfigurator.apply(Caffeine.newBuilder())
        .expireAfter(new Expiry<String, CachedRecord<V>>() {
          @Override
          public long expireAfterCreate(String key, CachedRecord<V> record, long currentTime) {
            return record.getTTLInNanoseconds();
          }

          @Override
          public long expireAfterUpdate(String key, CachedRecord<V> record, long currentTime, @NonNegative long currentDuration) {
            return record.getTTLInNanoseconds();
          }

          @Override
          public long expireAfterRead(String key, CachedRecord<V> record, long currentTime, @NonNegative long currentDuration) {
            return currentDuration;
          }
        })
        .build();

    return BasicCaffeineWrapper.create(cache);
  }

  StringRedisWrapper<V> createRedisWrapper(RedisConfigs<V> configs, boolean hasInMemoryLayer) {
    if (configs == null) {
      return new NoopStringRedisWrapper<>();
    }

    return BasicStringRedisWrapper.create(configs, hasInMemoryLayer);
  }

  public void warmUpInMemoryCache(String warmUpKeyPattern) {
    Instant start = Instant.now();

    this.redis.searchForKeys(warmUpKeyPattern)
        .forEach(key -> {
          CachedRecord<V> record = this.redis.read(key, false);
          if (record != null) {
            this.inMemoryCache.write(key, record);
          }
        });

    log.info(
        "CaffeineRedisCache.warmUpInMemoryCache completed, key pattern: {}, taken: {}ms",
        warmUpKeyPattern, Duration.between(start, Instant.now()).toMillis()
    );
  }

  @Override
  public V read(K key, long acceptableCreatedTimestamp, Function<K, ? extends V> function) {
    String mappedKey = this.keyMapper.apply(key);

    CachedRecord<V> record = this.inMemoryCache.read(mappedKey, () -> this.readFromRedisOrCompute(key, true, function));
    if (record != null && record.isCreatedOnOrAfter(acceptableCreatedTimestamp)) {
      return record.getValue();
    }

    V newValue = function.apply(key);
    this.write(key, newValue);

    return newValue;
  }

  public V read(K key, Function<K, ? extends V> function) {
    String mappedKey = this.keyMapper.apply(key);

    CachedRecord<V> record = this.inMemoryCache.read(mappedKey, () -> this.readFromRedisOrCompute(key, false, function));
    return record == null ? null : record.getValue();
  }

  CachedRecord<V> readFromRedisOrCompute(K key, boolean mustReadTTL, Function<K, ? extends V> function) {
    String mappedKey = this.keyMapper.apply(key);

    CachedRecord<V> redisRecord = this.redis.read(mappedKey, mustReadTTL);
    if (redisRecord != null) {
      return redisRecord;
    }

    CachedRecord<V> computedRecord = CachedRecord.<V>builder()
        .value(function.apply(key))
        .ttl(this.ttlSupplier.get())
        .createdTime(System.currentTimeMillis())
        .build();

    this.redis.write(mappedKey, computedRecord);

    return computedRecord;
  }

  public V read(K key) {
    String mappedKey = this.keyMapper.apply(key);

    CachedRecord<V> record = this.inMemoryCache.read(mappedKey, () -> this.redis.read(mappedKey, false));
    return record == null ? null : record.getValue();
  }

  public void write(K key, V value) {
    String mappedKey = this.keyMapper.apply(key);

    CachedRecord<V> record = CachedRecord.<V>builder()
        .value(value)
        .ttl(this.ttlSupplier.get())
        .createdTime(System.currentTimeMillis())
        .build();

    this.inMemoryCache.write(mappedKey, record);
    this.redis.write(mappedKey, record);
  }

  @Override
  public void delete(K key) {
    String mappedKey = this.keyMapper.apply(key);

    this.inMemoryCache.delete(mappedKey);
    this.redis.delete(mappedKey);
  }
}
