package io.micronaut.jaxrs.runtime.core;

import io.micronaut.context.BeanContext;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.naming.conventions.PropertyConvention;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.web.router.RouteBuilder;
import io.micronaut.web.router.naming.HyphenatedUriNamingStrategy;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;

/**
 * Configures a URI naming strategy based on the {@link ApplicationPath} annotation.
 *
 * @author graemerocher
 * @since 1.0
 */
@Singleton
@Requires(classes = RouteBuilder.UriNamingStrategy.class)
@Requires(beans = Application.class)
@Replaces(HyphenatedUriNamingStrategy.class)
@Primary
public class JaxRsApplicationUriNamingStrategy extends HyphenatedUriNamingStrategy {

    private final String contextPath;

    /**
     * Constructs a new uri naming strategy for the given property.
     *
     * @param beanContext The bean context
     *
     */
    @Inject
    public JaxRsApplicationUriNamingStrategy(BeanContext beanContext) {
        this.contextPath = normalizeContextPath(
                beanContext.getBeanDefinition(Application.class)
                    .stringValue(ApplicationPath.class)
                    .orElse("/")
        );
    }

    @Override
    public String resolveUri(Class type) {
        return contextPath + super.resolveUri(type);
    }

    @Override
    public @Nonnull String resolveUri(BeanDefinition<?> beanDefinition) {
        return contextPath + super.resolveUri(beanDefinition);
    }

    @Override
    public @Nonnull String resolveUri(String property) {
        return contextPath + super.resolveUri(property);
    }

    @Override
    public @Nonnull String resolveUri(Class type, PropertyConvention id) {
        return contextPath + super.resolveUri(type, id);
    }

    private String normalizeContextPath(String contextPath) {
        if (contextPath.charAt(0) != '/') {
            contextPath = '/' + contextPath;
        }
        if (contextPath.charAt(contextPath.length() - 1) == '/') {
            contextPath = contextPath.substring(0, contextPath.length() - 1);
        }
        return contextPath;
    }
}

