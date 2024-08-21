package io.github.jamestrandung.cache;

import java.time.Duration;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Builder
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class CachedRecord<V> {
  private V value;
  private Duration ttl;
  private long createdTime;

  public boolean hasTTL() {
    return this.ttl != null && !this.ttl.isZero();
  }

  public long getTTLInNanoseconds() {
    if (!this.hasTTL()) {
      return Long.MAX_VALUE;
    }

    return this.ttl.toNanos();
  }

  public boolean isCreatedOnOrAfter(long timestamp) {
    return this.createdTime >= timestamp;
  }
}
