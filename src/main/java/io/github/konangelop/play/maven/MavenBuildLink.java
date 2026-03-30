package io.github.konangelop.play.maven;

import play.core.BuildLink;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.InvocationResult;
import org.apache.maven.shared.invoker.Invoker;
import org.apache.maven.shared.invoker.MavenInvocationException;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Enumeration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementation of Play's {@link BuildLink} interface for Maven-based dev mode.
 *
 * <p>Play calls {@link #reload()} on each HTTP request. This implementation checks
 * the file watcher for source changes, triggers an incremental Maven rebuild if
 * needed, and returns a fresh {@link ClassLoader} pointing to the updated classes.
 * No JVM restart is required — Play swaps the application classloader internally.</p>
 */
class MavenBuildLink implements BuildLink {

    private final MavenProject project;
    private final MavenSession session;
    private final WatchService watchService;
    private final Map<WatchKey, Path> watchKeys;
    private final List<Path> watchPaths;
    private final String runGoals;
    private final ClassLoader serverClassLoader;
    private final Log log;

    final Map<String, String> devSettings = new HashMap<>();

    // true so first reload() provides a ClassLoader without triggering a rebuild
    // (the initial build already ran in RunMojo.execute())
    private volatile boolean initialLoad = true;
    private volatile boolean forceReload = false;
    private volatile Throwable lastBuildError = null;
    private volatile ClassLoader currentAppClassLoader = null;

    MavenBuildLink(MavenProject project, MavenSession session,
                   WatchService watchService, Map<WatchKey, Path> watchKeys,
                   List<Path> watchPaths, String runGoals,
                   ClassLoader serverClassLoader, Log log) {
        this.project = project;
        this.session = session;
        this.watchService = watchService;
        this.watchKeys = watchKeys;
        this.watchPaths = watchPaths;
        this.runGoals = runGoals;
        this.serverClassLoader = serverClassLoader;
        this.log = log;
    }

    /**
     * Called by Play on each HTTP request to check if a reload is needed.
     *
     * @return {@code null} if no changes, a {@link ClassLoader} if rebuild succeeded,
     *         or a {@link Throwable} if the build failed (Play shows the error page).
     */
    @Override
    public Object reload() {
        // On the very first reload() call, just provide a ClassLoader —
        // the initial build already ran in RunMojo.execute(), no need to rebuild.
        if (initialLoad) {
            initialLoad = false;
            try {
                currentAppClassLoader = createApplicationClassLoader();
                return currentAppClassLoader;
            } catch (Exception e) {
                lastBuildError = e;
                return e;
            }
        }

        // Poll once to avoid draining events across two calls
        boolean changesDetected = hasSourceChanges();

        // If previous build failed and no new changes, keep showing the error
        if (lastBuildError != null && !changesDetected && !forceReload) {
            return lastBuildError;
        }

        boolean needsReload = forceReload || changesDetected;
        forceReload = false;

        if (!needsReload) {
            return null; // No changes — serve from current classloader
        }

        log.info("Source changes detected, rebuilding...");

        boolean buildSuccess = invokeMavenBuild();

        if (buildSuccess) {
            lastBuildError = null;
            log.info("Build successful, reloading application");
            try {
                // Close previous app classloader to prevent Metaspace leak
                if (currentAppClassLoader instanceof Closeable) {
                    try {
                        ((Closeable) currentAppClassLoader).close();
                    } catch (IOException ignored) {
                    }
                }
                currentAppClassLoader = createApplicationClassLoader();
                return currentAppClassLoader;
            } catch (Exception e) {
                lastBuildError = e;
                return e;
            }
        } else {
            lastBuildError = new RuntimeException("Maven build failed. Check console output for details.");
            return lastBuildError;
        }
    }

    @Override
    public File projectPath() {
        return project.getBasedir();
    }

    @Override
    public void forceReload() {
        forceReload = true;
    }

    @Override
    public Map<String, String> settings() {
        return devSettings;
    }

    /**
     * Called by Play to find the source file for a given class (for error pages).
     *
     * @return {@code Object[]} with {File sourceFile, Integer lineNumber}, or null.
     */
    @Override
    public Object[] findSource(String className, Integer line) {
        // Convert class name to file path and search in source directories
        String javaPath = className.replace('.', File.separatorChar) + ".java";
        String scalaPath = className.replace('.', File.separatorChar) + ".scala";

        for (Path watchPath : watchPaths) {
            Path javaFile = watchPath.resolve(javaPath);
            if (Files.exists(javaFile)) {
                return new Object[]{javaFile.toFile(), line};
            }
            Path scalaFile = watchPath.resolve(scalaPath);
            if (Files.exists(scalaFile)) {
                return new Object[]{scalaFile.toFile(), line};
            }
        }

        // Also check main source directory
        Path srcMain = project.getBasedir().toPath().resolve("app");
        for (String ext : new String[]{".java", ".scala"}) {
            Path file = srcMain.resolve(className.replace('.', File.separatorChar) + ext);
            if (Files.exists(file)) {
                return new Object[]{file.toFile(), line};
            }
        }

        return null;
    }

    /**
     * Polls the WatchService for any source file changes without blocking.
     */
    private boolean hasSourceChanges() {
        boolean changed = false;
        WatchKey key;
        while ((key = watchService.poll()) != null) {
            Path dir = watchKeys.get(key);
            if (dir != null) {
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;
                    Path changedPath = dir.resolve((Path) event.context());
                    String name = changedPath.getFileName().toString();

                    if (isSourceFile(name)) {
                        log.debug("Changed: " + changedPath);
                        changed = true;
                    }

                    // Register new directories
                    if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE
                            && Files.isDirectory(changedPath)) {
                        try {
                            WatchKey newKey = changedPath.register(watchService,
                                    StandardWatchEventKinds.ENTRY_CREATE,
                                    StandardWatchEventKinds.ENTRY_MODIFY,
                                    StandardWatchEventKinds.ENTRY_DELETE);
                            watchKeys.put(newKey, changedPath);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            key.reset();
        }
        return changed;
    }

    private boolean isSourceFile(String name) {
        return name.endsWith(".java") || name.endsWith(".scala")
                || name.endsWith(".scala.html") || name.endsWith(".scala.txt")
                || name.endsWith(".scala.xml") || name.endsWith(".scala.js")
                || name.endsWith(".routes") || name.equals("routes")
                || name.endsWith(".conf");
    }

    private ClassLoader createApplicationClassLoader() throws Exception {
        // Only the project's compiled classes go in the app classloader.
        // Dependencies and Play framework are already on the server classloader (parent).
        // This isolation lets Play swap the app classloader on each reload.
        //
        // Override getResources() to delegate entirely to parent, matching
        // Play's DelegatedResourcesClassLoader. This prevents target/classes from
        // contributing duplicate resources (e.g., persistence.xml) that are already
        // found via the server classloader's dependency JARs. Class loading and
        // getResource() (singular) still search target/classes normally.
        URL[] urls = { new File(project.getBuild().getOutputDirectory()).toURI().toURL() };
        return new URLClassLoader(urls, serverClassLoader) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                return getParent().getResources(name);
            }
        };
    }

    private boolean invokeMavenBuild() {
        try {
            InvocationRequest request = new DefaultInvocationRequest();
            request.setPomFile(project.getFile());
            request.setGoals(Arrays.asList(runGoals.split("\\s+")));
            request.setBatchMode(true);
            request.setProperties(session.getUserProperties());
            Invoker invoker = new DefaultInvoker();
            InvocationResult result = invoker.execute(request);
            return result.getExitCode() == 0;
        } catch (MavenInvocationException e) {
            log.error("Maven invocation failed: " + e.getMessage());
            return false;
        }
    }
}
