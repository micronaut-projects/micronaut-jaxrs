package io.micronaut.jaxrs.common.body.standard

import io.micronaut.http.form.FormConfiguration
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.MultivaluedMap
import spock.lang.Specification

import java.lang.annotation.Annotation

class FormReaderConfigurationSpec extends Specification {

    static final Annotation[] NONE = new Annotation[0]

    void "a form entity is decoded with the limits of the form configuration"() {
        when:
        MultivaluedMap<String, ?> map = reader.readFrom(MultivaluedMap, MultivaluedMap, NONE,
                MediaType.APPLICATION_FORM_URLENCODED_TYPE, null, new ByteArrayInputStream("a=1;b=2&c=3".bytes))

        then:
        map.keySet() == ["a"] as Set
        map.getFirst("a") == "1;b=2&c=3"

        where:
        reader << [new JaxRsMultivaluedStringStringMapMessageBodyReader(new SemicolonFormConfiguration()),
                   new JaxRsMultivaluedStringObjectMapMessageBodyReader(new SemicolonFormConfiguration())]
    }

    void "a space of a written form is %20"() {
        given:
        def map = new jakarta.ws.rs.core.MultivaluedHashMap<String, String>()
        map.add("a b", "x+y z")
        def out = new ByteArrayOutputStream()

        when:
        new JaxRsMultivaluedMapMessageBodyWriter().writeTo(map, MultivaluedMap, MultivaluedMap, NONE,
                MediaType.APPLICATION_FORM_URLENCODED_TYPE, null, out)

        then:
        out.toString("UTF-8") == "a%20b=x%2By%20z"
    }

    static class SemicolonFormConfiguration implements FormConfiguration {
        @Override
        int getMaxDecodedKeyValueParameters() {
            1
        }

        @Override
        boolean isSemicolonIsNormalChar() {
            true
        }
    }
}
