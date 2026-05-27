package io.micronaut.jaxrs.processor

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec
import io.micronaut.context.annotation.Parameter
import io.micronaut.context.annotation.Prototype
import io.micronaut.core.annotation.ReflectiveAccess
import io.micronaut.core.bind.annotation.Bindable
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.CookieValue
import io.micronaut.http.annotation.CustomHttpMethod
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Header
import io.micronaut.http.annotation.HttpMethodMapping
import io.micronaut.http.annotation.PathVariable
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.QueryValue
import jakarta.ws.rs.BeanParam
import jakarta.ws.rs.CookieParam
import jakarta.ws.rs.Encoded
import jakarta.ws.rs.FormParam
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.MatrixParam
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.QueryParam
import spock.lang.Unroll

class ParameterAnnotationSpec extends AbstractTypeElementSpec {

    static final String REQUEST_FIELD_INJECTION_ANNOTATION = "io.micronaut.jaxrs.container.JaxRsRequestFieldInjection"
    static final String CONSTRUCTOR_INJECTION_ANNOTATION = "io.micronaut.jaxrs.container.JaxRsConstructorInjection"
    static final String PATH_PARAM_BINDING_ANNOTATION = "io.micronaut.jaxrs.container.JaxRsPathParamBinding"
    static final String SUB_RESOURCE_LOCATOR_ANNOTATION = "io.micronaut.jaxrs.container.JaxRsSubResourceLocator"

    @Unroll
    void "test map parameter annotation #source"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/test")
class Test {

    @jakarta.ws.rs.GET
    void test(@$source.name("$value") String test) {}
}
""")

        def method = definition.getRequiredMethod("test", String)
        def metadata = method.arguments[0].getAnnotationMetadata()

        expect:
        metadata.hasAnnotation(target)
        metadata.stringValue(target)
                .get() == 'test'
        source != PathParam || metadata.hasAnnotation(PathVariable)
        source != PathParam || metadata.getAnnotationTypeByStereotype(Bindable).get() == PathParam
        source != HeaderParam || !metadata.hasAnnotation(Header)
        source != CookieParam || !metadata.hasAnnotation(CookieValue)
        source != FormParam || !metadata.hasAnnotation(QueryValue)
        source != FormParam || metadata.getAnnotationTypeByStereotype(Bindable).get() == FormParam

        where:
        source      | target       | value
        PathParam   | PathParam    | "test"
        HeaderParam | HeaderParam  | "test"
        CookieParam | CookieParam  | "test"
        QueryParam  | QueryParam   | "test"
        FormParam   | FormParam    | "test"
        MatrixParam | MatrixParam  | "test"
    }

    void "test HeaderParam maps to Header for clients"() {
        given:
        def definition = buildBeanDefinition('test.Test$Intercepted', """
package test;

@io.micronaut.http.client.annotation.Client("/test")
interface Test {

    @jakarta.ws.rs.GET
    void test(@jakarta.ws.rs.HeaderParam("X-Test") String test);
}
""")

        def method = definition.getRequiredMethod("test", String)
        def metadata = method.arguments[0].getAnnotationMetadata()

        expect:
        metadata.hasAnnotation(Header)
        metadata.stringValue(Header).get() == 'X-Test'
    }

    void "test CookieParam maps to CookieValue for clients"() {
        given:
        def definition = buildBeanDefinition('test.Test$Intercepted', """
package test;

@io.micronaut.http.client.annotation.Client("/test")
interface Test {

    @jakarta.ws.rs.GET
    void test(@jakarta.ws.rs.CookieParam("test") String test);
}
""")

        def method = definition.getRequiredMethod("test", String)
        def metadata = method.arguments[0].getAnnotationMetadata()

        expect:
        metadata.hasAnnotation(CookieValue)
        metadata.stringValue(CookieValue).get() == 'test'
    }

    void "test PathParam maps to PathVariable for clients"() {
        given:
        def definition = buildBeanDefinition('test.Test$Intercepted', """
package test;

@io.micronaut.http.client.annotation.Client("/test")
interface Test {

    @jakarta.ws.rs.GET
    void test(@jakarta.ws.rs.PathParam("id") String id);
}
""")

        def method = definition.getRequiredMethod("test", String)
        def metadata = method.arguments[0].getAnnotationMetadata()

        expect:
        metadata.hasAnnotation(PathVariable)
        metadata.stringValue(PathVariable).get() == 'id'
        !metadata.hasAnnotation(PathParam)
    }

    void "test inherited PathParam maps to PathVariable for clients"() {
        given:
        def definition = buildBeanDefinition('test.Test$Intercepted', """
package test;

interface Resource {

