package io.micronaut.jaxrs.processor

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec
import io.micronaut.http.annotation.Consumes
import io.micronaut.http.annotation.Produces
import io.micronaut.jaxrs.common.JaxRsMessageBodyProvider

class MediaTypeAnnotationSpec extends AbstractTypeElementSpec {

    void "test that values are set for produces and consumes #source"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

@jakarta.ws.rs.Path("/test")
class Test {

    @jakarta.ws.rs.GET
    @jakarta.ws.rs.Consumes($source)
    @jakarta.ws.rs.Produces($source)
    void test(@jakarta.ws.rs.PathParam("test") String test) {}
}
""")

        def method = definition.getRequiredMethod("test", String)
        def metadata = method.annotationMetadata

        expect:
        metadata.findAnnotation(Produces).get().values['value'] == value
        metadata.findAnnotation(Consumes).get().values['value'] == value

        where:
        source                                 | value
        '{ "application/json", "text/plain" }' | ["application/json", "text/plain"]
        '{ "application/json" }'               | ["application/json"]
        '"application/json"'                   | ["application/json"]
        ''                                     | ["*/*"]
    }

    void "test provider body metadata is precomputed"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

@jakarta.ws.rs.ext.Provider
@jakarta.ws.rs.Consumes({ "application/json", "text/plain" })
@jakarta.ws.rs.Produces("text/plain")
class Test implements jakarta.ws.rs.ext.MessageBodyReader<String>, jakarta.ws.rs.ext.MessageBodyWriter<String> {
    public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType) {
        return true;
    }

    public String readFrom(Class<String> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType, jakarta.ws.rs.core.MultivaluedMap<String, String> httpHeaders, InputStream entityStream) {
        return "";
    }

    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType) {
        return true;
    }

    public void writeTo(String string, Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType, jakarta.ws.rs.core.MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) {
    }
}
""")
        def metadata = definition.annotationMetadata

        expect:
        metadata.booleanValue(JaxRsMessageBodyProvider, JaxRsMessageBodyProvider.MEMBER_SERVER).get()
        metadata.classValue(JaxRsMessageBodyProvider, JaxRsMessageBodyProvider.MEMBER_READER_TYPE).get() == String
        !metadata.booleanValue(JaxRsMessageBodyProvider, JaxRsMessageBodyProvider.MEMBER_READER_TYPE_VARIABLE).get()
        metadata.classValue(JaxRsMessageBodyProvider, JaxRsMessageBodyProvider.MEMBER_WRITER_TYPE).get() == String
        !metadata.booleanValue(JaxRsMessageBodyProvider, JaxRsMessageBodyProvider.MEMBER_WRITER_TYPE_VARIABLE).get()
        metadata.stringValues(JaxRsMessageBodyProvider, JaxRsMessageBodyProvider.MEMBER_CONSUMES).toList() == ["application/json", "text/plain"]
        metadata.stringValues(JaxRsMessageBodyProvider, JaxRsMessageBodyProvider.MEMBER_PRODUCES).toList() == ["text/plain"]
    }

    void "test provider body metadata defaults and constraints are precomputed"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

@jakarta.ws.rs.ext.Provider
@jakarta.ws.rs.ConstrainedTo(jakarta.ws.rs.RuntimeType.CLIENT)
class Test implements jakarta.ws.rs.ext.MessageBodyReader<String> {
    public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType) {
        return true;
    }

    public String readFrom(Class<String> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType, jakarta.ws.rs.core.MultivaluedMap<String, String> httpHeaders, InputStream entityStream) {
        return "";
    }
}
""")
        def metadata = definition.annotationMetadata

        expect:
        !metadata.booleanValue(JaxRsMessageBodyProvider, JaxRsMessageBodyProvider.MEMBER_SERVER).get()
        metadata.classValue(JaxRsMessageBodyProvider, JaxRsMessageBodyProvider.MEMBER_READER_TYPE).get() == String
        !metadata.booleanValue(JaxRsMessageBodyProvider, JaxRsMessageBodyProvider.MEMBER_READER_TYPE_VARIABLE).get()
        metadata.stringValues(JaxRsMessageBodyProvider, JaxRsMessageBodyProvider.MEMBER_CONSUMES).toList() == ["*/*"]
    }

    void "test provider type variable body metadata is precomputed"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

@jakarta.ws.rs.ext.Provider
class Test<T extends Number> implements jakarta.ws.rs.ext.MessageBodyReader<T>, jakarta.ws.rs.ext.MessageBodyWriter<Number> {
    public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType) {
        return true;
    }

    public T readFrom(Class<T> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType, jakarta.ws.rs.core.MultivaluedMap<String, String> httpHeaders, InputStream entityStream) {
        return null;
    }

    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType) {
        return true;
    }

    public void writeTo(Number number, Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType, jakarta.ws.rs.core.MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) {
    }
}
""")
        def metadata = definition.annotationMetadata

        expect:
        metadata.classValue(JaxRsMessageBodyProvider, JaxRsMessageBodyProvider.MEMBER_READER_TYPE).get() == Number
        metadata.booleanValue(JaxRsMessageBodyProvider, JaxRsMessageBodyProvider.MEMBER_READER_TYPE_VARIABLE).get()
        metadata.classValue(JaxRsMessageBodyProvider, JaxRsMessageBodyProvider.MEMBER_WRITER_TYPE).get() == Number
        !metadata.booleanValue(JaxRsMessageBodyProvider, JaxRsMessageBodyProvider.MEMBER_WRITER_TYPE_VARIABLE).get()
    }

    void "test array provider type metadata is precomputed for #providerType"() {
        given:
        def definition = buildBeanDefinition('test.Test', """
package test;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

@jakarta.ws.rs.ext.Provider
class Test implements jakarta.ws.rs.ext.MessageBodyReader<$providerType>, jakarta.ws.rs.ext.MessageBodyWriter<$providerType> {
    public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType) {
        return true;
    }

    public $providerType readFrom(Class<$providerType> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType, jakarta.ws.rs.core.MultivaluedMap<String, String> httpHeaders, InputStream entityStream) {
        return null;
    }

    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType) {
        return true;
    }

    public void writeTo($providerType value, Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType, jakarta.ws.rs.core.MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) {
    }
}
""")
        def annotation = definition.annotationMetadata.getAnnotation(JaxRsMessageBodyProvider)

        expect:
        annotation.classValue(JaxRsMessageBodyProvider.MEMBER_READER_TYPE).get() == expectedType
        annotation.classValue(JaxRsMessageBodyProvider.MEMBER_WRITER_TYPE).get() == expectedType

        where:
        providerType | expectedType
        'byte[]'     | byte[].class
        'String[]'   | String[].class
    }

}
