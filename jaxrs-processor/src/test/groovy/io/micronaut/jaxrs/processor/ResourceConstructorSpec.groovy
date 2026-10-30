package io.micronaut.jaxrs.processor

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec
import io.micronaut.inject.ast.ClassElement

/**
 * The constructor JAX-RS creates a resource with per request (JAX-RS 3.1.2).
 */
class ResourceConstructorSpec extends AbstractTypeElementSpec {

    void "a constructor with a parameter that cannot be resolved is not selected"() {
        when:
        def constructor = buildClassElement('''
package test;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;

@Path("/mixedctor")
class MixedResource {
    public MixedResource() {
    }

    public MixedResource(@QueryParam("x") String x) {
    }

    public MixedResource(@QueryParam("x") String x, String y, String z) {
    }

    @GET
    public String get() {
        return "";
    }
}
''') { ClassElement type -> JaxRsResources.requestConstructor(type) }

        then:
        constructor.parameters.length == 1
    }

    void "a constructor with a parameter that may be a bean is selected"() {
        when:
        def constructor = buildClassElement('''
package test;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;

@Path("/beanctor")
class BeanResource {
    public BeanResource(@QueryParam("x") String x) {
    }

    public BeanResource(@QueryParam("x") String x, Service service) {
    }

    @GET
    public String get() {
        return "";
    }
}

@jakarta.inject.Singleton
class Service {
}
''') { ClassElement type -> JaxRsResources.requestConstructor(type) }

        then:
        constructor.parameters.length == 2
    }
}
