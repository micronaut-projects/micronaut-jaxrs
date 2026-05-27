/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.jaxrs.container;

import io.micronaut.context.BeanContext;
import io.micronaut.context.annotation.Parameter;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.bind.ArgumentBinder;
import io.micronaut.core.convert.ConversionContext;
import io.micronaut.core.convert.exceptions.ConversionErrorException;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.ReturnType;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.bind.RequestBinderRegistry;
import io.micronaut.http.uri.UriMatchVariable;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.web.router.Router;
import io.micronaut.web.router.UriRouteInfo;
import io.micronaut.web.router.UriRouteMatch;
import io.micronaut.web.router.exceptions.UnsatisfiedRouteException;
import io.micronaut.web.router.filter.FilteredRouter;
import io.micronaut.web.router.filter.RouteMatchFilter;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Path;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Applies Jakarta REST route matching tie-breakers to JAX-RS routes.
 */
@Internal
@Singleton
final class JaxRsRouterListener implements BeanCreatedEventListener<Router> {

    private final BeanContext beanContext;
    private final RequestBinderRegistry requestBinderRegistry;

    JaxRsRouterListener(BeanContext beanContext, RequestBinderRegistry requestBinderRegistry) {
        this.beanContext = beanContext;
        this.requestBinderRegistry = requestBinderRegistry;
    }

    @Override
    public Router onCreated(BeanCreatedEvent<Router> event) {
        Router router = event.getBean();
        JaxRsRouteMatchFilter routeMatchFilter = new JaxRsRouteMatchFilter(router);
        return new JaxRsFilteredRouter(router, routeMatchFilter, beanContext, requestBinderRegistry);
    }

    private static final class JaxRsFilteredRouter extends FilteredRouter {
        private final Router router;
        private final RouteMatchFilter routeFilter;
        private final BeanContext beanContext;
        private final RequestBinderRegistry requestBinderRegistry;

        private JaxRsFilteredRouter(Router router,
                                    RouteMatchFilter routeFilter,
                                    BeanContext beanContext,
                                    RequestBinderRegistry requestBinderRegistry) {
            super(router, routeFilter);
            this.router = router;
            this.routeFilter = routeFilter;
            this.beanContext = beanContext;
            this.requestBinderRegistry = requestBinderRegistry;
        }

        @Override
        public <T, R> Stream<UriRouteMatch<T, R>> findAny(CharSequence uri, @Nullable HttpRequest<?> context) {
            Stream<UriRouteMatch<T, R>> matches = router.findAny(uri, context);
            if (context == null) {
                return matches;
            }
            return wrap(matches.filter(routeFilter.filter(context)), context);
        }

        @Override
        public <T, R> List<UriRouteMatch<T, R>> findAny(HttpRequest<?> request) {
            return wrap(router.<T, R>findAny(request).stream().filter(routeFilter.filter(request)), request).toList();
        }

        @Override
        public <T, R> Stream<UriRouteMatch<T, R>> find(HttpMethod httpMethod, CharSequence uri, @Nullable HttpRequest<?> context) {
            Stream<UriRouteMatch<T, R>> matches = router.find(httpMethod, uri, context);
            if (context == null) {
                return matches;
            }
            return wrap(matches.filter(routeFilter.filter(context)), context);
        }

        @Override
        public <T, R> List<UriRouteMatch<T, R>> findAllClosest(HttpRequest<?> request) {
            return wrap(router.<T, R>findAllClosest(request).stream().filter(routeFilter.filter(request)), request).toList();
        }

        @Override
        public <T, R> Stream<UriRouteMatch<T, R>> find(HttpRequest<?> request, CharSequence uri) {
            return wrap(router.<T, R>find(request, uri).filter(routeFilter.filter(request)), request);
        }

        @Override
        public <T, R> Stream<UriRouteMatch<T, R>> find(HttpRequest<?> request) {
            return wrap(router.<T, R>find(request).filter(routeFilter.filter(request)), request);
        }

        @Override
        public <T, R> Optional<UriRouteMatch<T, R>> route(HttpMethod httpMethod, CharSequence uri) {
            HttpRequest<?> request = HttpRequest.create(httpMethod, uri.toString());
            return wrap(
                router.<T, R>find(httpMethod, uri, request).filter(routeFilter.filter(request)),
                request
            )
                .findFirst();
        }

