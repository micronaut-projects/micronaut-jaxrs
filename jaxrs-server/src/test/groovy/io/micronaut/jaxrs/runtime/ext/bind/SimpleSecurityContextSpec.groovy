package io.micronaut.jaxrs.runtime.ext.bind

import io.micronaut.http.HttpRequest
import jakarta.ws.rs.core.SecurityContext
import spock.lang.Specification

import java.security.Principal

class SimpleSecurityContextSpec extends Specification {

    void "unverified credentials of an anonymous request are no authentication scheme"() {
        given:
        def request = HttpRequest.GET("/").basicAuth("fred", "wrong")

        expect:
        new SimpleSecurityContextImpl(request).authenticationScheme == null
    }

    void "the scheme of an authenticated request is the one of its credentials"() {
        given:
        def request = HttpRequest.GET("/").basicAuth("fred", "ok")
        request.setAttribute(io.micronaut.http.HttpAttributes.PRINCIPAL, { "fred" } as Principal)

        expect:
        new SimpleSecurityContextImpl(request).userPrincipal != null
        new SimpleSecurityContextImpl(request).authenticationScheme == SecurityContext.BASIC_AUTH
    }
}
