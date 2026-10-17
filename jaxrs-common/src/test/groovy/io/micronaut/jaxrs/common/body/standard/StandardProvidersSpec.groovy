package io.micronaut.jaxrs.common.body.standard

import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.MultivaluedHashMap
import jakarta.ws.rs.core.NoContentException
import spock.lang.Specification

import java.lang.annotation.Annotation

class StandardProvidersSpec extends Specification {

    static final Annotation[] NONE = new Annotation[0]

    void "a short is read as a short"() {
        expect:
        new JaxRsNumberMessageBodyReaderWriter<Short>().readFrom(Short, Short, NONE, MediaType.TEXT_PLAIN_TYPE, null,
                new ByteArrayInputStream("12".bytes)) == (short) 12
    }

    void "a character is written in the charset"() {
        given:
        def out = new ByteArrayOutputStream()

        when:
        new JaxRsCharacterMessageBodyReaderWriter().writeTo('é' as Character, Character, Character, NONE,
                MediaType.TEXT_PLAIN_TYPE.withCharset("UTF-8"), null, out)

        then:
        new String(out.toByteArray(), "UTF-8") == "é"
    }

    void "an empty boolean is no content"() {
        when:
        new JaxRsBooleanMessageBodyReaderWriter().readFrom(Boolean, Boolean, NONE, MediaType.TEXT_PLAIN_TYPE, null,
                new ByteArrayInputStream(new byte[0]))

        then:
        def e = thrown(Exception)
        e instanceof NoContentException || e.cause instanceof NoContentException
    }

    void "a form of objects is written"() {
        given:
        def map = new MultivaluedHashMap<String, Object>()
        map.add("a", 1)
        map.add("b", "x y")
        def out = new ByteArrayOutputStream()

        when:
        new JaxRsMultivaluedMapMessageBodyWriter().writeTo((MultivaluedHashMap) map, MultivaluedHashMap, MultivaluedHashMap, NONE,
                MediaType.APPLICATION_FORM_URLENCODED_TYPE, null, out)

        then:
        out.toString("UTF-8") == "a=1&b=x%20y"
    }
}