        private <T, R> Stream<UriRouteMatch<T, R>> wrap(Stream<UriRouteMatch<T, R>> matches, HttpRequest<?> request) {
            return matches.map(match -> wrap(match, request));
        }

        private <T, R> UriRouteMatch<T, R> wrap(UriRouteMatch<T, R> match, HttpRequest<?> request) {
            suppressMatrixRouteVariables(match);
            if (match.getArguments().length != 0) {
                return match;
            }
            Optional<BeanDefinition<T>> beanDefinition = beanContext.findBeanDefinition(match.getDeclaringType());
            if (beanDefinition.isPresent() && beanDefinition.get().hasAnnotation(JaxRsConstructorInjection.class)) {
                return new JaxRsConstructorInjectionRouteMatch<>(match, request, beanContext, requestBinderRegistry, beanDefinition.get());
            }
            return match;
        }

        private static void suppressMatrixRouteVariables(UriRouteMatch<?, ?> match) {
            String[] matrixRouteVariableNames = match.getAnnotationMetadata()
                .stringValues(JaxRsResourceTemplate.class, "matrixRouteVariableNames");
            if (matrixRouteVariableNames.length == 0) {
                return;
            }
            Map<String, Object> variableValues = match.getVariableValues();
            if (variableValues.isEmpty()) {
                return;
            }
            for (String matrixRouteVariableName : matrixRouteVariableNames) {
                variableValues.remove(matrixRouteVariableName);
            }
        }
    }

    private static final class JaxRsConstructorInjectionRouteMatch<T, R> implements UriRouteMatch<T, R> {
        private final UriRouteMatch<T, R> delegate;
        private final HttpRequest<?> request;
        private final BeanContext beanContext;
        private final RequestBinderRegistry requestBinderRegistry;
        private final BeanDefinition<T> beanDefinition;
        private @Nullable T target;

        private JaxRsConstructorInjectionRouteMatch(UriRouteMatch<T, R> delegate,
                                                    HttpRequest<?> request,
                                                    BeanContext beanContext,
                                                    RequestBinderRegistry requestBinderRegistry,
                                                    BeanDefinition<T> beanDefinition) {
            this.delegate = delegate;
            this.request = request;
            this.beanContext = beanContext;
            this.requestBinderRegistry = requestBinderRegistry;
            this.beanDefinition = beanDefinition;
        }

        @Override
        public T getTarget() {
            T target = this.target;
            if (target == null) {
                Map<String, Object> argumentValues = bindConstructorArguments();
                target = beanContext.createBean(beanDefinition.getBeanType(), beanDefinition.getDeclaredQualifier(), argumentValues);
                this.target = target;
            }
            return target;
        }

