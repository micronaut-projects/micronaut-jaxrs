package io.micronaut.jaxrs.runtime.core

import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpRequest
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.jaxrs.container.JaxRsResourceInfo
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.container.ResourceInfo
import spock.lang.Specification

@MicronautTest
@Property(name = "spec.name", value = "ResourceInfoSpec")
class ResourceInfoSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    void "resource info is correctly populated"() {
        expect:
        client.toBlocking().retrieve("/api/info/test", String) == 'io.micronaut.jaxrs.runtime.core.ResourceInfoSpec$ResourceInfoResource:getTest'
        client.toBlocking().retrieve(HttpRequest.POST("/api/info/test", "")) == 'io.micronaut.jaxrs.runtime.core.ResourceInfoSpec$ResourceInfoResource:postTest'
    }

    @Path("/info")
    @Requires(property = "spec.name", value = "ResourceInfoSpec")
    static class ResourceInfoResource {

        private final ResourceInfo resourceInfo

        ResourceInfoResource(JaxRsResourceInfo resourceInfo) {
            this.resourceInfo = resourceInfo
        }

        @Path("/test")
        @GET
        String getTest() {
            "${resourceInfo.resourceClass.name}:${resourceInfo.resourceMethod.name}"
        }

        @Path("/test")
        @POST
        String postTest() {
            "${resourceInfo.resourceClass.name}:${resourceInfo.resourceMethod.name}"
        }
    }
}
