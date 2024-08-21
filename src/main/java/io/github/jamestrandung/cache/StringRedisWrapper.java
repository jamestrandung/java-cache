package io.github.jamestrandung.cache;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

public interface StringRedisWrapper<V> {
  CachedRecord<V> read(String key, boolean mustReadTTL);

  void write(String key, CachedRecord<V> record);

  void delete(String key);

  Set<String> searchForKeys(String pattern);

  class NoopStringRedisWrapper<V> implements StringRedisWrapper<V> {
    @Override
    public CachedRecord<V> read(String key, boolean mustReadTTL) {
      return null;
    }

    @Override
    public void write(String key, CachedRecord<V> record) {

    }

    @Override
    public void delete(String key) {

    }

    @Override
    public Set<String> searchForKeys(String pattern) {
      return Collections.emptySet();
    }
  }

  @AllArgsConstructor
  class RedisConfigs<V> {
    private final Class<V> clazz;
    private final StringRedisTemplate template;

    public static <V> RedisConfigs<V> create(Class<V> clazz, StringRedisTemplate template) {
      return new RedisConfigs<>(clazz, template);
    }
  }

  @Slf4j
  @AllArgsConstructor
  class BasicStringRedisWrapper<V> implements StringRedisWrapper<V> {
    private static final ObjectMapper MAPPER;

    static {
      MAPPER = new ObjectMapper();
      MAPPER.setSerializationInclusion(JsonInclude.Include.NON_NULL);
      MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
      MAPPER.registerModule(new JavaTimeModule());
    }

    private final Class<V> clazz;
    private final RedisOperations<String, String> redisOps;
    private final boolean hasInMemoryLayer;

    public static <V> BasicStringRedisWrapper<V> create(RedisConfigs<V> configs, boolean hasInMemoryLayer) {
      return new BasicStringRedisWrapper<>(configs.clazz, configs.template, hasInMemoryLayer);
    }

    @Override
    public CachedRecord<V> read(String key, boolean mustReadTTL) {
      try {
        String redisValue = this.redisOps.opsForValue().get(key);
        if (StringUtils.isBlank(redisValue)) {
          return null;
        }

        Long remainingTTL = null;
        if (this.hasInMemoryLayer || mustReadTTL) {
          remainingTTL = this.redisOps.getExpire(key);
        }

        return CachedRecord.<V>builder()
            .value(MAPPER.readValue(redisValue, clazz))
            .ttl(remainingTTL == null ? Duration.ZERO : Duration.ofSeconds(remainingTTL))
            .createdTime(remainingTTL == null ? 0 : System.currentTimeMillis() - Duration.ofSeconds(remainingTTL).toMillis())
            .build();

      } catch (Exception ex) {
        log.error("BasicStringRedisWrapper.get failed, key: {}, error: {}", key, ExceptionUtils.getStackTrace(ex));

        return null;
      }
    }

    @Override
    public void write(String key, CachedRecord<V> record) {
      try {
        if (record == null) {
          return;
        }

        // This key-value pair will live until it is either
        // explicitly deleted or a new value is set.
        if (!record.hasTTL()) {
          this.redisOps.opsForValue().set(key, MAPPER.writeValueAsString(record.getValue()));
          return;
        }

        this.redisOps.opsForValue().set(key, MAPPER.writeValueAsString(record.getValue()), record.getTtl());

      } catch (Exception ex) {
        log.error("BasicStringRedisWrapper.set failed, key: {}, value: {}, error: {}", key, record, ExceptionUtils.getStackTrace(ex));
      }
    }

    @Override
    public void delete(String key) {
      try {
        this.redisOps.delete(key);

      } catch (Exception ex) {
        log.error("BasicStringRedisWrapper.delete failed, key: {}, error: {}", key, ExceptionUtils.getStackTrace(ex));
      }
    }

    @Override
    public Set<String> searchForKeys(String pattern) {
      Instant     start   = Instant.now();
      ScanOptions options = ScanOptions.scanOptions().match(pattern).build();

      try (Cursor<byte[]> cursor = this.redisOps.executeWithStickyConnection(redisConnection -> redisConnection.scan(options))) {
        if (cursor == null) {
          return Collections.emptySet();
        }

        Set<String> keys = new HashSet<>();
        while (cursor.hasNext()) {
          keys.add(new String(cursor.next()));
        }

        log.info(
            "BasicStringRedisWrapper.searchForKeys completed, pattern: {}, taken: {}ms, keys: {}",
            pattern, Duration.between(start, Instant.now()).toMillis(), keys
        );

        return keys;
      }
    }
  }
}