    @jakarta.ws.rs.GET
    @jakarta.ws.rs.Path("/ping/{v}")
    void test(@jakarta.ws.rs.PathParam("v") String value);
}

@io.micronaut.http.client.annotation.Client("/test")
interface Test extends Resource {
}
""")

        def method = definition.getRequiredMethod("test", String)
        def metadata = method.arguments[0].getAnnotationMetadata()

        expect:
        method.stringValue(HttpMethodMapping).get() == '/ping/{v}'
        metadata.hasAnnotation(PathVariable)
        metadata.stringValue(PathVariable).get() == 'v'
        !metadata.hasAnnotation(PathParam)
    }

    void "test MatrixParam route allows matrix path segments"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/test")
class Test {

    @jakarta.ws.rs.GET
    @jakarta.ws.rs.Path("/colors/ids")
    void test(@jakarta.ws.rs.MatrixParam("color") String color) {}
}
""")

        def method = definition.getRequiredMethod("test", String)

        expect:
        method.stringValue(HttpMethodMapping).get() == '/colors{color:;[^/]*|}/ids{color:;[^/]*|}'
    }

    void "test MatrixParam field route allows matrix method path segments"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/test")
class Test {

    @jakarta.ws.rs.MatrixParam("color")
    String color;

    @jakarta.ws.rs.GET
    @jakarta.ws.rs.Path("/colors")
    String test() {
        return color;
    }
}
""")

        def method = definition.getRequiredMethod("test")

        expect:
        definition.stringValue(Controller).get() == '/test{color:;[^/]*|}'
        method.stringValue(HttpMethodMapping).get() == '/colors{color:;[^/]*|}'
    }

    void "test same class subresource locator exposes inherited resource methods"() {
        given:
        def definition = buildBeanDefinition('test.SubResource', """
package test;

@jakarta.ws.rs.Path("/base-resource")
class BaseResource {

    @jakarta.ws.rs.GET
    String get(@jakarta.ws.rs.HeaderParam("X-Test") String test) {
        return test;
    }
}

@jakarta.ws.rs.Path("resource")
class SubResource extends BaseResource {

    @jakarta.ws.rs.Path("subresource")
    SubResource subresource() {
        return this;
    }
}
""")

        def method = definition.getRequiredMethod("get", String)

        expect:
        method.stringValue(HttpMethodMapping).get() == '/subresource'
    }

    void "test inherited PathParam prefers JAX-RS binder metadata"() {
        given:
        def definition = buildBeanDefinition('test.SubResource', """
package test;

@jakarta.ws.rs.Path("/base-resource")
class BaseResource {

    @jakarta.ws.rs.GET
    @jakarta.ws.rs.Path("/{id}/{id1}")
    String get(@jakarta.ws.rs.PathParam("id") String id,
               @jakarta.ws.rs.PathParam("id1") jakarta.ws.rs.core.PathSegment id1) {
        return id + id1.getPath();
    }
}

@jakarta.ws.rs.Path("resource")
class SubResource extends BaseResource {

    @jakarta.ws.rs.Path("subresource")
    SubResource subresource() {
        return this;
    }
}
""")

        def method = definition.getRequiredMethod("get", String, jakarta.ws.rs.core.PathSegment)
        def idMetadata = method.arguments[0].getAnnotationMetadata()
        def segmentMetadata = method.arguments[1].getAnnotationMetadata()

        expect:
        method.stringValue(HttpMethodMapping).get() == '/subresource{id1:;[^/]*|}/{id}{id1:;[^/]*|}/{id1}{id1:;[^/]*|}'
        method.hasAnnotation(PATH_PARAM_BINDING_ANNOTATION)
        idMetadata.hasAnnotation(PathVariable)
        idMetadata.getAnnotationTypeByStereotype(Bindable).get() == PathParam
        segmentMetadata.hasAnnotation(PathVariable)
        segmentMetadata.getAnnotationTypeByStereotype(Bindable).get() == PathParam
    }

    void "test subresource locator exposes returned resource method"() {
        given:
        def definition = buildBeanDefinition('test.LocatorResource', """
package test;

