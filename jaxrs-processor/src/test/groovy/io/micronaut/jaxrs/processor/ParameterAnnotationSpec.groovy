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
import io.micronaut.http.annotation.Put
import io.micronaut.http.annotation.QueryValue
import io.micronaut.http.annotation.RequestBean
import io.micronaut.jaxrs.common.JaxRsResourceTemplateMetadata
import io.micronaut.jaxrs.common.JaxRsSubResourceLocatorMetadata
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
    static final String RESOURCE_TEMPLATE_ANNOTATION = "io.micronaut.jaxrs.container.JaxRsResourceTemplate"
    static final String SUB_RESOURCE_LOCATOR_ANNOTATION = "io.micronaut.jaxrs.container.JaxRsSubResourceLocator"
    static final String DYNAMIC_SUB_RESOURCE_INDEX_ANNOTATION = "io.micronaut.jaxrs.container.JaxRsDynamicSubResourceIndex"
    static final String ENTITY_ANNOTATION = "io.micronaut.jaxrs.container.JaxRsEntity"

    private static <T> T withFailOnUnsupportedDisabled(Closure<T> closure) {
        return withSystemProperty(JaxRsTypeElementVisitor.OPTION_FAIL_ON_UNSUPPORTED, "false", closure)
    }

    private static <T> T withSystemProperty(String name, String value, Closure<T> closure) {
        def previous = System.getProperty(name)
        try {
            System.setProperty(name, value)
            return closure.call()
        } finally {
            if (previous == null) {
                System.clearProperty(name)
            } else {
                System.setProperty(name, previous)
            }
        }
    }

    private List<String> generatedBeanParamIntrospectionSources(String className, String source) {
        try (def parser = newJavaParser()) {
            return parser.generate(className, source)
                    .findAll { it.name.contains('$JaxRsBeanParamIntrospection') && it.name.endsWith('.java') }
                    .collect { it.getCharContent(true).toString() }
        }
    }

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
        method.intValue(RESOURCE_TEMPLATE_ANNOTATION, JaxRsResourceTemplateMetadata.MEMBER_PATH_SEGMENT_COUNT).getAsInt() == 3
        method.stringValues(RESOURCE_TEMPLATE_ANNOTATION, JaxRsResourceTemplateMetadata.MEMBER_PATH_PARAMETER_NAMES) == [] as String[]
        method.intValue(RESOURCE_TEMPLATE_ANNOTATION, JaxRsResourceTemplateMetadata.MEMBER_LITERAL_CHARACTERS).getAsInt() == 16
        method.intValue(RESOURCE_TEMPLATE_ANNOTATION, JaxRsResourceTemplateMetadata.MEMBER_CAPTURING_GROUPS).getAsInt() == 0
        method.intValue(RESOURCE_TEMPLATE_ANNOTATION, JaxRsResourceTemplateMetadata.MEMBER_NON_DEFAULT_CAPTURING_GROUPS).getAsInt() == 0
    }

    void "test resource template records path parameter layout"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/test/{id}")
class Test {

    @jakarta.ws.rs.GET
    @jakarta.ws.rs.Path("/child/{id:[0-9]+}/{slug}")
    void test(@jakarta.ws.rs.PathParam("id") String id,
              @jakarta.ws.rs.PathParam("slug") String slug) {}
}
""")

        def method = definition.getRequiredMethod("test", String, String)

        expect:
        method.stringValue(RESOURCE_TEMPLATE_ANNOTATION).get() == '/test/{id}/child/{id:[0-9]+}/{slug}'
        method.intValue(RESOURCE_TEMPLATE_ANNOTATION, JaxRsResourceTemplateMetadata.MEMBER_PATH_SEGMENT_COUNT).getAsInt() == 5
        method.stringValues(RESOURCE_TEMPLATE_ANNOTATION, JaxRsResourceTemplateMetadata.MEMBER_PATH_PARAMETER_NAMES) == ['id', 'id', 'slug'] as String[]
        method.getAnnotationMetadata().getValue(RESOURCE_TEMPLATE_ANNOTATION, JaxRsResourceTemplateMetadata.MEMBER_PATH_PARAMETER_SEGMENT_INDEXES, int[].class).get().toList() == [1, 3, 4]
    }

    void "test root resource method without method Path records root template once"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/resource/subresource/sub")
class Test {

    @jakarta.ws.rs.POST
    @jakarta.ws.rs.Consumes("text/plain")
    String post() {
        return "ok";
    }
}
""")

        def method = definition.getRequiredMethod("post")

        expect:
        method.stringValue(HttpMethodMapping).get() == '/'
        method.stringValue(RESOURCE_TEMPLATE_ANNOTATION).get() == '/resource/subresource/sub'
        method.intValue(RESOURCE_TEMPLATE_ANNOTATION, JaxRsResourceTemplateMetadata.MEMBER_ROOT_LITERAL_CHARACTERS).getAsInt() == 25
        method.intValue(RESOURCE_TEMPLATE_ANNOTATION, JaxRsResourceTemplateMetadata.MEMBER_LITERAL_CHARACTERS).getAsInt() == 25
    }

    void "test MatrixParam default method route allows class path matrix segment"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/test")
