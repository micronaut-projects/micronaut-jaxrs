package io.micronaut.jaxrs.processor;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.HttpMethodMapping;
import io.micronaut.http.annotation.UriMapping;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;

import javax.ws.rs.BeanParam;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.MatrixParam;
import javax.ws.rs.Path;
import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.List;

/**
 * A type element visitor that turns a JAX-RS path into a controller.
 *
 * @author graemerocher
 * @since 1.0
 */
public class JaxRsTypeElementVisitor implements TypeElementVisitor<Object, Object> {

    public static final int POSITION = 200;

    @Override
    public int getOrder() {
        return POSITION; // higher priority to ensure mutations visible
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        if (element.hasAnnotation(Path.class) && !element.isAbstract()) {
            element.annotate(Controller.class, builder ->
                    element.stringValue(Path.class).ifPresent(builder::value)
            );
        }
    }

    @Override
    public void visitMethod(MethodElement element, VisitorContext context) {
        if (element.hasStereotype(HttpMethod.class)) {
            final ParameterElement[] parameters = element.getParameters();
            for (ParameterElement parameter : parameters) {
                final List<Class<? extends Annotation>> unsupported = getUnsupportedParameterAnnotations();
                for (Class<? extends Annotation> annType : unsupported) {
                    if (parameter.hasAnnotation(annType)) {
                        context.fail("Unsupported JAX-RS annotation used on method: " + annType.getName(), parameter);
                    }
                }
            }

        }

        if (element.hasDeclaredAnnotation(Path.class)) {
            element.annotate(HttpMethodMapping.class, builder ->
                    builder.value(element.stringValue(Path.class).orElse(UriMapping.DEFAULT_URI))
            );
        } else {
            element.annotate(HttpMethodMapping.class, builder ->
                    builder.value(UriMapping.DEFAULT_URI)
            );
        }

    }

    private List<Class<? extends Annotation>> getUnsupportedParameterAnnotations() {
        return Arrays.asList(MatrixParam.class, BeanParam.class);
    }
}
