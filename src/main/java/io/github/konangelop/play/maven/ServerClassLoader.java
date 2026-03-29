package io.github.konangelop.play.maven;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Enumeration;
import java.util.NoSuchElementException;

/**
 * Classloader setup for the Play dev server, matching the architecture from
 * Play's SBT-based Reloader and the old play-maven-plugin.
 *
 * <p>The classloader chain is:
 * <ol>
 *   <li>{@link BuildSharedClassLoader} — parent is {@code null} (completely
 *       isolated from Maven). Only delegates shared interfaces ({@code BuildLink},
 *       {@code ReloadableServer}) to Maven's plugin ClassRealm so the plugin
 *       and server can communicate via the same types.</li>
 *   <li><b>DependencyClassLoader</b> — standard {@link URLClassLoader} containing
 *       all project runtime dependency JARs + conf/. Parent is BuildSharedClassLoader.
 *       Created once at startup.</li>
 *   <li><b>ApplicationClassLoader</b> — standard {@link URLClassLoader} containing
 *       only {@code target/classes}. Parent is DependencyClassLoader.
 *       <b>Recreated on each hot-reload.</b></li>
 * </ol>
 *
 * <p>Key design: the dependency classloader's root parent is {@code null}, NOT
 * Maven's ClassRealm. This prevents Maven's plugin dependencies from leaking
 * into the server and causing class conflicts (e.g., duplicate Config, SLF4J).
 */
class ServerClassLoader {

    /**
     * Creates the dependency classloader for the dev server.
     *
     * @param urls        runtime dependency JARs + conf directory
     * @param buildLoader Maven's plugin classloader (for shared interfaces only)
     * @return a classloader isolated from Maven, containing all dependency JARs
     */
    static URLClassLoader create(URL[] urls, ClassLoader buildLoader) {
        ClassLoader sharedLoader = new BuildSharedClassLoader(buildLoader);
        return new URLClassLoader(urls, sharedLoader);
    }

    /**
     * Classloader with {@code null} parent that only bridges shared Play
     * interfaces to the Maven plugin's ClassRealm. Everything else is invisible.
     * This is equivalent to the old plugin's DelegatingClassLoader + CommonClassLoader.
     */
    private static class BuildSharedClassLoader extends ClassLoader {

        private final ClassLoader buildLoader;

        BuildSharedClassLoader(ClassLoader buildLoader) {
            super(null); // null parent — fully isolated from Maven
            this.buildLoader = buildLoader;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            // JDK classes — delegate to bootstrap/platform loader
            if (name.startsWith("java.") || name.startsWith("javax.")
                    || name.startsWith("jdk.") || name.startsWith("sun.")
                    || name.startsWith("org.xml.") || name.startsWith("org.w3c.")
                    || name.startsWith("org.ietf.")) {
                return ClassLoader.getPlatformClassLoader().loadClass(name);
            }

            // Shared interfaces that the plugin and server must agree on
            if (isSharedClass(name)) {
                Class<?> c = buildLoader.loadClass(name);
                if (resolve) resolveClass(c);
                return c;
            }

            throw new ClassNotFoundException(name);
        }

        @Override
        public URL getResource(String name) {
            return null;
        }

        @Override
        public Enumeration<URL> getResources(String name) {
            return new Enumeration<>() {
                @Override public boolean hasMoreElements() { return false; }
                @Override public URL nextElement() { throw new NoSuchElementException(); }
            };
        }

        private static boolean isSharedClass(String name) {
            return name.equals("play.core.BuildLink")
                    || name.equals("play.core.Build")
                    || name.equals("play.core.BuildDocHandler")
                    || name.equals("play.core.server.ReloadableServer")
                    || name.equals("play.TemplateImports");
        }
    }
}
