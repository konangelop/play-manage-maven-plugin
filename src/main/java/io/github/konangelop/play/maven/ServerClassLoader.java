package io.github.konangelop.play.maven;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * A child-first classloader for the Play dev server. Loads classes from
 * its own classpath (the project's runtime dependencies) before delegating
 * to the parent (Maven's plugin ClassRealm). Only shared interfaces
 * ({@code BuildLink}, {@code ReloadableServer}) delegate to the parent so
 * the plugin and server share the same types. Everything else (Play runtime,
 * SLF4J, Logback, Scala, Akka) is loaded child-first to avoid version
 * conflicts with the plugin's ClassRealm.
 */
class ServerClassLoader extends URLClassLoader {

    ServerClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c != null) return c;

            // Always delegate to parent for JDK classes and shared interfaces.
            // org.xml.sax, org.w3c.dom, and javax.xml are JDK classes (java.xml module)
            // but don't start with "java." so they need explicit delegation.
            if (name.startsWith("java.") || name.startsWith("javax.")
                    || name.startsWith("jdk.") || name.startsWith("sun.")
                    || name.startsWith("org.xml.") || name.startsWith("org.w3c.")
                    || name.startsWith("org.ietf.")
                    || name.startsWith("io.github.konangelop.")
                    || isSharedPlayClass(name)) {
                return super.loadClass(name, resolve);
            }

            // Child-first: try own classpath before parent for everything else
            try {
                c = findClass(name);
                if (resolve) resolveClass(c);
                return c;
            } catch (ClassNotFoundException ignored) {
                // fall through to parent
            }

            return super.loadClass(name, resolve);
        }
    }

    /**
     * Classes from play-build-link.jar that must be shared between plugin and server.
     */
    private static boolean isSharedPlayClass(String name) {
        return name.equals("play.core.BuildLink")
                || name.equals("play.core.Build")
                || name.equals("play.core.BuildDocHandler")
                || name.equals("play.core.server.ReloadableServer")
                || name.equals("play.TemplateImports");
    }
}
