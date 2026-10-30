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

    void "an extension value with a backslash or a quote is escaped"() {
        given:
        def cacheControl = new CacheControl()
        cacheControl.cacheExtension.put("ext", value)

        when:
        def header = CacheControlDelegate.INSTANCE.toString(cacheControl)

        then:
        CacheControlDelegate.INSTANCE.fromString(header).cacheExtension.get("ext") == value

        where:
        value << ['a\\b', 'a"b', 'a\\"b, c']
    }

    void "an extension keeps a quoted value with a comma"() {
        when:
        def cacheControl = CacheControlDelegate.INSTANCE.fromString('ext="a, b", no-store')

        then:
        cacheControl.cacheExtension == [ext: 'a, b']
        cacheControl.noStore
    }

    void "directive names are parsed whatever the default locale"() {
        given:
        def locale = Locale.default
        Locale.default = Locale.forLanguageTag("tr")

        expect:
        CacheControlDelegate.INSTANCE.fromString("PRIVATE").private

        cleanup:
        Locale.default = locale
    }
}
