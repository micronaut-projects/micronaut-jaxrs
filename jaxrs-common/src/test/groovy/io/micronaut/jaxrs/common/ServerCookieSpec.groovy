package io.micronaut.jaxrs.common

import spock.lang.Specification
import spock.lang.Unroll

class ServerCookieSpec extends Specification {

    @Unroll
    void "the value #value is quoted as #expected"(String value, String expected) {
        expect:
        ServerCookie.quoted(value) == expected

        where:
        value       || expected
        null        || '""'
        ''          || '""'
        'token'     || 'token'
        'a b'       || '"a b"'
        'a;b'       || '"a;b"'
        'a"b c'     || '"a\\"b c"'
        '"quoted"'  || '"quoted"'
    }

    void "a control character is rejected"() {
        when:
        ServerCookie.quoted("a\u0001b")

        then:
        thrown(IllegalArgumentException)
    }
}
