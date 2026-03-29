package io.github.konangelop.play.maven;

import java.net.URL;
import java.net.URLClassLoader;

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
            // JDK classes — delegate to bootstrap/platform loader.
            // Important: javax.inject, javax.persistence, javax.annotation etc. are
            // NOT JDK classes — they're third-party (Jakarta EE / JSR-330) and must
            // be loaded from the dependency JARs, not the platform classloader.
            if (name.startsWith("java.") || isJdkJavaxClass(name)
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

        /**
         * Returns true only for javax.* packages that are part of the JDK
         * (java.xml, java.sql, java.crypto, etc.). Third-party javax packages
         * like javax.inject, javax.persistence, javax.annotation are NOT JDK
         * classes and must be loaded from the project's dependency JARs.
         */
        private static boolean isJdkJavaxClass(String name) {
            if (!name.startsWith("javax.")) return false;
            return name.startsWith("javax.crypto.")
                    || name.startsWith("javax.net.")
                    || name.startsWith("javax.security.")
                    || name.startsWith("javax.xml.")
                    || name.startsWith("javax.sql.")
                    || name.startsWith("javax.naming.")
                    || name.startsWith("javax.management.")
                    || name.startsWith("javax.lang.model.")
                    || name.startsWith("javax.tools.")
                    || name.startsWith("javax.annotation.processing.")
                    || name.startsWith("javax.accessibility.")
                    || name.startsWith("javax.imageio.")
                    || name.startsWith("javax.print.")
                    || name.startsWith("javax.sound.")
                    || name.startsWith("javax.swing.")
                    || name.startsWith("javax.script.");
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