@jakarta.ws.rs.Path("resource")
class LocatorResource {

    @jakarta.ws.rs.Path("locator")
    MiddleResource locator(@jakarta.ws.rs.HeaderParam("X-Test") String test) {
        return new MiddleResource(test);
    }
}

class MiddleResource {
    private final String value;

    MiddleResource() {
        this.value = null;
    }

    MiddleResource(String value) {
        this.value = value;
    }

    @jakarta.ws.rs.POST
    String post() {
        return value;
    }
}
""")

        def method = definition.getRequiredMethod("locator", String)
        def metadata = method.arguments[0].getAnnotationMetadata()

        expect:
        method.hasAnnotation(Post)
        method.stringValue(HttpMethodMapping).get() == '/locator'
        method.stringValue(SUB_RESOURCE_LOCATOR_ANNOTATION).get() == 'post'
        method.classValue(SUB_RESOURCE_LOCATOR_ANNOTATION, 'type').get().name == 'test.MiddleResource'
        metadata.hasAnnotation(HeaderParam)
        metadata.stringValue(HeaderParam).get() == 'X-Test'
        !metadata.hasAnnotation(Header)
    }

    void "test subresource locator MatrixParam route allows matrix path segments"() {
        given:
        def definition = buildBeanDefinition('test.LocatorResource', """
package test;

@jakarta.ws.rs.Path("resource")
class LocatorResource {

    @jakarta.ws.rs.Path("locator")
    MiddleResource locator(@jakarta.ws.rs.MatrixParam("color") String color) {
        return new MiddleResource(color);
    }
}

class MiddleResource {
    private final String color;

    MiddleResource(String color) {
        this.color = color;
    }

    @jakarta.ws.rs.POST
    String post() {
        return color;
    }
}
""")

        def method = definition.getRequiredMethod("locator", String)

        expect:
        definition.stringValue(Controller).get() == 'resource{color:;[^/]*|}'
        method.hasAnnotation(Post)
        method.stringValue(HttpMethodMapping).get() == '/locator{color:;[^/]*|}'
        method.stringValue(SUB_RESOURCE_LOCATOR_ANNOTATION).get() == 'post'
    }

    void "test subresource locator MatrixParam field route allows matrix path segments"() {
        given:
        def definition = buildBeanDefinition('test.LocatorResource', """
package test;

@jakarta.ws.rs.Path("resource")
class LocatorResource {

    @jakarta.ws.rs.MatrixParam("color")
    String color;

    @jakarta.ws.rs.Path("locator")
    MiddleResource locator() {
        return new MiddleResource(color);
    }
}

class MiddleResource {
    private final String color;

    MiddleResource(String color) {
        this.color = color;
    }

    @jakarta.ws.rs.POST
    String post() {
        return color;
    }
}
""")

        def method = definition.getRequiredMethod("locator")

        expect:
        definition.stringValue(Controller).get() == 'resource{color:;[^/]*|}'
        method.hasAnnotation(Post)
        method.stringValue(HttpMethodMapping).get() == '/locator{color:;[^/]*|}'
        method.stringValue(SUB_RESOURCE_LOCATOR_ANNOTATION).get() == 'post'
    }

    void "test subresource locator prefers target methods declared on returned resource type"() {
        given:
        def definition = buildBeanDefinition('test.LocatorResource', """
package test;

@jakarta.ws.rs.Path("resource")
class LocatorResource {

    @jakarta.ws.rs.Path("locator/{id}")
    MiddleResource locator(@jakarta.ws.rs.PathParam("id") String id) {
        return new MiddleResource(id);
    }
}

class BaseResource {

    @jakarta.ws.rs.GET
    @jakarta.ws.rs.Path("{id}")
    String inherited(@jakarta.ws.rs.PathParam("id") String id) {
        return id;
    }
}

class MiddleResource extends BaseResource {
    private final String id;

    MiddleResource(String id) {
        this.id = id;
    }

    @jakarta.ws.rs.POST
    String returnValue() {
        return id;
    }
}
""")

        def method = definition.getRequiredMethod("locator", String)

        expect:
        method.hasAnnotation(Post)
        method.stringValue(HttpMethodMapping).get() == '/locator/{id}'
        method.stringValue(SUB_RESOURCE_LOCATOR_ANNOTATION).get() == 'returnValue'
        method.classValue(SUB_RESOURCE_LOCATOR_ANNOTATION, 'type').get().name == 'test.MiddleResource'
    }

    void "test subresource locator preserves custom http method mapping"() {
        given:
        def definition = buildBeanDefinition('test.LocatorResource', """
