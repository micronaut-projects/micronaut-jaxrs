/*
 * Copyright 2017-2023 original authors
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
package io.micronaut.validation.tck;

import io.micronaut.annotation.processing.AggregatingTypeElementVisitorProcessor;
import io.micronaut.annotation.processing.BeanDefinitionInjectProcessor;
import io.micronaut.annotation.processing.TypeElementVisitorProcessor;
import io.micronaut.core.annotation.Internal;
import io.micronaut.jaxrs.processor.JaxRsTypeElementVisitor;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ArchivePath;
import org.jboss.shrinkwrap.api.Node;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.annotation.processing.Processor;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * IMPORTANT: assumes that it is possible to iterate through the {@code Archive}
 * and for each {@code .class} file in there, find a corresponding {@code .java}
 * file in this class's classloader. In other words, the CDI TCK source JAR must
 * be on classpath.
 */
@Internal
final class ArchiveCompiler {
    private static final String GENERATED_SECURITY_FILTER = "io.micronaut.validation.tck.generated.TckBasicAuthFilter";
    private static final String WEB_XML_PATH = "/WEB-INF/web.xml";

    private final DeploymentDir deploymentDir;
    private final Archive<?> deploymentArchive;
    private final Class<?> testJavaClass;

    ArchiveCompiler(DeploymentDir deploymentDir, Archive<?> deploymentArchive, Class<?> testJavaClass) {
        this.deploymentDir = deploymentDir;
        this.deploymentArchive = deploymentArchive;
        this.testJavaClass = testJavaClass;
    }

    void compile() throws ArchiveCompilationException, ArchiveCompilerException {
        try {
            if (deploymentArchive instanceof WebArchive) {
                compileWar();
            } else {
                throw new ArchiveCompilerException("Unknown archive type: " + deploymentArchive);
            }
        } catch (IOException e) {
            throw new ArchiveCompilerException("Compilation failed", e);
        }
    }

    private void compileWar() throws ArchiveCompilationException, IOException {
        List<File> sourceFiles = new ArrayList<>();
        for (Map.Entry<ArchivePath, Node> entry : deploymentArchive.getContent().entrySet()) {
            String path = entry.getKey().get();
            if (path.startsWith("/WEB-INF/classes") && path.endsWith(".class")) {
                String sourceFile = path.replace("/WEB-INF/classes", "")
                    .replace(".class", ".java");

                if (sourceFile.contains("$")) {
                    // skip nested classes
                    //
                    // this is crude, maybe there's a better way?
                    continue;
                }

                Path sourceFilePath = resolveInside(deploymentDir.source, sourceFile.substring(1)); // sourceFile begins with `/`

                Files.createDirectories(sourceFilePath.getParent()); // make sure the directory exists
                try (InputStream in = ArchiveCompiler.class.getResourceAsStream(sourceFile)) {
                    if (in == null) {
                        // This might be a non-inner class defined in another class
                        continue;
                    }
                    Files.copy(in, sourceFilePath);
                }

                sourceFiles.add(sourceFilePath.toFile());
            } else if (path.startsWith("/WEB-INF/classes/") && entry.getValue().getAsset() != null) {
                String resource = path.replace("/WEB-INF/classes", "");
                Path resourcePath = resolveInside(deploymentDir.target, resource.substring(1)); // resource begins with `/`

                Files.createDirectories(resourcePath.getParent()); // make sure the directory exists
                try (InputStream in = entry.getValue().getAsset().openStream()) {
                    Files.copy(in, resourcePath);
                }
            } else if (path.startsWith("/WEB-INF/lib") && path.endsWith(".jar")) {
                String jarFile = path.replace("/WEB-INF/lib", "");
                Path jarFilePath = resolveInside(deploymentDir.lib, jarFile.substring(1)); // jarFile begins with `/`

                Files.createDirectories(jarFilePath.getParent()); // make sure the directory exists
                try (InputStream in = entry.getValue().getAsset().openStream()) {
                    Files.copy(in, jarFilePath);
                }
            }
        }

        generateSecurityFilter(sourceFiles);
        doCompile(sourceFiles, deploymentDir.target.toFile());
    }