class Test {

    @jakarta.ws.rs.GET
    String test(@jakarta.ws.rs.MatrixParam("color") String color) {
        return color;
    }
}
""")

        def method = definition.getRequiredMethod("test", String)

        expect:
        definition.stringValue(Controller).get() == '/test{color:;[^/]*|}'
        method.stringValue(HttpMethodMapping).get() == '/'
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

    void "test BeanParam parameter maps to RequestBean and introspection when unsupported failures are disabled"() {
        given:
        def source = """
package test;

@jakarta.ws.rs.Path("/test")
class Test {

    @jakarta.ws.rs.GET
    @jakarta.ws.rs.Path("/beans")
    void test(@jakarta.ws.rs.BeanParam Bean bean) {}
}

class Bean {
    @jakarta.ws.rs.QueryParam("q")
    public String query;

    @jakarta.ws.rs.BeanParam
    public Nested nested;
}

class Nested {
    @jakarta.ws.rs.HeaderParam("X-Nested")
    public String nestedHeader;
}
"""
        def definition = withFailOnUnsupportedDisabled {
            buildBeanDefinition('test.Test', source)
        }
        def generatedSources = withFailOnUnsupportedDisabled {
            generatedBeanParamIntrospectionSources('test.Test', source)
        }
        def beanType = definition.beanType.classLoader.loadClass('test.Bean')
        def method = definition.getRequiredMethod("test", beanType)
        def argumentMetadata = method.arguments[0].annotationMetadata

        expect:
        argumentMetadata.hasAnnotation(BeanParam)
        argumentMetadata.hasAnnotation(RequestBean)
        generatedSources.any { it.contains('classNames = "test.Bean"') }
        generatedSources.any { it.contains('classNames = "test.Nested"') }
        generatedSources.every { it.contains('Introspected.AccessKind.FIELD') }
        generatedSources.every { it.contains('Introspected.AccessKind.METHOD') }
        generatedSources.every { it.contains('Introspected.Visibility.ANY') }
    }

    void "test BeanParam field route allows nested matrix method path segments when unsupported failures are disabled"() {
        given:
        def definition = withFailOnUnsupportedDisabled {
            buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/test")
class Test {

    @jakarta.ws.rs.BeanParam
    Bean bean;

    @jakarta.ws.rs.GET
    @jakarta.ws.rs.Path("/items/details")
    String test() {
        return bean.color + bean.nested.shade;
    }
}

class Bean {
    @jakarta.ws.rs.MatrixParam("color")
    public String color;

    @jakarta.ws.rs.BeanParam
    public Nested nested;
}

class Nested {
    @jakarta.ws.rs.MatrixParam("shade")
    public String shade;
}
""")
        }
        def method = definition.getRequiredMethod("test")

        expect:
        definition.stringValue(Controller).get() == '/test{color:;[^/]*|}'
        method.stringValue(HttpMethodMapping).get() == '/items{color:;[^/]*|}/details{shade:;[^/]*|}'
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
        method.classValue(SUB_RESOURCE_LOCATOR_ANNOTATION, JaxRsSubResourceLocatorMetadata.MEMBER_TYPE).get().name == 'test.MiddleResource'
        metadata.hasAnnotation(HeaderParam)
        metadata.stringValue(HeaderParam).get() == 'X-Test'
        !metadata.hasAnnotation(Header)
    }

    void "test subresource locator records all target http methods"() {
        given:
        def definition = buildBeanDefinition('test.LocatorResource', """
package test;

@jakarta.ws.rs.Path("resource")
class LocatorResource {

    @jakarta.ws.rs.Path("locator")
    MiddleResource locator() {
        return new MiddleResource();
    }
}

class MiddleResource {

    @jakarta.ws.rs.GET
    String get() {
        return "get";
    }

    @jakarta.ws.rs.POST
    @jakarta.ws.rs.Consumes("text/plain")
    @jakarta.ws.rs.Produces("text/plain")
    String post() {
        return "post";
    }
}
""")

        def method = definition.getRequiredMethod("locator")

        expect:
        method.stringValue(HttpMethodMapping).get() == '/locator'
        method.stringValues(SUB_RESOURCE_LOCATOR_ANNOTATION, JaxRsSubResourceLocatorMetadata.MEMBER_TARGET_METHODS) == ['get', 'post'] as String[]
        method.stringValues(SUB_RESOURCE_LOCATOR_ANNOTATION, JaxRsSubResourceLocatorMetadata.MEMBER_TARGET_HTTP_METHODS) == ['GET', 'POST'] as String[]
        method.stringValues(SUB_RESOURCE_LOCATOR_ANNOTATION, JaxRsSubResourceLocatorMetadata.MEMBER_TARGET_RESOURCE_TEMPLATES) == ['/resource/locator', '/resource/locator'] as String[]
        method.stringValues(SUB_RESOURCE_LOCATOR_ANNOTATION, JaxRsSubResourceLocatorMetadata.MEMBER_TARGET_PATH_SEGMENTS) == ['resource', 'locator', 'resource', 'locator'] as String[]
        method.getAnnotationMetadata().getValue(SUB_RESOURCE_LOCATOR_ANNOTATION, JaxRsSubResourceLocatorMetadata.MEMBER_TARGET_PATH_SEGMENT_COUNTS, int[].class).get().toList() == [2, 2]
        method.getAnnotationMetadata().getValue(SUB_RESOURCE_LOCATOR_ANNOTATION, JaxRsSubResourceLocatorMetadata.MEMBER_TARGET_ROUTE_SCORES, int[].class).get().toList() == [17, 0, 0, 17, 0, 0]
    }

    void "test Object subresource locator emits dynamic fallback route when unsupported failures are disabled"() {
        given:
        def definition = withFailOnUnsupportedDisabled {
            withSystemProperty("micronaut.route.validation", "false") {
                buildBeanDefinition('test.LocatorResource', """
package test;

@jakarta.ws.rs.Path("resource")
class LocatorResource {

    @jakarta.ws.rs.Path("l2locator")
    Object locator() {
        return new MiddleResource();
    }
}

class MiddleResource {

    @jakarta.ws.rs.Path("l2locator")
    Object locator() {
        return new LeafResource();
    }
}

class LeafResource {

    @jakarta.ws.rs.DELETE
    String delete() {
        return "ok";
    }
}
""")
            }
        }

        def method = definition.getRequiredMethod("locator")

        expect:
        method.hasAnnotation(CustomHttpMethod)
        method.stringValue(CustomHttpMethod, 'method').get() == JaxRsTypeElementVisitor.DYNAMIC_SUB_RESOURCE_LOCATOR_HTTP_METHOD
        method.stringValue(HttpMethodMapping).get() == 'l2locator{/jaxrsDynamicRemaining:.*}'
        method.stringValue(SUB_RESOURCE_LOCATOR_ANNOTATION, JaxRsSubResourceLocatorMetadata.MEMBER_ROUTE_PATH).get() == 'l2locator'
        method.booleanValue(SUB_RESOURCE_LOCATOR_ANNOTATION, JaxRsSubResourceLocatorMetadata.MEMBER_DYNAMIC).get()
        method.stringValue(SUB_RESOURCE_LOCATOR_ANNOTATION, JaxRsSubResourceLocatorMetadata.MEMBER_REMAINING).get() == JaxRsSubResourceLocatorMetadata.DYNAMIC_REMAINING_ROUTE_VARIABLE
    }

    void "test dynamic subresource runtime target class records method index"() {
        given:
        def context = withFailOnUnsupportedDisabled {
            withSystemProperty("micronaut.route.validation", "false") {
                buildContext('test.MiddleResource', """
package test;

class MiddleResource {

    @jakarta.ws.rs.Path("leaf")
    Object locator() {
        return new LeafResource();
    }

    @jakarta.ws.rs.GET
    @jakarta.ws.rs.Path("item/{id}")
    @jakarta.ws.rs.Produces("text/plain")
    String get(@jakarta.ws.rs.PathParam("id") String id) {
        return id;
    }
}

class LeafResource {

    @jakarta.ws.rs.DELETE
    String delete() {
        return "ok";
    }
}
""")
            }
        }
        def definition = context.getBeanDefinition(context.getClassLoader().loadClass('test.MiddleResource'))

        expect:
        definition.stringValues(DYNAMIC_SUB_RESOURCE_INDEX_ANNOTATION, JaxRsSubResourceLocatorMetadata.DYNAMIC_INDEX_MEMBER_METHOD_NAMES) == ['locator', 'get'] as String[]
        definition.stringValues(DYNAMIC_SUB_RESOURCE_INDEX_ANNOTATION, JaxRsSubResourceLocatorMetadata.DYNAMIC_INDEX_MEMBER_HTTP_METHODS) == ['', 'GET'] as String[]
        definition.stringValues(DYNAMIC_SUB_RESOURCE_INDEX_ANNOTATION, JaxRsSubResourceLocatorMetadata.DYNAMIC_INDEX_MEMBER_ROUTE_PATH_SEGMENTS) == ['leaf', 'item', '{id}'] as String[]
        definition.getAnnotationMetadata().getValue(DYNAMIC_SUB_RESOURCE_INDEX_ANNOTATION, JaxRsSubResourceLocatorMetadata.DYNAMIC_INDEX_MEMBER_ROUTE_PATH_SEGMENT_COUNTS, int[].class).get().toList() == [1, 2]
        definition.stringValues(DYNAMIC_SUB_RESOURCE_INDEX_ANNOTATION, JaxRsSubResourceLocatorMetadata.DYNAMIC_INDEX_MEMBER_RESOURCE_TEMPLATES) == ['', '/item/{id}'] as String[]
        definition.stringValues(DYNAMIC_SUB_RESOURCE_INDEX_ANNOTATION, JaxRsSubResourceLocatorMetadata.DYNAMIC_INDEX_MEMBER_PRODUCES) == ['text/plain'] as String[]
        definition.getAnnotationMetadata().getValue(DYNAMIC_SUB_RESOURCE_INDEX_ANNOTATION, JaxRsSubResourceLocatorMetadata.DYNAMIC_INDEX_MEMBER_PRODUCES_COUNTS, int[].class).get().toList() == [0, 1]
    }

    void "test response returning terminal subresource locator is exposed as GET route"() {
        given:
        def definition = buildBeanDefinition('test.LocatorResource', """
package test;

@jakarta.ws.rs.Path("resource")
class LocatorResource {

    @jakarta.ws.rs.Path("terminal/{id}")
    jakarta.ws.rs.core.Response terminal(@jakarta.ws.rs.PathParam("id") String id) {
        return jakarta.ws.rs.core.Response.ok(id).build();
    }
}
""")

        def method = definition.getRequiredMethod("terminal", String)
        def metadata = method.arguments[0].getAnnotationMetadata()

        expect:
        method.hasAnnotation(Get)
        method.stringValue(HttpMethodMapping).get() == '/terminal/{id}'
        !method.hasAnnotation(SUB_RESOURCE_LOCATOR_ANNOTATION)
        method.stringValue(RESOURCE_TEMPLATE_ANNOTATION).get() == '/resource/terminal/{id}'
        method.stringValue(RESOURCE_TEMPLATE_ANNOTATION, JaxRsResourceTemplateMetadata.MEMBER_HTTP_METHOD).get() == 'GET'
        metadata.hasAnnotation(PathVariable)
        metadata.getAnnotationTypeByStereotype(Bindable).get() == PathParam
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
        method.classValue(SUB_RESOURCE_LOCATOR_ANNOTATION, JaxRsSubResourceLocatorMetadata.MEMBER_TYPE).get().name == 'test.MiddleResource'
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
        method.classValue(SUB_RESOURCE_LOCATOR_ANNOTATION, JaxRsSubResourceLocatorMetadata.MEMBER_TYPE).get().name == 'test.MiddleResource'
    }

    void "test subresource locator exposes returned resource method with context parameter"() {
        given:
        def definition = buildBeanDefinition('test.LocatorResource', """
package test;

@jakarta.ws.rs.Path("resource")
class LocatorResource {

    @jakarta.ws.rs.Path("locator")
    MiddleResource locator() {
        return new MiddleResource();
    }
}

class MiddleResource {

    @jakarta.ws.rs.GET
    @jakarta.ws.rs.Path("child")
    String get(@jakarta.ws.rs.core.Context jakarta.ws.rs.core.UriInfo uriInfo) {
        return uriInfo.getMatchedResourceTemplate();
    }
}
""")

        def method = definition.getRequiredMethod("locator")

        expect:
        method.hasAnnotation(Get)
        method.stringValue(HttpMethodMapping).get() == '/locator/child'
        method.stringValue(RESOURCE_TEMPLATE_ANNOTATION).get() == '/resource/locator/child'
        method.stringValue(RESOURCE_TEMPLATE_ANNOTATION, JaxRsResourceTemplateMetadata.MEMBER_HTTP_METHOD).get() == 'GET'
        method.intValue(RESOURCE_TEMPLATE_ANNOTATION, JaxRsResourceTemplateMetadata.MEMBER_ROOT_PATH_SEGMENT_COUNT).getAsInt() == 1
        method.stringValue(RESOURCE_TEMPLATE_ANNOTATION, JaxRsResourceTemplateMetadata.MEMBER_ROOT_CLASS_NAME).get() == 'test.LocatorResource'
        method.intValue(RESOURCE_TEMPLATE_ANNOTATION, JaxRsResourceTemplateMetadata.MEMBER_ROOT_LITERAL_CHARACTERS).getAsInt() == 9
        method.intValue(RESOURCE_TEMPLATE_ANNOTATION, JaxRsResourceTemplateMetadata.MEMBER_ROOT_CAPTURING_GROUPS).getAsInt() == 0
        method.intValue(RESOURCE_TEMPLATE_ANNOTATION, JaxRsResourceTemplateMetadata.MEMBER_ROOT_NON_DEFAULT_CAPTURING_GROUPS).getAsInt() == 0
        method.stringValue(SUB_RESOURCE_LOCATOR_ANNOTATION).get() == 'get'
        method.stringValues(SUB_RESOURCE_LOCATOR_ANNOTATION, JaxRsSubResourceLocatorMetadata.MEMBER_ARGUMENT_TYPES) == [jakarta.ws.rs.core.UriInfo.name] as String[]
    }

    void "test subresource locator exposes target template variables not bound by target parameters"() {
        given:
        def definition = buildBeanDefinition('test.LocatorResource', """
package test;

@jakarta.ws.rs.Path("resource")
class LocatorResource {

    @jakarta.ws.rs.Path("locator/{id:[a-z]+}")
    SubResource locator() {
        return new SubResource();
    }
}

class SubResource {

    @jakarta.ws.rs.GET
    @jakarta.ws.rs.Path("{child:[a-z]+}")
    String get(@jakarta.ws.rs.core.Context jakarta.ws.rs.core.UriInfo uriInfo) {
        return uriInfo.getMatchedResourceTemplate();
    }
}
""")

        def method = definition.getRequiredMethod("locator")

        expect:
        method.hasAnnotation(Get)
        method.stringValue(HttpMethodMapping).get() == '/locator/{id:[a-z]+}/{child:[a-z]+}'
        method.stringValue(RESOURCE_TEMPLATE_ANNOTATION).get() == '/resource/locator/{id:[a-z]+}/{child:[a-z]+}'
        method.stringValue(SUB_RESOURCE_LOCATOR_ANNOTATION).get() == 'get'
        method.stringValues(SUB_RESOURCE_LOCATOR_ANNOTATION, JaxRsSubResourceLocatorMetadata.MEMBER_ARGUMENT_TYPES) == [jakarta.ws.rs.core.UriInfo.name] as String[]
    }

    void "test subresource locator exposes target method from returned resource class"() {
        given:
        def definition = buildBeanDefinition('test.LocatorResource', """
package test;

@jakarta.ws.rs.Path("resource")
class LocatorResource {

    @jakarta.ws.rs.Path("sub")
    Class<SubResource> locator() {
        return SubResource.class;
    }
}

class SubResource {

    @jakarta.ws.rs.GET
    String ok() {
        return "OK";
    }
}
""")

        def method = definition.getRequiredMethod("locator")

        expect:
        method.hasAnnotation(Get)
        method.stringValue(HttpMethodMapping).get() == '/sub'
        method.stringValue(RESOURCE_TEMPLATE_ANNOTATION).get() == '/resource/sub'
        method.stringValue(SUB_RESOURCE_LOCATOR_ANNOTATION).get() == 'ok'
        method.classValue(SUB_RESOURCE_LOCATOR_ANNOTATION, JaxRsSubResourceLocatorMetadata.MEMBER_TYPE).get().name == 'test.SubResource'
        method.stringValues(SUB_RESOURCE_LOCATOR_ANNOTATION, JaxRsSubResourceLocatorMetadata.MEMBER_ARGUMENT_TYPES) == [] as String[]
    }

    void "test subresource locator exposes matching returned resource methods with request parameters"() {
        given:
        def definition = buildBeanDefinition('test.LocatorResource', """
package test;

@jakarta.ws.rs.Path("locator")
class LocatorResource {

    @jakarta.ws.rs.Path("sbquery")
    Resource query() {
        return new Resource();
    }

    @jakarta.ws.rs.Path("sbpath")
    Resource path() {
        return new Resource();
    }

    @jakarta.ws.rs.Path("sbmatrix")
    Resource matrix() {
        return new Resource();
    }
}

@jakarta.ws.rs.Path("ignored")
class Resource {

    @jakarta.ws.rs.GET
    @jakarta.ws.rs.Path("sbquery")
    String query(@jakarta.ws.rs.QueryParam("param") String param) {
        return param;
    }

    @jakarta.ws.rs.GET
    @jakarta.ws.rs.Path("sbpath/default")
    String path(@jakarta.ws.rs.DefaultValue("default") @jakarta.ws.rs.PathParam("param") String param) {
        return param;
    }

    @jakarta.ws.rs.GET
    @jakarta.ws.rs.Path("sbmatrix")
    String matrix(@jakarta.ws.rs.DefaultValue("default") @jakarta.ws.rs.MatrixParam("param") String param) {
        return param;
    }
}
""")

        def query = definition.getRequiredMethod("query")
        def path = definition.getRequiredMethod("path")
        def matrix = definition.getRequiredMethod("matrix")

        expect:
        query.hasAnnotation(Get)
        query.stringValue(HttpMethodMapping).get() == '/sbquery/sbquery'
        query.stringValue(SUB_RESOURCE_LOCATOR_ANNOTATION).get() == 'query'
        query.stringValues(SUB_RESOURCE_LOCATOR_ANNOTATION, JaxRsSubResourceLocatorMetadata.MEMBER_ARGUMENT_TYPES) == [String.name] as String[]
        path.hasAnnotation(Get)
        path.stringValue(HttpMethodMapping).get() == '/sbpath/sbpath/default'
        path.stringValue(SUB_RESOURCE_LOCATOR_ANNOTATION).get() == 'path'
        path.stringValues(SUB_RESOURCE_LOCATOR_ANNOTATION, JaxRsSubResourceLocatorMetadata.MEMBER_ARGUMENT_TYPES) == [String.name] as String[]
        matrix.hasAnnotation(Get)
        matrix.stringValue(HttpMethodMapping).get() == '/sbmatrix/sbmatrix'
        matrix.stringValue(SUB_RESOURCE_LOCATOR_ANNOTATION).get() == 'matrix'
        matrix.stringValues(SUB_RESOURCE_LOCATOR_ANNOTATION, JaxRsSubResourceLocatorMetadata.MEMBER_ARGUMENT_TYPES) == [String.name] as String[]
    }

    void "test subresource locator exposes multiple matching target paths when unsupported failures are disabled"() {
        given:
        def definition = withFailOnUnsupportedDisabled {
            withSystemProperty("micronaut.route.validation", "false") {
                buildBeanDefinition('test.LocatorResource', """
package test;

@jakarta.ws.rs.Path("locator")
class LocatorResource {

    @jakarta.ws.rs.Path("sbpath")
    Resource path() {
        return new Resource();
    }
}

class Resource {

    @jakarta.ws.rs.GET
    @jakarta.ws.rs.Path("sbpath/{param}")
    String path(@jakarta.ws.rs.PathParam("param") String param) {
        return param;
    }

    @jakarta.ws.rs.GET
    @jakarta.ws.rs.Path("sbpath/default")
    String pathDefault(@jakarta.ws.rs.DefaultValue("default") @jakarta.ws.rs.PathParam("param") String param) {
        return param;
    }
}
""")
            }
        }

        def method = definition.getRequiredMethod("path")

        expect:
        method.hasAnnotation(Get)
        method.stringValue(HttpMethodMapping).get() == '/sbpath/sbpath/{jaxrsSubResourcePath1}'
        method.stringValue(RESOURCE_TEMPLATE_ANNOTATION).get() == '/locator/sbpath/sbpath/{jaxrsSubResourcePath1}'
        method.stringValue(SUB_RESOURCE_LOCATOR_ANNOTATION).get() == 'path'
        method.stringValues(SUB_RESOURCE_LOCATOR_ANNOTATION, JaxRsSubResourceLocatorMetadata.MEMBER_TARGET_METHODS) == ['path', 'pathDefault'] as String[]
        method.stringValues(SUB_RESOURCE_LOCATOR_ANNOTATION, JaxRsSubResourceLocatorMetadata.MEMBER_TARGET_RESOURCE_TEMPLATES) == ['/locator/sbpath/sbpath/{param}', '/locator/sbpath/sbpath/default'] as String[]
        method.stringValues(SUB_RESOURCE_LOCATOR_ANNOTATION, JaxRsSubResourceLocatorMetadata.MEMBER_TARGET_ARGUMENT_TYPES) == [String.name, String.name] as String[]
        method.getAnnotationMetadata().getValue(SUB_RESOURCE_LOCATOR_ANNOTATION, JaxRsSubResourceLocatorMetadata.MEMBER_TARGET_ARGUMENT_TYPE_COUNTS, int[].class).get().toList() == [1, 1]
    }

    void "test subresource locator exposes target path variables already present on locator when unsupported failures are disabled"() {
        given:
        def definition = withFailOnUnsupportedDisabled {
            withSystemProperty("micronaut.route.validation", "false") {
                buildBeanDefinition('test.LocatorResource', """
package test;

@jakarta.ws.rs.Path("resource")
class LocatorResource {

    @jakarta.ws.rs.Path("three/{x:[a-z]}")
    SubResource locator() {
        return new SubResource();
    }
}

class SubResource {

    @jakarta.ws.rs.PUT
    @jakarta.ws.rs.Path("{x:[a-z]}")
    String put(@jakarta.ws.rs.core.Context jakarta.ws.rs.core.UriInfo uriInfo) {
        return uriInfo.getMatchedResourceTemplate();
    }
}
""")
            }
        }

        def method = definition.getRequiredMethod("locator")

        expect:
        method.hasAnnotation(Put)
        method.stringValue(HttpMethodMapping).get() == '/three/{x:[a-z]}/{x:[a-z]}'
        method.stringValue(RESOURCE_TEMPLATE_ANNOTATION).get() == '/resource/three/{x:[a-z]}/{x:[a-z]}'
        method.stringValue(SUB_RESOURCE_LOCATOR_ANNOTATION).get() == 'put'
        method.stringValues(SUB_RESOURCE_LOCATOR_ANNOTATION, JaxRsSubResourceLocatorMetadata.MEMBER_ARGUMENT_TYPES) == [jakarta.ws.rs.core.UriInfo.name] as String[]
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
        method.classValue(SUB_RESOURCE_LOCATOR_ANNOTATION, JaxRsSubResourceLocatorMetadata.MEMBER_TYPE).get().name == 'test.RecursiveResource'
        method.stringValue(SUB_RESOURCE_LOCATOR_ANNOTATION, JaxRsSubResourceLocatorMetadata.MEMBER_RECURSIVE).get() == 'recursive'
        method.stringValue(SUB_RESOURCE_LOCATOR_ANNOTATION, JaxRsSubResourceLocatorMetadata.MEMBER_REMAINING).get() == 'jaxrsRecursiveRemaining'
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

    void "test subclass parameter annotation overrides inherited resource annotations"() {
        given:
        def definition = buildBeanDefinition('test.Resource', """
package test;

@jakarta.ws.rs.Path("super")
class SuperClass {

    @jakarta.ws.rs.POST
    @jakarta.ws.rs.Path("post")
    String get(@jakarta.ws.rs.QueryParam("pqr") String param) {
        return param;
    }
}

@jakarta.ws.rs.Path("interfaceresource")
interface ResourceInterface {

    @jakarta.ws.rs.GET
    @jakarta.ws.rs.Path("get")
    String get(@jakarta.ws.rs.FormParam("xyz") String param);
}

@jakarta.ws.rs.Path("resource")
class Resource extends SuperClass implements ResourceInterface {

    @jakarta.ws.rs.PUT
    @jakarta.ws.rs.Path("put")
    public String get(@jakarta.ws.rs.MatrixParam("ijk") String param) {
        return param;
    }
}
""")
        def method = definition.getRequiredMethod("get", String)
        def metadata = method.arguments[0].getAnnotationMetadata()

        expect:
        definition.stringValue(Controller).get() == 'resource'
        method.stringValue(HttpMethodMapping).get() == 'put{param:;[^/]*|}'
        method.stringValue(RESOURCE_TEMPLATE_ANNOTATION, JaxRsResourceTemplateMetadata.MEMBER_HTTP_METHOD).get() == 'PUT'
        method.stringValues(RESOURCE_TEMPLATE_ANNOTATION, JaxRsResourceTemplateMetadata.MEMBER_MATRIX_ROUTE_VARIABLE_NAMES) == ['param'] as String[]
        metadata.hasAnnotation(MatrixParam)
        !metadata.hasAnnotation(QueryParam)
        !metadata.hasAnnotation(FormParam)
        metadata.getAnnotationTypeByStereotype(Bindable).get() == MatrixParam
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

    void "test primitive parameter has default value"() {
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

    void "test server implicit entity parameter keeps custom runtime annotations"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/test")
class Test {

    @jakarta.ws.rs.POST
    void test(@EntityAnnotation("Body") ReadableWritableEntity entity) {}
}

@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
@interface EntityAnnotation {
    String value();
}

class ReadableWritableEntity {
}
""")

        def entityType = definition.beanType.classLoader.loadClass("test.ReadableWritableEntity")
        def method = definition.getRequiredMethod("test", entityType)
        def metadata = method.arguments[0].getAnnotationMetadata()

        expect:
        metadata.hasAnnotation("test.EntityAnnotation")
        metadata.stringValue("test.EntityAnnotation").get() == "Body"
        metadata.hasAnnotation(ENTITY_ANNOTATION)
        !metadata.hasAnnotation(io.micronaut.http.annotation.Body)
    }

    void "test client implicit body parameter maps to Body"() {
        given:
        def definition = buildBeanDefinition('test.Test$Intercepted', """
package test;

@io.micronaut.http.client.annotation.Client("/test")
interface Test {

    @jakarta.ws.rs.POST
    void test(ReadableWritableEntity entity);
}

class ReadableWritableEntity {
}
""")

        def entityType = definition.beanType.classLoader.loadClass("test.ReadableWritableEntity")
        def method = definition.getRequiredMethod("test", entityType)
        def metadata = method.arguments[0].getAnnotationMetadata()

        expect:
        metadata.hasAnnotation(io.micronaut.http.annotation.Body)
        !metadata.hasAnnotation(ENTITY_ANNOTATION)
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

    @Unroll
    void "test unsupported request field annotation #source by default"() {
        when:
        buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/test")
class Test {

    @$source.name(${value ? "\"$value\"" : ""})
    String test;

    @jakarta.ws.rs.GET
    void test() {}
}
""")

        then:
        def e = thrown(RuntimeException)
        e.message.contains("Request scoped bean parameters are currently not supported")

        where:
        source    | value
        BeanParam | null
        FormParam | "test"
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
