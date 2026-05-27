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
package io.micronaut.jaxrs.processor;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.Nullable;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Generates bean and resource metadata for unannotated Jakarta REST application classes.
 */
@Internal
@SupportedAnnotationTypes("*")
public final class JaxRsApplicationProcessor extends AbstractProcessor {

    private static final String APPLICATION_TYPE = "jakarta.ws.rs.core.Application";
    private static final String APPLICATION_PATH_TYPE = "jakarta.ws.rs.ApplicationPath";
    private static final String FACTORY_ANNOTATION = "io.micronaut.context.annotation.Factory";
    private static final String NAMED_ANNOTATION = "jakarta.inject.Named";
    private static final String PRIMARY_ANNOTATION = "io.micronaut.context.annotation.Primary";
    private static final String SINGLETON_ANNOTATION = "jakarta.inject.Singleton";
    private static final String RESOURCES_ANNOTATION = "io.micronaut.jaxrs.common.JaxRsApplicationResources";

    private final Set<String> generatedFactories = new LinkedHashSet<>();

    private Elements elementUtils;
    private Types typeUtils;
    private Filer filer;
    private @Nullable Trees trees;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        elementUtils = processingEnv.getElementUtils();
        typeUtils = processingEnv.getTypeUtils();
        filer = processingEnv.getFiler();
        try {
            trees = Trees.instance(processingEnv);
        } catch (IllegalArgumentException e) {
            trees = null;
        }
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            return false;
        }
        TypeElement applicationType = elementUtils.getTypeElement(APPLICATION_TYPE);
        if (applicationType == null) {
            return false;
        }
        for (Element rootElement : roundEnv.getRootElements()) {
            if (rootElement instanceof TypeElement typeElement && shouldGenerateFactory(typeElement, applicationType)) {
                Set<String> applicationResources = applicationResources(typeElement);
                generateFactory(typeElement, applicationResources);
            }
        }
        return false;
    }

    private boolean shouldGenerateFactory(TypeElement typeElement, TypeElement applicationType) {
        String applicationClassName = typeElement.getQualifiedName().toString();
        return !generatedFactories.contains(applicationClassName)
            && typeElement.getKind() == ElementKind.CLASS
            && !typeUtils.isSameType(typeElement.asType(), applicationType.asType())
            && typeUtils.isAssignable(typeElement.asType(), applicationType.asType())
            && !hasBeanDefiningAnnotation(typeElement)
            && hasAccessibleNoArgumentConstructor(typeElement);
    }

    private boolean hasBeanDefiningAnnotation(TypeElement typeElement) {
        for (AnnotationMirror annotationMirror : typeElement.getAnnotationMirrors()) {
            String annotationName = annotationMirror.getAnnotationType().toString();
            if (annotationName.equals(APPLICATION_PATH_TYPE)
                || annotationName.equals(SINGLETON_ANNOTATION)
                || annotationName.startsWith("io.micronaut.context.annotation.")) {
                return true;
            }
            Element annotationElement = annotationMirror.getAnnotationType().asElement();
            if (annotationElement instanceof TypeElement annotationType && hasAnnotation(annotationType, "jakarta.inject.Scope")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAnnotation(TypeElement typeElement, String annotationName) {
        for (AnnotationMirror annotationMirror : typeElement.getAnnotationMirrors()) {
            if (annotationMirror.getAnnotationType().toString().equals(annotationName)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAccessibleNoArgumentConstructor(TypeElement typeElement) {
        boolean hasConstructor = false;
        for (Element enclosedElement : typeElement.getEnclosedElements()) {
            if (enclosedElement instanceof ExecutableElement executableElement
                && executableElement.getKind() == ElementKind.CONSTRUCTOR) {
                hasConstructor = true;
                if (executableElement.getParameters().isEmpty()
                    && !executableElement.getModifiers().contains(Modifier.PRIVATE)) {
                    return true;
                }
            }
        }
        return !hasConstructor;
    }

    private Set<String> applicationResources(TypeElement typeElement) {
        Trees trees = this.trees;
        if (trees == null) {
            return Set.of();
        }
        Set<String> resourceClassNames = new LinkedHashSet<>();
        for (Element enclosedElement : typeElement.getEnclosedElements()) {
            if (enclosedElement instanceof ExecutableElement executableElement
                && executableElement.getSimpleName().contentEquals("getClasses")
                && executableElement.getParameters().isEmpty()) {
                TreePath methodPath = trees.getPath(executableElement);
                if (methodPath != null && methodPath.getLeaf() instanceof MethodTree methodTree) {
                    Tree body = methodTree.getBody();
                    if (body != null) {
                        ClassLiteralScanner scanner = new ClassLiteralScanner(trees);
                        scanner.scan(new TreePath(methodPath, body), resourceClassNames);
                        if (!scanner.isComplete()) {
                            return Set.of();
                        }
                    }
                }
            }
        }
        return resourceClassNames;
    }

    private void generateFactory(TypeElement applicationType, Set<String> applicationResources) {
        String applicationClassName = applicationType.getQualifiedName().toString();
        if (!generatedFactories.add(applicationClassName)) {
            return;
        }
        PackageElement packageElement = elementUtils.getPackageOf(applicationType);
        String packageName = packageElement.isUnnamed() ? "" : packageElement.getQualifiedName().toString();
        String factorySimpleName = applicationType.getSimpleName() + "$JaxRsApplicationFactory";
        String factoryClassName = packageName.isEmpty() ? factorySimpleName : packageName + "." + factorySimpleName;
        try {
            JavaFileObject sourceFile = filer.createSourceFile(factoryClassName, applicationType);
            try (Writer writer = sourceFile.openWriter()) {
                writeFactory(writer, packageName, factorySimpleName, applicationClassName, applicationResources);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot generate Jakarta REST application factory for " + applicationClassName, e);
        }
    }

    private static void writeFactory(Writer writer,
                                     String packageName,
                                     String factorySimpleName,
                                     String applicationClassName,
                                     Set<String> applicationResources) throws IOException {
        if (!packageName.isEmpty()) {
            writer.write("package ");
            writer.write(packageName);
            writer.write(";\n\n");
        }
        writer.write("@");
        writer.write(FACTORY_ANNOTATION);
        writer.write("\nfinal class ");
        writer.write(factorySimpleName);
        writer.write(" {\n\n");
        writer.write("    @");
        writer.write(SINGLETON_ANNOTATION);
        writer.write("\n    @");
        writer.write(PRIMARY_ANNOTATION);
        writer.write("\n    @");
        writer.write(NAMED_ANNOTATION);
        writer.write("\n");
        if (!applicationResources.isEmpty()) {
            writer.write("    @");
            writer.write(RESOURCES_ANNOTATION);
            writer.write("({");
            writeStringArray(writer, applicationResources);
            writer.write("})\n");
        }
        writer.write("    ");
        writer.write(applicationClassName);
        writer.write(" application() {\n");
        writer.write("        return new ");
        writer.write(applicationClassName);
        writer.write("();\n");
        writer.write("    }\n");
        writer.write("}\n");
    }

    private static void writeStringArray(Writer writer, Set<String> values) throws IOException {
        boolean first = true;
        for (String value : values) {
            if (first) {
                first = false;
            } else {
                writer.write(", ");
            }
            writer.write("\"");
            writer.write(value.replace("\\", "\\\\").replace("\"", "\\\""));
            writer.write("\"");
        }
    }

    private final class ClassLiteralScanner extends TreePathScanner<Void, Set<String>> {
        private final Trees trees;
        private boolean complete = true;

        private ClassLiteralScanner(Trees trees) {
            this.trees = trees;
        }

        private boolean isComplete() {
            return complete;
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree node, Set<String> classNames) {
            if (node.getIdentifier().contentEquals("class")) {
                TypeElement typeElement = typeElement(node.getExpression());
                if (typeElement != null) {
                    classNames.add(typeElement.getQualifiedName().toString());
                }
            }
            return super.visitMemberSelect(node, classNames);
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Set<String> classNames) {
            if (!isSupportedClassRegistration(node)) {
                complete = false;
            }
            return super.visitMethodInvocation(node, classNames);
        }

        private boolean isSupportedClassRegistration(MethodInvocationTree node) {
            String methodName = methodName(node.getMethodSelect());
            List<? extends ExpressionTree> arguments = node.getArguments();
            if ("add".equals(methodName)) {
                return arguments.size() == 1 && isClassLiteral(arguments.get(0));
            }
            if ("of".equals(methodName) || "singleton".equals(methodName)) {
                return !arguments.isEmpty() && arguments.stream().allMatch(this::isClassLiteral);
            }
            return false;
        }

        private @Nullable String methodName(Tree methodSelect) {
            if (methodSelect instanceof MemberSelectTree memberSelectTree) {
                return memberSelectTree.getIdentifier().toString();
            }
            return null;
        }

        private boolean isClassLiteral(Tree tree) {
            return tree instanceof MemberSelectTree memberSelectTree
                && memberSelectTree.getIdentifier().contentEquals("class");
        }

        private @Nullable TypeElement typeElement(Tree expression) {
            TreePath path = new TreePath(getCurrentPath(), expression);
            Element element = trees.getElement(path);
            if (element instanceof TypeElement typeElement) {
                return typeElement;
            }
            TypeMirror typeMirror = trees.getTypeMirror(path);
            if (typeMirror != null && typeMirror.getKind() != TypeKind.ERROR) {
                Element typeElement = typeUtils.asElement(typeMirror);
                if (typeElement instanceof TypeElement resolvedTypeElement) {
                    return resolvedTypeElement;
                }
            }
            return null;
        }
    }
}
