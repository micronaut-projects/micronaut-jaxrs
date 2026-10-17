package io.micronaut.jaxrs.common

import jakarta.ws.rs.ext.RuntimeDelegate
import spock.lang.Specification

class DateHeaderDelegateSpec extends Specification {

    void "a date header is an HTTP date"() {
        given:
        def delegate = RuntimeDelegate.getInstance().createHeaderDelegate(Date)

        expect:
        delegate.toString(new Date(784111777000L)) == "Sun, 06 Nov 1994 08:49:37 GMT"
        delegate.fromString("Sun, 06 Nov 1994 08:49:37 GMT") == new Date(784111777000L)
        delegate.fromString("Sunday, 06-Nov-94 08:49:37 GMT") == new Date(784111777000L)
        delegate.fromString("Sun Nov  6 08:49:37 1994") == new Date(784111777000L)
        RuntimeDelegate.getInstance().createHeaderDelegate(java.sql.Timestamp).toString(new java.sql.Timestamp(784111777000L)) == "Sun, 06 Nov 1994 08:49:37 GMT"
        JaxRsHeaderUtil.headerToString(new Date(784111777000L)) == "Sun, 06 Nov 1994 08:49:37 GMT"
    }
}
