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
}
