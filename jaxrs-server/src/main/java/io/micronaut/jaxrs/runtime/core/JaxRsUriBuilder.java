package io.micronaut.jaxrs.runtime.core;

import io.micronaut.core.util.ArgumentUtils;

import javax.ws.rs.Path;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriBuilderException;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.Map;

/**
 * Partial implementation of {@link UriBuilder}. Unsupported methods throw {@link UnsupportedOperationException}.
 *
 * @author graemerocher
 * @since 1.0
 */
public class JaxRsUriBuilder extends UriBuilder {

    private io.micronaut.http.uri.UriBuilder uriBuilder;

    /**
     * Default constructor.
     */
    public JaxRsUriBuilder() {
        this.uriBuilder = io.micronaut.http.uri.UriBuilder.of("/");
    }

    /**
     * Copy constructor.
     * @param uriBuilder The uri builder
     */
    JaxRsUriBuilder(io.micronaut.http.uri.UriBuilder uriBuilder) {
        this.uriBuilder = uriBuilder;
    }

    @Override
    public UriBuilder clone() {
        return new JaxRsUriBuilder(io.micronaut.http.uri.UriBuilder.of(uriBuilder.build()));
    }

    @Override
    public UriBuilder uri(URI uri) {
        ArgumentUtils.requireNonNull("uri", uri);
        uriBuilder.replacePath(uri.getPath());
        uriBuilder.scheme(uri.getScheme());
        uriBuilder.port(uri.getPort());
        uriBuilder.fragment(uri.getFragment());
        uriBuilder.userInfo(uri.getUserInfo());
        uriBuilder.host(uri.getHost());
        return this;
    }

    @Override
    public UriBuilder uri(String uriTemplate) {
        return uri(URI.create(uriTemplate));
    }

    @Override
    public UriBuilder scheme(String scheme) {
        uriBuilder.scheme(scheme);
        return this;
    }

    @Override
    public UriBuilder schemeSpecificPart(String ssp) {
        throw new UnsupportedOperationException("Method schemeSpecificPart(..) not supported by implementation");
    }

    @Override
    public UriBuilder userInfo(String ui) {
        uriBuilder.userInfo(ui);
        return this;
    }

    @Override
    public UriBuilder host(String host) {
        uriBuilder.host(host);
        return this;
    }

    @Override
    public UriBuilder port(int port) {
        uriBuilder.port(port);
        return this;
    }

    @Override
    public UriBuilder replacePath(String path) {
        uriBuilder.replacePath(path);
        return this;
    }

    @Override
    public UriBuilder path(String path) {
        uriBuilder.path(path);
        return this;
    }

    @Override
    public UriBuilder path(Class resource) {
        final Path annotation = (Path) resource.getAnnotation(Path.class);
        if (annotation != null) {
            uriBuilder.path(annotation.value());
        }

        return this;
    }

    @Override
    public UriBuilder path(Class resource, String method) {
        return this;
    }

    @Override
    public UriBuilder path(Method method) {
        final Path annotation = method.getAnnotation(Path.class);
        if (annotation != null) {
            uriBuilder.path(annotation.value());
        }
        return this;
    }

    @Override
    public UriBuilder segment(String... segments) {
        return path(String.join("/", segments));
    }

    @Override
    public UriBuilder replaceMatrix(String matrix) {
        throw new UnsupportedOperationException("Method replaceMatrix(..) not supported by implementation");
    }

    @Override
    public UriBuilder matrixParam(String name, Object... values) {
        throw new UnsupportedOperationException("Method matrixParam(..) not supported by implementation");
    }

    @Override
    public UriBuilder replaceMatrixParam(String name, Object... values) {
        throw new UnsupportedOperationException("Method replaceMatrixParam(..) not supported by implementation");
    }

    @Override
    public UriBuilder replaceQuery(String query) {
        throw new UnsupportedOperationException("Method replaceQuery(..) not supported by implementation");
    }

    @Override
    public UriBuilder queryParam(String name, Object... values) {
        uriBuilder.queryParam(name, values);
        return this;
    }

    @Override
    public UriBuilder replaceQueryParam(String name, Object... values) {
        throw new UnsupportedOperationException("Method replaceQueryParam(..) not supported by implementation");
    }

    @Override
    public UriBuilder fragment(String fragment) {
        uriBuilder.fragment(fragment);
        return this;
    }

    @Override
    public UriBuilder resolveTemplate(String name, Object value) {
        throw new UnsupportedOperationException("Method resolveTemplate(..) not supported by implementation");
    }

    @Override
    public UriBuilder resolveTemplate(String name, Object value, boolean encodeSlashInPath) {
        throw new UnsupportedOperationException("Method resolveTemplate(..) not supported by implementation");
    }

    @Override
    public UriBuilder resolveTemplateFromEncoded(String name, Object value) {
        throw new UnsupportedOperationException("Method resolveTemplateFromEncoded(..) not supported by implementation");
    }

    @Override
    public UriBuilder resolveTemplates(Map<String, Object> templateValues) {
        throw new UnsupportedOperationException("Method resolveTemplates(..) not supported by implementation");
    }

    @Override
    public UriBuilder resolveTemplates(Map<String, Object> templateValues, boolean encodeSlashInPath) throws IllegalArgumentException {
        throw new UnsupportedOperationException("Method resolveTemplates(..) not supported by implementation");
    }

    @Override
    public UriBuilder resolveTemplatesFromEncoded(Map<String, Object> templateValues) {
        throw new UnsupportedOperationException("Method resolveTemplateFromEncoded(..) not supported by implementation");
    }

    @Override
    public URI buildFromMap(Map<String, ?> values) {
        return uriBuilder.expand((Map<String, ? super Object>) values);
    }

    @Override
    public URI buildFromMap(Map<String, ?> values, boolean encodeSlashInPath) throws IllegalArgumentException, UriBuilderException {
        return uriBuilder.expand((Map<String, ? super Object>) values);
    }

    @Override
    public URI buildFromEncodedMap(Map<String, ?> values) throws IllegalArgumentException, UriBuilderException {
        return uriBuilder.expand((Map<String, ? super Object>) values);
    }

    @Override
    public URI build(Object... values) throws IllegalArgumentException, UriBuilderException {
        return uriBuilder.build();
    }

    @Override
    public URI build(Object[] values, boolean encodeSlashInPath) throws IllegalArgumentException, UriBuilderException {
        return uriBuilder.build();
    }

    @Override
    public URI buildFromEncoded(Object... values) throws IllegalArgumentException, UriBuilderException {
        return uriBuilder.build();
    }

    @Override
    public String toTemplate() {
        return uriBuilder.toString();
    }
}