package test;

import java.lang.annotation.*;

@jakarta.ws.rs.Path("resource")
class LocatorResource {

    @jakarta.ws.rs.Path("locator")
    MiddleResource locator() {
        return new MiddleResource();
    }
}

class MiddleResource {

    @WATCH
    @jakarta.ws.rs.Path("child")
    String watch() {
        return "ok";
    }
}

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@jakarta.ws.rs.HttpMethod("WATCH")
@Documented
@interface WATCH {
}
""")

        def method = definition.getRequiredMethod("locator")

        expect:
        method.hasAnnotation(CustomHttpMethod)
        method.stringValue(CustomHttpMethod, 'method').get() == 'WATCH'
        method.stringValue(HttpMethodMapping).get() == '/locator/child'
        method.stringValue(SUB_RESOURCE_LOCATOR_ANNOTATION).get() == 'watch'
        method.classValue(SUB_RESOURCE_LOCATOR_ANNOTATION, 'type').get().name == 'test.MiddleResource'
    }

    void "test recursive subresource locator captures remaining path"() {
        given:
        def definition = buildBeanDefinition('test.LocatorResource', """
package test;

@jakarta.ws.rs.Path("resource")
class LocatorResource {

    @jakarta.ws.rs.Path("recursive")
    RecursiveResource recursive() {
        return new RecursiveResource();
    }
}

class RecursiveResource {

    @jakarta.ws.rs.Path("{id}")
    RecursiveResource recursive() {
        return this;
    }

    @jakarta.ws.rs.GET
    String get() {
        return "ok";
    }
}
""")

        def method = definition.getRequiredMethod("recursive")

        expect:
        method.hasAnnotation(Get)
        method.stringValue(HttpMethodMapping).get() == '/recursive{/jaxrsRecursiveRemaining:.*}'
        method.stringValue(SUB_RESOURCE_LOCATOR_ANNOTATION).get() == 'get'
        method.classValue(SUB_RESOURCE_LOCATOR_ANNOTATION, 'type').get().name == 'test.RecursiveResource'
        method.stringValue(SUB_RESOURCE_LOCATOR_ANNOTATION, 'recursive').get() == 'recursive'
        method.stringValue(SUB_RESOURCE_LOCATOR_ANNOTATION, 'remaining').get() == 'jaxrsRecursiveRemaining'
    }

    void "test Encoded MatrixParam is supported"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/test")
class Test {

    @jakarta.ws.rs.GET
    void test(@jakarta.ws.rs.Encoded @jakarta.ws.rs.MatrixParam("color") String color) {}
}
""")

        def method = definition.getRequiredMethod("test", String)
        def metadata = method.arguments[0].getAnnotationMetadata()

        expect:
        metadata.hasAnnotation(Encoded)
        metadata.hasAnnotation(MatrixParam)
    }

    void "test method Encoded applies to MatrixParam arguments"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/test")
class Test {

    @jakarta.ws.rs.GET
    @jakarta.ws.rs.Encoded
    void test(@jakarta.ws.rs.MatrixParam("color") String color) {}
}
""")

        def method = definition.getRequiredMethod("test", String)

        expect:
        method.arguments[0].getAnnotationMetadata().hasAnnotation(Encoded)
    }

    void "test method Encoded applies to PathParam arguments"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/test/{color}")
class Test {

    @jakarta.ws.rs.GET
    @jakarta.ws.rs.Encoded
    void test(@jakarta.ws.rs.PathParam("color") String color) {}
}
""")

        def method = definition.getRequiredMethod("test", String)

        expect:
        method.arguments[0].getAnnotationMetadata().hasAnnotation(Encoded)
    }

    void "test MatrixParam field marks resource for request field injection"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/test")
class Test {

    @jakarta.ws.rs.MatrixParam("color")
    String color;