        private Map<String, Object> bindConstructorArguments() {
            Map<String, Object> argumentValues = new LinkedHashMap<>();
            for (Argument<?> argument : beanDefinition.getConstructor().getArguments()) {
                if (argument.getAnnotationMetadata().hasAnnotation(Parameter.class)) {
                    bindConstructorArgument(argumentValues, argument);
                }
            }
            return argumentValues;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private <E> void bindConstructorArgument(Map<String, Object> argumentValues, Argument<E> argument) {
            ArgumentBinder<E, HttpRequest<?>> binder = (ArgumentBinder<E, HttpRequest<?>>) requestBinderRegistry.findArgumentBinder(argument)
                .orElseThrow(() -> UnsatisfiedRouteException.create(argument));
            ArgumentBinder.BindingResult<E> result = binder.bind(ConversionContext.of(
                argument,
                request.getLocale().orElse(null),
                request.getCharacterEncoding()
            ), request);
            if (!result.getConversionErrors().isEmpty()) {
                throw new ConversionErrorException(argument, result.getConversionErrors().get(0));
            }
            Optional<E> value = result.getValue();
            if (value.isPresent()) {
                argumentValues.put(argument.getName(), value.get());
            } else if (argument.isNullable()) {
                argumentValues.put(argument.getName(), null);
            } else {
                throw UnsatisfiedRouteException.create(argument);
            }
        }

        @Override
        public @Nullable R execute() {
            return getExecutableMethod().invoke(getTarget());
        }

        @Override
        public @Nullable R invoke(@Nullable Object... arguments) {
            return getExecutableMethod().invoke(getTarget(), arguments);
        }

        @Override
        public Map<String, Object> getVariableValues() {
            return delegate.getVariableValues();
        }

        @Override
        @SuppressWarnings("removal")
        public void fulfill(Map<String, Object> argumentValues) {
            delegate.fulfill(argumentValues);
        }

        @Override
        public void fulfillBeforeFilters(RequestBinderRegistry requestBinderRegistry, HttpRequest<?> request) {
            delegate.fulfillBeforeFilters(requestBinderRegistry, request);
        }

        @Override
        public void fulfillAfterFilters(RequestBinderRegistry requestBinderRegistry, HttpRequest<?> request) {
            delegate.fulfillAfterFilters(requestBinderRegistry, request);
        }

        @Override
        public boolean isFulfilled() {
            return delegate.isFulfilled();
        }

        @Override
        public Optional<Argument<?>> getRequiredInput(String name) {
            return delegate.getRequiredInput(name);
        }

        @Override
        public List<Argument<?>> getRequiredArguments() {
            return delegate.getRequiredArguments();
        }

        @Override
        public boolean isSatisfied(String name) {
            return delegate.isSatisfied(name);
        }

        @Override
        public void close() {
            delegate.close();
        }

        @Override
        public String getUri() {
            return delegate.getUri();
        }

        @Override
        public List<UriMatchVariable> getVariables() {
            return delegate.getVariables();
        }

        @Override
        public Map<String, UriMatchVariable> getVariableMap() {
            return delegate.getVariableMap();
        }

        @Override
        public UriRouteInfo<T, R> getRouteInfo() {
            return delegate.getRouteInfo();
        }

        @Override
        public HttpMethod getHttpMethod() {
            return delegate.getHttpMethod();
        }

        @Override
        public Class<T> getDeclaringType() {
            return delegate.getDeclaringType();
        }

        @Override
        public Argument<?>[] getArguments() {
            return delegate.getArguments();
        }

        @Override
        public ExecutableMethod<T, R> getExecutableMethod() {
            return delegate.getExecutableMethod();
        }

        @Override
        public Method getTargetMethod() {
            return delegate.getTargetMethod();
        }

        @Override
        public ReturnType<R> getReturnType() {
            return delegate.getReturnType();
        }

        @Override
        public String getMethodName() {
            return delegate.getMethodName();
        }

        @Override
        public AnnotationMetadata getAnnotationMetadata() {
            return delegate.getAnnotationMetadata();
        }
    }

    private static final class JaxRsRouteMatchFilter implements RouteMatchFilter {
        private final Router router;

        private JaxRsRouteMatchFilter(Router router) {
            this.router = router;
        }

        @Override
        public <T, R> Predicate<UriRouteMatch<T, R>> filter(HttpRequest<?> request) {
            List<UriRouteMatch<Object, Object>> matches = router.find(request).toList();
            List<UriRouteMatch<Object, Object>> jaxRsMatches = matches.stream()
                .filter(JaxRsRouteMatchFilter::isJaxRsRoute)
                .toList();
            if (jaxRsMatches.isEmpty()) {
                return ignored -> true;
            }
            JaxRsRouteScore bestScore = jaxRsMatches.stream()
                .map(JaxRsRouteMatchFilter::score)
                .max(JaxRsRouteScore.COMPARATOR)
                .orElse(null);
            if (bestScore == null) {
                return ignored -> true;
            }
            Set<UriRouteInfo<?, ?>> selectedRoutes = jaxRsMatches.stream()
                .filter(match -> score(match).equals(bestScore))
                .map(UriRouteMatch::getRouteInfo)
                .collect(Collectors.toSet());
            return match -> !isJaxRsRoute(match) || selectedRoutes.contains(match.getRouteInfo());
        }

        private static boolean isJaxRsRoute(UriRouteMatch<?, ?> match) {
            return match.getRouteInfo().getAnnotationMetadata().hasAnnotation(Path.class);
        }

        private static JaxRsRouteScore score(UriRouteMatch<?, ?> match) {
            return JaxRsRouteScore.of(match.getRouteInfo().getUriMatchTemplate().toString());
        }
    }
}
