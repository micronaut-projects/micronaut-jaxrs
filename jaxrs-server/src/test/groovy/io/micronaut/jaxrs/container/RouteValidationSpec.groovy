package io.micronaut.jaxrs.container

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec

/**
 * The resource methods routed at runtime are compiled with the route validation of the
 * controllers, which does not apply to them: a path variable of a method may be read from a field
 * or a bean parameter, or not at all.
 */
class RouteValidationSpec extends AbstractTypeElementSpec {

    void "a resource method is not validated as a controller method"() {
        when:
        def definition = buildBeanDefinition('test.Test', '''
package test;

import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

@Path("/test")
public class Test {

    @PathParam("id")
    String id;

    @GET
    @Path("/field/{id}")
    public String field() {
        return id;
    }

    @GET
    @Path("/bean/{id}")
    public String bean(@BeanParam Bean bean) {
        return bean.id;
    }

    @GET
    @Path("/unused/{other}")
    public String unused() {
        return "unused";
    }

    public static class Bean {
        @PathParam("id")
        String id;
    }
}
''')

        then:
        definition != null
    }
}
