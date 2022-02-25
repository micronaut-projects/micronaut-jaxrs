package io.micronaut.jaxrs.runtime.core

import spock.lang.Specification

class ParameterParserSpec extends Specification {

    void "it can set attributes"() {
        given:
        ParameterParser parser = new ParameterParser()
        def string = "key1=value1,key2=value2"

        when:
        String result = parser.setAttribute(string.toCharArray(), 0, string.size(), ',' as char, "key1", "appended")

        then:
        result == "key1=appendedvalue1,key2=value2"

    }

}