    @jakarta.ws.rs.GET
    String test() {
        return color;
    }
}
""")
        def method = definition.getRequiredMethod("test")

        expect:
        method.annotationMetadata.hasAnnotation(REQUEST_FIELD_INJECTION_ANNOTATION)
        definition.annotationMetadata.hasAnnotation(Prototype)
    }

    void "test QueryParam field marks resource for request field injection"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/test")
class Test {

    @jakarta.ws.rs.QueryParam("color")
    String color;

    @jakarta.ws.rs.GET
    String test() {
        return color;
    }
}
""")
        def method = definition.getRequiredMethod("test")

        expect:
        method.annotationMetadata.hasAnnotation(REQUEST_FIELD_INJECTION_ANNOTATION)
        definition.annotationMetadata.hasAnnotation(Prototype)
        definition.stringValue(io.micronaut.http.annotation.Controller).get() == '/test'
    }

    void "test HeaderParam field marks resource for request field injection"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/test")
class Test {

    @jakarta.ws.rs.HeaderParam("X-Color")
    String color;

    @jakarta.ws.rs.GET
    String test() {
        return color;
    }
}
""")
        def method = definition.getRequiredMethod("test")

        expect:
        method.annotationMetadata.hasAnnotation(REQUEST_FIELD_INJECTION_ANNOTATION)
        definition.annotationMetadata.hasAnnotation(Prototype)
    }

    void "test CookieParam field marks resource for request field injection"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/test")
class Test {

    @jakarta.ws.rs.CookieParam("color")
    String color;

    @jakarta.ws.rs.GET
    String test() {
        return color;
    }
}
""")
        def method = definition.getRequiredMethod("test")

        expect:
        method.annotationMetadata.hasAnnotation(REQUEST_FIELD_INJECTION_ANNOTATION)
        definition.annotationMetadata.hasAnnotation(Prototype)
    }

    void "test PathParam field marks resource for request field injection"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/test/{color}")
class Test {

    @jakarta.ws.rs.PathParam("color")
    String color;

    @jakarta.ws.rs.GET
    String test() {
        return color;
    }
}
""")
        def method = definition.getRequiredMethod("test")

        expect:
        method.annotationMetadata.hasAnnotation(REQUEST_FIELD_INJECTION_ANNOTATION)
        definition.annotationMetadata.hasAnnotation(Prototype)
    }

    void "test inherited HeaderParam field marks resource for request field injection"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

class BaseResource {

    @jakarta.ws.rs.HeaderParam("X-Color")
    String color;
}

@jakarta.ws.rs.Path("/test")
class Test extends BaseResource {

    @jakarta.ws.rs.GET
    String test() {
        return color;
    }
}
""")
        def method = definition.getRequiredMethod("test")

        expect:
        method.annotationMetadata.hasAnnotation(REQUEST_FIELD_INJECTION_ANNOTATION)
        definition.annotationMetadata.hasAnnotation(Prototype)
    }

    void "test request field injection allows inherited protected helpers"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

class BaseResource {

    protected void helper(String name, Number value) {
    }
}

@jakarta.ws.rs.Path("/test")
class Test extends BaseResource {

    @jakarta.ws.rs.MatrixParam("color")
    String color;

    @jakarta.ws.rs.GET
    String test() {
        helper("color", 1);
        return color;
    }
}
""")
        def method = definition.getRequiredMethod("test")
        def helper = definition.getRequiredMethod("helper", String, Number)

        expect:
        method.annotationMetadata.hasAnnotation(REQUEST_FIELD_INJECTION_ANNOTATION)
        helper.annotationMetadata.hasAnnotation(ReflectiveAccess)
    }

    void "test default value"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/test")
class Test {

    @jakarta.ws.rs.GET
    void test(@jakarta.ws.rs.DefaultValue("foo") @jakarta.ws.rs.PathParam("test") String test) {}
}
""")

        def method = definition.getRequiredMethod("test", String)
        def metadata = method.arguments[0].getAnnotationMetadata()

        expect:
        metadata.stringValue(Bindable, "defaultValue").get() == 'foo'
    }

    void "test primitive parameter has java default value"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/test")
class Test {

