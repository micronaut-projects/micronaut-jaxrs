package io.micronaut.jaxrs.common.reflect

import spock.lang.Specification

import java.lang.ref.WeakReference

class StringConversionCacheSpec extends Specification {

    void "the cached conversions do not keep the class loader of an application"() {
        given:
        WeakReference<ClassLoader> loader = LeakProbeConversion.convertWithOwnLoader()

        when:
        for (int i = 0; i < 50 && loader.get() != null; i++) {
            System.gc()
            Thread.sleep(20)
        }

        then:
        loader.get() == null
    }
}
