package io.micronaut.jaxrs.common

import jakarta.ws.rs.core.CacheControl
import spock.lang.Specification

class CacheControlSpec extends Specification {

    void "the quoted field list of #directive keeps all its field names"() {
        when:
        def cacheControl = CacheControlDelegate.INSTANCE.fromString("$directive=\"Authorization, X-Secret\", max-age=10")

        then:
        fields(cacheControl) == ['Authorization', 'X-Secret']
        cacheControl.maxAge == 10
        cacheControl.cacheExtension.isEmpty()

        when:
        def reparsed = CacheControlDelegate.INSTANCE.fromString(CacheControlDelegate.INSTANCE.toString(cacheControl))

        then:
        fields(reparsed) == ['Authorization', 'X-Secret']

        where:
        directive  | fields
        'private'  | { CacheControl c -> c.privateFields }
        'no-cache' | { CacheControl c -> c.noCacheFields }
    }

    void "an extension keeps a quoted value with a comma"() {
        when:
        def cacheControl = CacheControlDelegate.INSTANCE.fromString('ext="a, b", no-store')

        then:
        cacheControl.cacheExtension == [ext: 'a, b']
        cacheControl.noStore
    }
}
