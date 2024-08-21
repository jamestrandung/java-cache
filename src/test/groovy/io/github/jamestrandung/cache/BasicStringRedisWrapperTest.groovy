package io.github.jamestrandung.cache

import org.springframework.data.redis.core.RedisOperations
import org.springframework.data.redis.core.ValueOperations
import spock.lang.Specification

import java.time.Duration

class BasicStringRedisWrapperTest extends Specification {
    static class Dummy {
        String something
    }

    def valueOps = Mock(ValueOperations)
    def redisOps = Mock(RedisOperations<String, String>) {
        opsForValue() >> valueOps
    }

    def wrapper = new StringRedisWrapper.BasicStringRedisWrapper(Dummy.class, redisOps, false)
    def wrapperWithInMemoryLayer = new StringRedisWrapper.BasicStringRedisWrapper(Dummy.class, redisOps, true)

    def "test read"() {
        given:
        def key = "key"
        def dummyValue = "{\"something\":\"text\"}"
        def dummyTTL = 123L

        when: "key doesn't exist or value is blank"
        def result = wrapperWithInMemoryLayer.read(key, false)

        then:
        1 * valueOps.get(key) >> noRedisKeyValue

        and:
        result == null

        when: "key doesn't exist or value is blank"
        def result2 = wrapper.read(key, false)

        then:
        1 * valueOps.get(key) >> noRedisKeyValue

        and:
        result2 == null

        when: "key exists, mustReadTTL == false"
        def result3 = wrapperWithInMemoryLayer.read(key, false)

        then:
        1 * valueOps.get(key) >> dummyValue
        1 * redisOps.getExpire(key) >> dummyTTL

        and:
        result3.value.something == "text"
        result3.ttl == Duration.ofSeconds(dummyTTL)

        when: "key exists, mustReadTTL == false"
        def result4 = wrapper.read(key, false)

        then:
        1 * valueOps.get(key) >> dummyValue

        and:
        result4.value.something == "text"
        result4.ttl == Duration.ZERO

        when: "key exists, mustReadTTL == true"
        def result5 = wrapper.read(key, true)

        then:
        1 * valueOps.get(key) >> dummyValue
        1 * redisOps.getExpire(key) >> dummyTTL

        and:
        result5.value.something == "text"
        result5.ttl == Duration.ofSeconds(dummyTTL)

        where:
        noRedisKeyValue << [null, "", " "]
    }

    def "test write"() {
        given:
        def key = "key"
        def recordWithTTL = CachedRecord.builder()
                .value(new Dummy(something: "text"))
                .ttl(Duration.ofSeconds(123L))
                .build()
        def recordWithoutTTL = CachedRecord.builder()
                .value(new Dummy(something: "text"))
                .ttl(null)
                .build()

        when:
        wrapper.write(key, null)

        then:
        0 * valueOps.set(_, _)
        0 * valueOps.set(_, _, _)

        when:
        wrapper.write(key, recordWithTTL)

        then:
        0 * valueOps.set(_, _)
        1 * valueOps.set(key, "{\"something\":\"text\"}", recordWithTTL.getTtl())

        when:
        wrapper.write(key, recordWithoutTTL)

        then:
        1 * valueOps.set(key, "{\"something\":\"text\"}")
        0 * valueOps.set(_, _, _)
    }
}
