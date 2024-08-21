package io.github.jamestrandung.cache;

import java.util.function.Function;

public interface MultiLayerCache<K, V> {
  /**
   * Warm up the in-memory cache by searching for the given key pattern in Redis.
   *
   * @param warmUpKeyPattern
   */
  void warmUpInMemoryCache(String warmUpKeyPattern);

  /**
   * Read the given key from the cache. If this key is not found, or the value was written into cache before the acceptable created
   * timestamp, the given function will be called to compute the latest value.
   *
   * @param key                        the key to read
   * @param acceptableCreatedTimestamp the earliest timestamp that the value was written into cache to be considered valid
   * @param function                   the function to compute the value associated with the given key
   * @return the value associated with the given key
   */
  V read(K key, long acceptableCreatedTimestamp, Function<K, ? extends V> function);

  /**
   * Read the given key from the cache. If this key is not found, the given function will be called to compute a value.
   *
   * @param key      the key to read
   * @param function the function to compute the value associated with the given key
   * @return the value associated with the given key
   */
  V read(K key, Function<K, ? extends V> function);

  /**
   * Read the given key from the cache.
   *
   * @param key the key to read
   * @return the value associated with the given key, or null if the key is not found
   */
  V read(K key);

  /**
   * Write the given key-value pair into the cache.
   *
   * @param key   the key to write
   * @param value the value associated with the given key
   */
  void write(K key, V value);

  /**
   * Delete the given key from the cache.
   *
   * @param key the key to delete
   */
  void delete(K key);
}
