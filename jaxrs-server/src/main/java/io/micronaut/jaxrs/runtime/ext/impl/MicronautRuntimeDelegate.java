package io.micronaut.jaxrs.runtime.ext.impl;

import io.micronaut.core.annotation.Internal;
import io.micronaut.jaxrs.runtime.core.JaxRsResponseBuilder;
import io.micronaut.jaxrs.runtime.core.JaxRsUriBuilder;

import javax.ws.rs.core.*;
import javax.ws.rs.ext.RuntimeDelegate;
import java.util.HashMap;
import java.util.Map;

/**
 * RuntimeDelegate implementation for JAX-RS.
 *
 * @author graemerocher
 * @since 1.0
 */
@Internal
public final class MicronautRuntimeDelegate extends RuntimeDelegate {

    private static final Map<Class, HeaderDelegate> HEADER_DELEGATES = new HashMap<>();

    static {
        HEADER_DELEGATES.put(MediaType.class, new MediaTypeHeaderDelegate());
        HEADER_DELEGATES.put(Cookie.class, new CookieHeaderDelegate());
        HEADER_DELEGATES.put(EntityTag.class, new EntityTagDelegate());
        HEADER_DELEGATES.put(NewCookie.class, new NewCookieHeaderDelegate());
        HEADER_DELEGATES.put(Link.class, new LinkDelegate());
        HEADER_DELEGATES.put(CacheControl.class, CacheControlDelegate.INSTANCE);
    }

    @Override
    public UriBuilder createUriBuilder() {
        return new JaxRsUriBuilder();
    }

    @Override
    public Response.ResponseBuilder createResponseBuilder() {
        return new JaxRsResponseBuilder();
    }

    @Override
    public Variant.VariantListBuilder createVariantListBuilder() {
        throw new UnsupportedOperationException("Method createVariantListBuilder() not supported by implementation");
    }

    @Override
    public <T> T createEndpoint(Application application, Class<T> endpointType) throws IllegalArgumentException, UnsupportedOperationException {
        throw new UnsupportedOperationException("Method createEndpoint(..) not supported by implementation");
    }

    @Override
    public <T> HeaderDelegate<T> createHeaderDelegate(Class<T> type) throws IllegalArgumentException {
        final HeaderDelegate headerDelegate = HEADER_DELEGATES.get(type);
        if (headerDelegate != null) {
            return headerDelegate;
        }
        throw new UnsupportedOperationException("Unsupported header type: " + type);
    }

    @Override
    public Link.Builder createLinkBuilder() {
        return new LinkBuilderImpl();
    }
}
