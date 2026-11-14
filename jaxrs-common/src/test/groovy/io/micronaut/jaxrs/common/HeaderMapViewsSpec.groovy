package io.micronaut.jaxrs.common

import io.micronaut.context.ApplicationContext
import spock.lang.Specification

class HeaderMapViewsSpec extends Specification {

    void "the header map views are not beans"() {
        given:
        def context = ApplicationContext.run()

        expect:
        !context.containsBean(JaxRsObjectHeadersMultivaluedMap)
        !context.containsBean(JaxRsMutableObjectHeadersMultivaluedMap)

        cleanup:
        context.close()
    }
}
