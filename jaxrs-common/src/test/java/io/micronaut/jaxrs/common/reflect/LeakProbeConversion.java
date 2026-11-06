package io.micronaut.jaxrs.common.reflect;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.function.Function;

/**
 * A String conversion that a test loads with a class loader of its own, see StringConversionCacheSpec.
 */
public final class LeakProbeConversion implements StringConversion {

    @Override
    public Class<?> type() {
        return Probe.class;
    }

    @Override
    public Object convert(String value) {
        return new Probe();
    }

    /**
     * Convert a value to the probe type of a class loader of its own, which the conversion is the service of.
     *
     * @return The class loader
     * @throws Exception If it fails
     */
    public static WeakReference<ClassLoader> convertWithOwnLoader() throws Exception {
        ProbeLoader loader = new ProbeLoader(LeakProbeConversion.class.getClassLoader());
        Class<?> type = loader.loadClass(Probe.class.getName());
        if (type.getClassLoader() != loader) {
            throw new IllegalStateException("not loaded by the probe loader");
        }
        Function<String, Object> conversion = new MetadataJaxRsReflection().stringConversion(type);
        if (conversion == null || conversion.apply("x").getClass() != type) {
            throw new IllegalStateException("no conversion");
        }
        return new WeakReference<>(loader);
    }

    /**
     * The converted type.
     */
    public static final class Probe {
    }

    /**
     * Loads the probe classes itself, and lists the probe conversion as a service.
     */
    private static final class ProbeLoader extends ClassLoader {
        private final URL services;

        ProbeLoader(ClassLoader parent) throws IOException {
            super(parent);
            Path file = Files.createTempFile("services", ".txt");
            file.toFile().deleteOnExit();
            Files.writeString(file, LeakProbeConversion.class.getName() + "\n", StandardCharsets.UTF_8);
            services = file.toUri().toURL();
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith(LeakProbeConversion.class.getName())) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                            byte[] bytes = in.readAllBytes();
                            loaded = defineClass(name, bytes, 0, bytes.length);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }
                    return loaded;
                }
            }
            return super.loadClass(name, resolve);
        }

        @Override
        public Enumeration<URL> getResources(String name) {
            if (name.equals("META-INF/services/" + StringConversion.class.getName())) {
                return Collections.enumeration(List.of(services));
            }
            return Collections.emptyEnumeration();
        }
    }
}
