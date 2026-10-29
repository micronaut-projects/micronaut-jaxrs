package io.micronaut.jaxrs.processor

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.HttpMethodMapping
import io.micronaut.http.annotation.Post
import jakarta.ws.rs.GET
import jakarta.ws.rs.HttpMethod
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam

/**
 * The annotation inheritance of JAX-RS 3.6: the JAX-RS annotations of an overridden method and of
 * its parameters are inherited only when the overriding method declares none of its own.
 */
class AnnotationInheritanceSpec extends AbstractTypeElementSpec {

    void "an override without JAX-RS annotations inherits those of the overridden method"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/test")
class Test extends Base {

    @Override
    public String test(String q) {
        return q;
    }
}

abstract class Base {

    @jakarta.ws.rs.GET
    @jakarta.ws.rs.Path("/base")
    public abstract String test(@jakarta.ws.rs.QueryParam("q") String q);
}
""")
        def method = definition.getRequiredMethod("test", String)

        expect:
        method.hasStereotype(HttpMethod)
        method.hasAnnotation(GET)
        method.annotationMetadata.declaredMetadata.stringValue(Path).get() == "/base"
        method.arguments[0].annotationMetadata.stringValue(QueryParam).get() == 'q'
    }

    void "an override declaring a JAX-RS annotation discards the inherited ones"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/test")
class Test extends Base {

    @Override
    @jakarta.ws.rs.Produces("text/plain")
    public String test(String q) {
        return q;
    }
}

abstract class Base {

    @jakarta.ws.rs.GET
    @jakarta.ws.rs.Path("/base")
    public abstract String test(@jakarta.ws.rs.QueryParam("q") String q);
}
""")
        def method = definition.findMethod("test", String).orElse(null)

        expect:
        method == null || !method.hasStereotype(HttpMethod) && !method.hasAnnotation(GET)
            && !method.hasStereotype(Get) && !method.annotationMetadata.declaredMetadata.hasAnnotation(Path) && !method.annotationMetadata.declaredMetadata.hasStereotype(HttpMethodMapping)
            && !method.arguments[0].annotationMetadata.hasAnnotation(QueryParam)
    }

    void "an override declaring a JAX-RS parameter annotation discards the inherited method annotations"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/test")
class Test implements Api {

    @Override
    @jakarta.ws.rs.POST
    public String test(@jakarta.ws.rs.HeaderParam("h") String q) {
        return q;
    }
}

interface Api {

    @jakarta.ws.rs.GET
    @jakarta.ws.rs.Path("/base")
    @jakarta.ws.rs.Produces("text/html")
    String test(@jakarta.ws.rs.QueryParam("q") String q);
}
""")
        def method = definition.getRequiredMethod("test", String)

        expect:
        method.hasStereotype(Post)
        !method.hasAnnotation(GET)
        !method.hasStereotype(Get)
        !method.annotationMetadata.declaredMetadata.hasAnnotation(Path)
        !method.hasAnnotation(Produces)
        !method.arguments[0].annotationMetadata.hasAnnotation(QueryParam)
        method.arguments[0].annotationMetadata.stringValue(jakarta.ws.rs.HeaderParam).get() == 'h'
    }

    void "the class keeps the @Path of its superclass"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

class Test extends Base {

    @Override
    @jakarta.ws.rs.GET
    public String test() {
        return "";
    }
}

@jakarta.ws.rs.Path("/base")
abstract class Base {

    public abstract String test();
}
""")

        expect:
        definition.stringValue(Path).get() == '/base'
    }

    void "an override inherits the annotations of the nearest method that declares any"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/test")
class Test extends Middle {

    @Override
    public String test() {
        return "";
    }
}

abstract class Middle extends Base {

    @Override
    @jakarta.ws.rs.Produces("text/plain")
    public abstract String test();
}

abstract class Base {

    @jakarta.ws.rs.GET
    public abstract String test();
}
""")
        def method = definition.findMethod("test").orElse(null)

        expect:
        method == null || !method.hasStereotype(HttpMethod) && !method.hasAnnotation(GET) && !method.hasStereotype(Get)
    }

    void "the annotations of a superclass method take precedence over those of an interface method"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/test")
class Test extends Base implements Api {

    @Override
    public String test() {
        return "";
    }
}

abstract class Base {

    @jakarta.ws.rs.GET
    @jakarta.ws.rs.Path("/a")
    public abstract String test();
}

interface Api {

    @jakarta.ws.rs.POST
    @jakarta.ws.rs.Path("/b")
    String test();
}
""")
        def method = definition.getRequiredMethod("test")

        expect:
        method.hasAnnotation(GET)
        method.hasStereotype(Get)
        !method.hasAnnotation(jakarta.ws.rs.POST)
        !method.hasStereotype(Post)
        method.stringValue(Path).get() == "/a"
        method.stringValue(HttpMethod).get() == "GET"
    }

    void "an override with a custom request method designator discards the inherited annotations"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/test")
class Test extends Base {

    @Override
    @Patch
    public String test() {
        return "";
    }
}

abstract class Base {

    @jakarta.ws.rs.GET
    @jakarta.ws.rs.Path("/base")
    public abstract String test();
}

@java.lang.annotation.Target(java.lang.annotation.ElementType.METHOD)
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
@jakarta.ws.rs.HttpMethod("PATCH")
@interface Patch {
}
""")
        def method = definition.getRequiredMethod("test")

        expect:
        method.stringValue(HttpMethod).get() == "PATCH"
        !method.hasAnnotation(GET)
        !method.annotationMetadata.declaredMetadata.hasAnnotation(Path)
    }

    void "a subclass does not inherit the media types of its superclass"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/test")
class Test extends Base {

    @jakarta.ws.rs.POST
    public String test(String body) {
        return body;
    }
}

@jakarta.ws.rs.Path("/base")
@jakarta.ws.rs.Consumes("application/json")
@jakarta.ws.rs.Produces("application/json")
class Base {
}
""")
        def method = definition.getRequiredMethod("test", String)

        expect:
        !definition.hasAnnotation(jakarta.ws.rs.Consumes)
        !definition.hasAnnotation(jakarta.ws.rs.Produces)
        method.stringValues(io.micronaut.http.annotation.Consumes) as List == ["*/*"]
        method.stringValues(io.micronaut.http.annotation.Produces) as List == ["*/*"]
        definition.stringValue(Path).get() == "/test"
    }
}
