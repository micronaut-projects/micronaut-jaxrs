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

    void "the fields of #directive are written as one directive"() {
        given:
        def cacheControl = new CacheControl()
        cacheControl.noTransform = false
        if (directive == "no-cache") {
            cacheControl.noCache = true
            cacheControl.noCacheFields.addAll(["Set-Cookie", "Authorization"])
        } else {
            cacheControl.private = true
            cacheControl.privateFields.addAll(["Set-Cookie", "Authorization"])
        }

        expect:
        CacheControlDelegate.INSTANCE.toString(cacheControl) == "$directive=\"Set-Cookie, Authorization\""

        where:
        directive << ["no-cache", "private"]
    }

    void "an extension value is written as a token when it is one"() {
        given:
        def cacheControl = new CacheControl()
        cacheControl.noTransform = false
        cacheControl.cacheExtension.put("stale-while-revalidate", "60")
        cacheControl.cacheExtension.put("ext", "a, b")

        expect:
        def written = CacheControlDelegate.INSTANCE.toString(cacheControl)
        written.contains("stale-while-revalidate=60")
        written.contains('ext="a, b"')
    }
}
