package io.github.jamestrandung.cache

import spock.lang.Specification

import java.time.Duration

class CaffeineRedisCacheTest extends Specification {
    def mappedKey = "mappedKey"
    def keyMapper = (String key) -> mappedKey
    def caffeine = Mock(CaffeineWrapper<String>)
    def redis = Mock(StringRedisWrapper<String>)
    def ttl = Duration.ofSeconds(30)

    def cache = Spy(new CaffeineRedisCache(
            keyMapper: keyMapper,
            inMemoryCache: caffeine,
            redis: redis,
            ttlSupplier: () -> ttl
    ))

    def "test warmUpInMemoryCache"() {
        given:
        def pattern = "pattern"
        def dummyKeys = ["key1", "key2"] as HashSet
        def dummyRecord = CachedRecord.builder().value("something").build()

        when:
        cache.warmUpInMemoryCache(pattern)

        then:
        1 * redis.searchForKeys(pattern) >> dummyKeys

        then:
        1 * redis.read("key1", false) >> null
        0 * caffeine.write("key1", _)

        and:
        1 * redis.read("key2", false) >> dummyRecord
        1 * caffeine.write("key2", dummyRecord)
    }

    def "test read, with acceptableReadTimestamp"() {
        given:
        def key = "key"
        def acceptableCreatedTimestamp = 999L
        def fn = k -> {
            return "value"
        }

        def newRecord = CachedRecord.builder().value("something").createdTime(System.currentTimeMillis()).build()
        def oldRecord = CachedRecord.builder().value("somethingElse").createdTime(123L).build()

        when:
        def result1 = cache.read(key, acceptableCreatedTimestamp, fn)

        then:
        1 * caffeine.read(mappedKey, _) >> newRecord

        and:
        result1 == newRecord.value

        when:
        def result2 = cache.read(key, acceptableCreatedTimestamp, fn)

        then:
        1 * caffeine.read(mappedKey, _) >> oldRecord

        then:
        1 * cache.write(key, "value")

        and:
        result2 == "value"
    }

    def "test readFromRedisOrCompute"() {
        given:
        def key = "key"
        def fn = k -> {
            return "value"
        }

        def dummyRecord = CachedRecord.builder().value("something").build()

        when: "Redis has key"
        def result = cache.readFromRedisOrCompute(key, false, fn)

        then:
        1 * redis.read(mappedKey, false) >> dummyRecord
        0 * redis.write(mappedKey, _)

        and:
        result == dummyRecord

        when: "Redis does not have key"
        def result2 = cache.readFromRedisOrCompute(key, false, fn)

        then:
        1 * redis.read(mappedKey, false) >> null
        1 * redis.write(mappedKey, _) >> {
            args ->
                {
                    CachedRecord<String> record = (CachedRecord<String>) args[1]
                    assert record.value == "value"
                    assert record.ttl == this.ttl
                }
        }

        and:
        result2.value == "value"
        result2.ttl == this.ttl
    }

    def "test write"() {
        given:
        def key = "key"
        def value = "value"

        when:
        cache.write(key, value)

        then:
        1 * caffeine.write(mappedKey, _) >> {
            args ->
                {
                    CachedRecord<String> record = (CachedRecord<String>) args[1]
                    assert record.value == "value"
                    assert record.ttl == this.ttl
                }
        }
        1 * redis.write(mappedKey, _) >> {
            args ->
                {
                    CachedRecord<String> record = (CachedRecord<String>) args[1]
                    assert record.value == "value"
                    assert record.ttl == this.ttl
                }
        }
    }
}