    @jakarta.ws.rs.GET
    void test(@jakarta.ws.rs.MatrixParam("count") int count, @jakarta.ws.rs.MatrixParam("enabled") boolean enabled) {}
}
""")

        def method = definition.getRequiredMethod("test", int, boolean)

        expect:
        method.arguments[0].getAnnotationMetadata().stringValue(Bindable, "defaultValue").get() == '0'
        method.arguments[1].getAnnotationMetadata().stringValue(Bindable, "defaultValue").get() == 'false'
    }

    void "test public resource uses largest public context constructor"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/resource")
public class Test {

    public Test() {
    }

    public Test(@jakarta.ws.rs.core.Context jakarta.ws.rs.core.HttpHeaders headers) {
    }

    public Test(@jakarta.ws.rs.core.Context jakarta.ws.rs.core.HttpHeaders headers,
                @jakarta.ws.rs.core.Context jakarta.ws.rs.core.UriInfo info,
                @jakarta.ws.rs.core.Context jakarta.ws.rs.core.Application application,
                @jakarta.ws.rs.core.Context jakarta.ws.rs.core.Request request) {
    }

    protected Test(@jakarta.ws.rs.core.Context jakarta.ws.rs.core.HttpHeaders headers,
                   @jakarta.ws.rs.core.Context jakarta.ws.rs.core.UriInfo info,
                   @jakarta.ws.rs.core.Context jakarta.ws.rs.core.Application application,
                   @jakarta.ws.rs.core.Context jakarta.ws.rs.core.Request request,
                   @jakarta.ws.rs.core.Context jakarta.ws.rs.ext.Providers providers) {
    }

    @jakarta.ws.rs.GET
    public String test() {
        return "ok";
    }
}
""")

        expect:
        definition.constructor.arguments*.type*.name == [
                'jakarta.ws.rs.core.HttpHeaders',
                'jakarta.ws.rs.core.UriInfo',
                'jakarta.ws.rs.core.Application',
                'jakarta.ws.rs.core.Request'
        ]
    }

    void "test request constructor parameters mark resource for constructor injection"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/resource/matrix")
public class Test {
    private final String param;

    public Test(@jakarta.ws.rs.MatrixParam("param") String param) {
        this.param = param;
    }

    @jakarta.ws.rs.GET
    public String get() {
        return param;
    }
}
""")

        expect:
        definition.annotationMetadata.hasAnnotation(CONSTRUCTOR_INJECTION_ANNOTATION)
        definition.annotationMetadata.hasAnnotation(Prototype)
        definition.stringValue(Controller).get() == '/resource{param:;[^/]*|}/matrix{param:;[^/]*|}'
        definition.constructor.arguments[0].annotationMetadata.hasAnnotation(Parameter)
        definition.constructor.arguments[0].annotationMetadata.hasAnnotation(MatrixParam)
    }

    @Unroll
    void "test unsupported parameter annotation #source"() {
        when:
        buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/test")
class Test {

    @jakarta.ws.rs.GET
    void test(@$source.name(${value ? "\"$value\"" : ""}) String test) {}
}
""")


        then:
        def e = thrown(RuntimeException)
        e.message.contains("Unsupported JAX-RS annotation used on method: $source.name")

        where:
        source      | value
        BeanParam   | null
    }

    @Unroll
    void "test unsupported parameter annotation #source with @Controller"() {
        when:
        buildBeanDefinition('test.Test', """
package test;

@io.micronaut.http.annotation.Controller("/test")
class Test {

    @jakarta.ws.rs.GET
    void test(@$source.name(${value ? "\"$value\"" : ""}) String test) {}
}
""")

        then:
        def e = thrown(RuntimeException)
        e.message.contains("Unsupported JAX-RS annotation used on method: $source.name")

        where:
        source      | value
        BeanParam   | null
    }

    void "test jakarta.ws.rs.PathParam value"() {
        when:
        def definition = buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/test/{user_id}/v1")
class Test {

    @jakarta.ws.rs.GET
    void test(@jakarta.ws.rs.PathParam("user_id") String userId) {}
}
""")

        def method = definition.getRequiredMethod("test", String)
        def metadata = method.arguments[0].getAnnotationMetadata()


        then:
        metadata.stringValue(PathParam).get() == 'user_id'
        metadata.hasStereotype(Bindable)
    }

    void "test jakarta.ws.rs.PathParam value with jakarta.ws.rs.DefaultValue"() {
        when:
        def definition = buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/test/{user_id}")
class Test {

    @jakarta.ws.rs.GET
    void test(@jakarta.ws.rs.DefaultValue("foo") @jakarta.ws.rs.PathParam("user_id") String userId) {}
}
""")

        def method = definition.getRequiredMethod("test", String)
        def metadata = method.arguments[0].getAnnotationMetadata()


        then:
        metadata.stringValue(PathParam, "value").get() == 'user_id'
        metadata.stringValue(Bindable, "defaultValue").get() == 'foo'
    }

}
