package io.micronaut.jaxrs.common.reflect

import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.HttpMethod
import jakarta.ws.rs.NameBinding
import spock.lang.Specification

import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy

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

    void "a method without JAX-RS annotations inherits the name bindings and request method designators"() {
        given:
        def reflection = new ReflectiveJaxRsReflection()

        expect:
        reflection.methodAnnotations(SecureImpl, "get").hasAnnotation(GET)
        reflection.methodAnnotations(SecureImpl, "get").hasAnnotation(Secured)
        reflection.methodAnnotations(SecureImpl, "report").hasAnnotation(Report)
        // a method with JAX-RS annotations of its own inherits none
        !reflection.methodAnnotations(SecureOwn, "get").hasAnnotation(Secured)
    }

    void "a generic override inherits the annotations of the method it overrides"() {
        given:
        def reflection = new ReflectiveJaxRsReflection()
        def metadata = reflection.methodAnnotations(GenericImpl, "get", String)

        expect:
        metadata.hasAnnotation(GET)
        metadata.stringValue(Produces).get() == "text/plain"
    }

    void "a private valueOf helper is not the String conversion, the public constructor is"() {
        when:
        def conversion = new ReflectiveJaxRsReflection().stringConversion(PrivateValueOfParam)

        then:
        conversion.apply("x").value() == "x"
    }

    void "the annotations of a method handed over include the ones it inherits"() {
        expect:
        new ReflectiveJaxRsReflection().annotationMetadata(PathImpl.getMethod("get")).stringValue(Path).get() == "sub"
    }

    static interface PathApi {
        @GET
        @Path("sub")
        String get()
    }

    static class PathImpl implements PathApi {
        @Override
        String get() {
            "x"
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    @NameBinding
    static @interface Secured {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @HttpMethod("REPORT")
    static @interface Report {
    }

    static interface SecureApi {
        @GET
        @Secured
        String get()

        @Report
        String report()
    }

    static class SecureImpl implements SecureApi {
        String get() { "" }

        String report() { "" }
    }

    static class SecureOwn implements SecureApi {
        @POST
        String get() { "" }

        String report() { "" }
    }

    static interface GenericApi<T> {
        @GET
        @Produces("text/plain")
        String get(T value)
    }

    static class GenericImpl implements GenericApi<String> {
        String get(String value) { value }
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
