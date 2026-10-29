package io.micronaut.jaxrs.common.reflect

import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import spock.lang.Specification

class ReflectiveJaxRsReflectionSpec extends Specification {

    void "a method without JAX-RS annotations has the ones it inherits"() {
        given:
        def reflection = new ReflectiveJaxRsReflection()

        expect:
        reflection.methodAnnotations(Impl, "get").hasAnnotation(GET)
        reflection.methodAnnotations(Impl, "get").stringValue(Produces).get() == "text/plain"
        // a superclass first, then an interface
        reflection.methodAnnotations(SubImpl, "post").hasAnnotation(GET)
        !reflection.methodAnnotations(SubImpl, "post").hasAnnotation(POST)
        // a method with JAX-RS annotations of its own inherits none
        !reflection.methodAnnotations(Own, "get").hasAnnotation(GET)
    }

    static interface Api {
        @GET
        @Produces("text/plain")
        String get()

        @POST
        @Path("b")
        String post()
    }

    static class Impl implements Api {
        String get() { "" }

        String post() { "" }
    }

    static abstract class Base {
        @GET
        @Path("a")
        abstract String post()
    }

    static class SubImpl extends Base implements Api {
        String get() { "" }

        String post() { "" }
    }

    static class Own implements Api {
        @Produces("text/html")
        String get() { "" }

        String post() { "" }
    }
}
