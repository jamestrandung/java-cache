package io.github.jamestrandung.cache;

import com.github.benmanes.caffeine.cache.Cache;
import java.util.function.Supplier;
import lombok.AllArgsConstructor;

public interface CaffeineWrapper<V> {
  CachedRecord<V> read(String key, Supplier<? extends CachedRecord<V>> supplier);

  void write(String key, CachedRecord<V> record);

  void delete(String key);

  class NoopCaffeineWrapper<V> implements CaffeineWrapper<V> {
    @Override
    public CachedRecord<V> read(String key, Supplier<? extends CachedRecord<V>> supplier) {
      return supplier.get();
    }

    @Override
    public void write(String key, CachedRecord<V> record) {

    }

    @Override
    public void delete(String key) {

    }
  }

  @AllArgsConstructor
  class BasicCaffeineWrapper<V> implements CaffeineWrapper<V> {
    private final Cache<String, CachedRecord<V>> cache;

    public static <V> BasicCaffeineWrapper<V> create(Cache<String, CachedRecord<V>> cache) {
      return new BasicCaffeineWrapper<>(cache);
    }

    @Override
    public CachedRecord<V> read(String key, Supplier<? extends CachedRecord<V>> supplier) {
      return this.cache.get(key, k -> supplier.get());
    }

    @Override
    public void write(String key, CachedRecord<V> record) {
      this.cache.put(key, record);
    }

    @Override
    public void delete(String key) {
      this.cache.invalidate(key);
    }
  }
}