    private void generateSecurityFilter(List<File> sourceFiles) throws IOException {
        BasicAuthDescriptor descriptor = basicAuthDescriptor();
        if (descriptor == null) {
            return;
        }
        Path filterSource = deploymentDir.source.resolve(GENERATED_SECURITY_FILTER.replace('.', '/') + ".java");
        Files.createDirectories(filterSource.getParent());
        Files.writeString(filterSource, securityFilterSource(descriptor));
        sourceFiles.add(filterSource.toFile());
    }

    private BasicAuthDescriptor basicAuthDescriptor() throws IOException {
        String webXml = readArchiveDescriptor(WEB_XML_PATH);
        if (webXml == null) {
            return null;
        }
        Document document = parseXml(webXml);
        if (!hasBasicAuth(document)) {
            return null;
        }
        List<SecurityConstraint> constraints = securityConstraints(document);
        if (constraints.isEmpty()) {
            return null;
        }
        Map<String, Set<String>> principalRoles = principalRoles(constraints);
        if (principalRoles.isEmpty()) {
            return null;
        }
        return new BasicAuthDescriptor(constraints, principalRoles);
    }

    private String readArchiveDescriptor(String descriptorPath) throws IOException {
        for (Map.Entry<ArchivePath, Node> entry : deploymentArchive.getContent().entrySet()) {
            if (!descriptorPath.equals(entry.getKey().get()) || entry.getValue().getAsset() == null) {
                continue;
            }
            try (InputStream inputStream = entry.getValue().getAsset().openStream()) {
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private String readTckResource(String resourcePath) throws IOException {
        try (InputStream inputStream = ArchiveCompiler.class.getClassLoader().getResourceAsStream(resourcePath)) {
            return inputStream == null ? null : new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Document parseXml(String source) throws IOException {
        try {
            DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
            documentBuilderFactory.setNamespaceAware(true);
            documentBuilderFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            documentBuilderFactory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            documentBuilderFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            documentBuilderFactory.setXIncludeAware(false);
            documentBuilderFactory.setExpandEntityReferences(false);
            return documentBuilderFactory.newDocumentBuilder().parse(new InputSource(new StringReader(source)));
        } catch (Exception e) {
            throw new IOException("Unable to parse deployment descriptor", e);
        }
    }

    private static boolean hasBasicAuth(Document document) {
        return descendants(document.getDocumentElement(), "auth-method")
            .stream()
            .map(ArchiveCompiler::text)
            .anyMatch(authMethod -> "BASIC".equalsIgnoreCase(authMethod));
    }

    private static List<SecurityConstraint> securityConstraints(Document document) {
        List<SecurityConstraint> result = new ArrayList<>();
        for (Element securityConstraint : descendants(document.getDocumentElement(), "security-constraint")) {
            Set<String> roles = new LinkedHashSet<>();
            for (Element authConstraint : childElements(securityConstraint, "auth-constraint")) {
                descendants(authConstraint, "role-name").stream()
                    .map(ArchiveCompiler::text)
                    .filter(role -> !role.isBlank())
                    .forEach(roles::add);
            }
            if (roles.isEmpty()) {
                continue;
            }
            for (Element webResourceCollection : childElements(securityConstraint, "web-resource-collection")) {
                Set<String> methods = descendants(webResourceCollection, "http-method")
                    .stream()
                    .map(ArchiveCompiler::text)
                    .filter(method -> !method.isBlank())
                    .map(method -> method.toUpperCase(Locale.ROOT))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
                for (Element urlPattern : descendants(webResourceCollection, "url-pattern")) {
                    String pattern = text(urlPattern);
                    if (!pattern.isBlank()) {
                        result.add(new SecurityConstraint(pattern, methods, roles));
                    }
                }
            }
        }
        return result;
    }

    private Map<String, Set<String>> principalRoles(List<SecurityConstraint> constraints) throws IOException {
        String packagePath = testJavaClass.getPackageName().replace('.', '/');
        String sunWebXml = readTckResource(packagePath + "/" + deploymentArchive.getName() + ".sun-web.xml");
        if (sunWebXml == null) {
            return defaultPrincipalRoles(constraints);
        }
        Document document = parseXml(sunWebXml);
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (Element mapping : descendants(document.getDocumentElement(), "security-role-mapping")) {
            String roleName = childElements(mapping, "role-name")
                .stream()
                .findFirst()
                .map(ArchiveCompiler::text)
                .orElse("");
            if (roleName.isBlank()) {
                continue;
            }
            for (Element principalName : childElements(mapping, "principal-name")) {
                String principal = text(principalName);
                if (!principal.isBlank()) {
                    result.computeIfAbsent(principal, ignored -> new LinkedHashSet<>()).add(roleName);
                }
            }
        }
        return result;
    }

    private static Map<String, Set<String>> defaultPrincipalRoles(List<SecurityConstraint> constraints) {
        Set<String> roles = constraints.stream()
            .flatMap(constraint -> constraint.roles().stream())
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, Set<String>> result = new LinkedHashMap<>();
        if (roles.contains("DIRECTOR")) {
            result.put("j2ee", Set.of("DIRECTOR"));
        }
        if (roles.contains("OTHERROLE")) {
            result.put("javajoe", Set.of("OTHERROLE"));
        }
        return result;
    }

    private static List<Element> descendants(Element element, String localName) {
        NodeList nodeList = element.getElementsByTagNameNS("*", localName);
        List<Element> result = new ArrayList<>(nodeList.getLength());
        for (int i = 0; i < nodeList.getLength(); i++) {
            if (nodeList.item(i) instanceof Element child) {
                result.add(child);
            }
        }
        if (result.isEmpty()) {
            nodeList = element.getElementsByTagName(localName);
            for (int i = 0; i < nodeList.getLength(); i++) {
                if (nodeList.item(i) instanceof Element child) {
                    result.add(child);
                }
            }
        }
        return result;
    }

    private static List<Element> childElements(Element element, String localName) {
        List<Element> result = new ArrayList<>();
        NodeList childNodes = element.getChildNodes();
        for (int i = 0; i < childNodes.getLength(); i++) {
            org.w3c.dom.Node child = childNodes.item(i);
            if (child instanceof Element childElement && matches(childElement, localName)) {
                result.add(childElement);
            }
        }
        return result;
    }

    private static boolean matches(Element element, String localName) {
        return localName.equals(element.getLocalName()) || localName.equals(element.getNodeName());
    }

    private static String text(Element element) {
        return element.getTextContent() == null ? "" : element.getTextContent().trim();
    }

    private static String securityFilterSource(BasicAuthDescriptor descriptor) {
        StringBuilder builder = new StringBuilder();
        builder.append("package io.micronaut.validation.tck.generated;\n\n");
        builder.append("import io.micronaut.http.context.ServerRequestContext;\n");
        builder.append("import jakarta.annotation.Priority;\n");
        builder.append("import jakarta.ws.rs.container.ContainerRequestContext;\n");
        builder.append("import jakarta.ws.rs.container.ContainerRequestFilter;\n");
        builder.append("import jakarta.ws.rs.core.HttpHeaders;\n");
        builder.append("import jakarta.ws.rs.core.Response;\n");
        builder.append("import jakarta.ws.rs.core.SecurityContext;\n");
        builder.append("import jakarta.ws.rs.ext.Provider;\n");
        builder.append("import java.io.IOException;\n");
        builder.append("import java.nio.charset.StandardCharsets;\n");
        builder.append("import java.security.Principal;\n");
        builder.append("import java.util.Base64;\n");
        builder.append("import java.util.List;\n");
        builder.append("import java.util.Locale;\n");
        builder.append("import java.util.Map;\n");
        builder.append("import java.util.Set;\n\n");
        builder.append("@Provider\n");
        builder.append("@Priority(-1000)\n");
        builder.append("public final class TckBasicAuthFilter implements ContainerRequestFilter {\n");
        builder.append("    private static final List<Constraint> CONSTRAINTS = List.of(\n");
        for (int i = 0; i < descriptor.constraints().size(); i++) {
            SecurityConstraint constraint = descriptor.constraints().get(i);
            builder.append("        new Constraint(")
                .append(quote(constraint.pattern()))
                .append(", ")
                .append(setOf(constraint.methods()))
                .append(", ")
                .append(setOf(constraint.roles()))
                .append(")");
            if (i < descriptor.constraints().size() - 1) {
                builder.append(",");
            }
            builder.append("\n");
        }
        builder.append("    );\n");
        builder.append("    private static final Map<String, Set<String>> PRINCIPAL_ROLES = Map.ofEntries(\n");
        List<Map.Entry<String, Set<String>>> principalRoles = new ArrayList<>(descriptor.principalRoles().entrySet());
        for (int i = 0; i < principalRoles.size(); i++) {
            Map.Entry<String, Set<String>> entry = principalRoles.get(i);
            builder.append("        Map.entry(")
                .append(quote(entry.getKey()))
                .append(", ")
                .append(setOf(entry.getValue()))
                .append(")");
            if (i < principalRoles.size() - 1) {
                builder.append(",");
            }
            builder.append("\n");
        }
        builder.append("    );\n\n");
        builder.append("""
    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        Constraint constraint = matchingConstraint(requestContext);
        if (constraint == null) {
            return;
        }
        Credentials credentials = credentials(requestContext.getHeaderString(HttpHeaders.AUTHORIZATION));
        if (credentials == null) {
            requestContext.abortWith(unauthorized());
            return;
        }
        Set<String> roles = PRINCIPAL_ROLES.get(credentials.user());
        if (roles == null || !validPassword(credentials)) {
            requestContext.abortWith(unauthorized());
            return;
        }
        if (roles.stream().noneMatch(constraint.roles()::contains)) {
            requestContext.abortWith(Response.status(Response.Status.FORBIDDEN).build());
            return;
        }
        SecurityContext delegate = requestContext.getSecurityContext();
        Principal principal = credentials::user;
        SecurityContext securityContext = new SecurityContext() {
            @Override
            public Principal getUserPrincipal() {
                return principal;
            }

            @Override
            public boolean isUserInRole(String role) {
                return roles.contains(role);
            }

            @Override
            public boolean isSecure() {
                return delegate.isSecure();
            }

            @Override
            public String getAuthenticationScheme() {
                return SecurityContext.BASIC_AUTH;
            }
        };
        requestContext.setSecurityContext(securityContext);
        ServerRequestContext.currentRequest().ifPresent(request -> request.setUserPrincipal(principal));
    }

    private static Constraint matchingConstraint(ContainerRequestContext requestContext) {
        String path = requestContext.getUriInfo().getPath(false);
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        String method = requestContext.getMethod().toUpperCase(Locale.ROOT);
        for (Constraint constraint : CONSTRAINTS) {
            if (constraint.matches(path, method)) {
                return constraint;
            }
        }
        return null;
    }

    private static Credentials credentials(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            return null;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(authorization.substring(6).trim()), StandardCharsets.ISO_8859_1);
            int separator = decoded.indexOf(':');
            if (separator < 0) {
                return null;
            }
            return new Credentials(decoded.substring(0, separator), decoded.substring(separator + 1));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean validPassword(Credentials credentials) {
        if (credentials.user().equals(System.getProperty("user"))) {
            return credentials.password().equals(System.getProperty("password"));
        }
        if (credentials.user().equals(System.getProperty("authuser"))) {
            return credentials.password().equals(System.getProperty("authpassword"));
        }
        return false;
    }

    private static Response unauthorized() {
        return Response.status(Response.Status.UNAUTHORIZED)
            .header("WWW-Authenticate", "Basic")
            .build();
    }

    private record Constraint(String pattern, Set<String> methods, Set<String> roles) {
        boolean matches(String path, String method) {
            return (methods.isEmpty() || methods.contains(method)) && matchesPath(path);
        }

        private boolean matchesPath(String path) {
            if (pattern.endsWith("/*")) {
                String prefix = pattern.substring(0, pattern.length() - 2);
                return path.equals(prefix) || path.startsWith(prefix + "/");
            }
            return path.equals(pattern);
        }
    }

    private record Credentials(String user, String password) {
    }
}
""");
        return builder.toString();
    }

    private static String setOf(Collection<String> values) {
        if (values.isEmpty()) {
            return "Set.of()";
        }
        return values.stream()
            .map(ArchiveCompiler::quote)
            .collect(Collectors.joining(", ", "Set.of(", ")"));
    }

    static String quote(String value) {
        StringBuilder result = new StringBuilder(value.length() + 2);
        result.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> result.append("\\\\");
                case '"' -> result.append("\\\"");
                case '\b' -> result.append("\\b");
                case '\t' -> result.append("\\t");
                case '\n' -> result.append("\\n");
                case '\f' -> result.append("\\f");
                case '\r' -> result.append("\\r");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        result.append(String.format("\\u%04x", (int) c));
                    } else {
                        result.append(c);
                    }
                }
            }
        }
        result.append('"');
        return result.toString();
    }

    static Path resolveInside(Path root, String child) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path resolved = normalizedRoot.resolve(child).normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new IOException("Archive entry escapes deployment directory: " + child);
        }
        return resolved;
    }

    private void doCompile(Collection<File> testSources, File outputDir) throws ArchiveCompilationException, IOException {
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager mgr = compiler.getStandardFileManager(diagnostics, null, null)) {
            final String targetDir = outputDir.getAbsolutePath();
            JavaCompiler.CompilationTask task = compiler.getTask(
                null,
                mgr,
                diagnostics,
                Arrays.asList(
                    "-d", targetDir,
                    "-verbose",
                    "-parameters",
                    "-Amicronaut.route.validation=false",
                    "-A" + JaxRsTypeElementVisitor.OPTION_FAIL_ON_UNSUPPORTED + "=false"
                ),
                null,
                mgr.getJavaFileObjectsFromFiles(
                    Stream.concat(
                        testSources.stream(),
                        Stream.of(
                            applicationClass(testSources).toFile()
                        )
                    ).toList()
                )
            );
            task.setProcessors(getAnnotationProcessors());
            Boolean success = task.call();
            if (!Boolean.TRUE.equals(success)) {
                outputDiagnostics(diagnostics);
            }
        }
    }

    private Path applicationClass(Collection<File> testSources) throws IOException {
        String annotations = "";
        String packageName = "ee.jakarta.tck.ws.rs";
        final Path packagePath = deploymentDir.target.resolve(
            packageName.replace('.', '/')
        );
        Files.createDirectories(packagePath);
        final Path applicationSource = packagePath.resolve("Application.java");
        String sourceCode = "package " + packageName + ";\n" +
            annotations + " class Application {}";
        Files.writeString(applicationSource, sourceCode);
        return applicationSource;
    }

    private void outputDiagnostics(DiagnosticCollector<JavaFileObject> diagnostics) throws ArchiveCompilationException {
        throw new ArchiveCompilationException("Compilation failed:\n" + diagnostics.getDiagnostics()
            .stream()
            .map(it -> {
                System.out.println(it);
                if (it.getSource() == null) {
                    return "- " + it.getMessage(Locale.US);
                }
                Path source = deploymentDir.source.relativize(Paths.get(it.getSource().toUri().getPath()));
                return "- " + source + ":" + it.getLineNumber() + " " + it.getMessage(Locale.US);
            })
            .collect(Collectors.joining("\n")));
    }

    private List<Processor> getAnnotationProcessors() {
        List<Processor> result = new ArrayList<>();
        result.add(new TypeElementVisitorProcessor());
        result.add(new AggregatingTypeElementVisitorProcessor());
        result.add(new BeanDefinitionInjectProcessor() {
            @Override
            protected boolean isProcessedAnnotation(String annotationName) {
                return true;
            }
        });
        return result;
    }

    private record BasicAuthDescriptor(List<SecurityConstraint> constraints, Map<String, Set<String>> principalRoles) {
    }

    private record SecurityConstraint(String pattern, Set<String> methods, Set<String> roles) {
    }

}
