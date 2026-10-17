package io.micronaut.jaxrs.common

import jakarta.ws.rs.core.Variant
import spock.lang.Specification

class VariantListBuilderSpec extends Specification {

    void "add of a variant without anything set fails"() {
        when:
        Variant.VariantListBuilder.newInstance().add()

        then:
        thrown(IllegalStateException)

        expect:
        Variant.VariantListBuilder.newInstance().languages(Locale.ENGLISH).add().build().size() == 1
        Variant.VariantListBuilder.newInstance().build().isEmpty()
    }
}
