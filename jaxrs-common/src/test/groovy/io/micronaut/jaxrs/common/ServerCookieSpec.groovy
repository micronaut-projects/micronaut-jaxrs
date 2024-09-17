package io.micronaut.jaxrs.common

import spock.lang.Specification
import spock.lang.Unroll

class ServerCookieSpec extends Specification {

    @Unroll
    void "it can append cookie values when isSecure is #isSecure"(boolean isSecure, String expected) {
        given:
        StringBuilder sb = new StringBuilder()

        when:
        ServerCookie.appendCookieValue(sb, 1, "name", "value", "path", "domain", "comment", 0, isSecure)

        then:
        sb.toString() == expected

        where:
        isSecure || expected
        true     || "name=value; Version=1; Comment=comment; Domain=domain; Max-Age=0; Path=path; Secure"
        false    || "name=value; Version=1; Comment=comment; Domain=domain; Max-Age=0; Path=path"
    }

    @Unroll
    void "it can get cookie header name for version #version"(int version, String expected) {
        expect:
        ServerCookie.getCookieHeaderName(version) == expected

        where:
        version || expected
        0       || "Set-Cookie"
        1       || "Set-Cookie"
        2       || "Set-Cookie"
    }

}
