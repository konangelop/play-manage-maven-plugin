package io.github.konangelop.play.maven;

import play.core.BuildLink;
import play.core.server.ReloadableServer;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.InvocationResult;
import org.apache.maven.shared.invoker.Invoker;
import org.apache.maven.shared.invoker.MavenInvocationException;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a Play Framework application in development mode with true hot-reload.
 *
 * <p>Uses Play's {@link BuildLink} interface for classloader-swap hot reload:
 * on each HTTP request, Play calls {@code BuildLink.reload()} which checks
 * for source changes, triggers an incremental Maven rebuild if needed, and
 * returns a fresh classloader — all without restarting the JVM.</p>
 *
 * <p>The dev server is started via {@code DevServerStart.mainDevHttpMode()}
 * which is loaded reflectively from the project's runtime classpath
 * (the consumer project must depend on {@code play-akka-http-server} or
 * another Play server backend).</p>
 */
@Mojo(name = "run", requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, threadSafe = true)
public class RunMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    /**
     * HTTP port for the Play dev server.
     */
    @Parameter(defaultValue = "9000", property = "play.httpPort")
    private int httpPort;

    /**
     * HTTPS port for the Play dev server. Set to -1 to disable.
     */
    @Parameter(defaultValue = "-1", property = "play.httpsPort")
    private int httpsPort;

    /**
     * Address to bind the server to.
     */
    @Parameter(defaultValue = "0.0.0.0", property = "play.httpAddress")
    private String httpAddress;

    /**
     * Dev mode settings, passed to the Play application.
     * Format: "key1=value1,key2=value2"
     */
    @Parameter(property = "play.devSettings")
    private String devSettings;

    /**
     * Directories to watch for changes. Defaults to app, conf, and public.
     */
    @Parameter(property = "play.watchDirectories")
    private List<String> watchDirectories;

    /**
     * Maven goals to execute on rebuild.
     */
    @Parameter(defaultValue = "process-classes", property = "play.runGoals")
    private String runGoals;

    /**
     * Skip the run goal.
     */
    @Parameter(defaultValue = "false", property = "play.run.skip")
    private boolean skip;

    private volatile boolean running = true;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping Play run");
            return;
        }

        // Initial build
        getLog().info("Performing initial build...");
        if (!invokeMavenBuild()) {
            throw new MojoExecutionException("Initial build failed");
        }

        // Set up file watching to detect changes
        List<Path> watchPaths = getWatchPaths();
        WatchService watchService;
        Map<WatchKey, Path> watchKeys = new HashMap<>();
        try {
            watchService = FileSystems.getDefault().newWatchService();
            for (Path watchPath : watchPaths) {
                registerDirectoryTree(watchService, watchPath, watchKeys);
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to set up file watching: " + e.getMessage(), e);
        }

        // Build the server classloader first — MavenBuildLink needs it as parent for app classloaders.
        // Parent is the plugin classloader so BuildLink/ReloadableServer are shared.
        URLClassLoader serverClassLoader;
        try {
            URL[] serverUrls = buildRuntimeClasspathUrls();
            serverClassLoader = ServerClassLoader.create(serverUrls, getClass().getClassLoader());
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to build runtime classpath: " + e.getMessage(), e);
        }

        // Track source changes for the BuildLink
        MavenBuildLink buildLink = new MavenBuildLink(
                project, session, watchService, watchKeys, watchPaths, runGoals,
                serverClassLoader, getLog());

        // Parse dev settings
        if (devSettings != null && !devSettings.isEmpty()) {
            for (String setting : devSettings.split(",")) {
                String[] kv = setting.trim().split("=", 2);
                if (kv.length == 2) {
                    buildLink.devSettings.put(kv[0].trim(), kv[1].trim());
                }
            }
        }

        // Start the Play dev server using DevServerStart via reflection
        ReloadableServer server;
        try {
            server = startDevServer(buildLink, serverClassLoader);
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to start Play dev server: " + e.getMessage(), e);
        }

        getLog().info("Play application started in dev mode on http://" + httpAddress + ":" + httpPort);
        getLog().info("Watching for changes in: " + watchPaths);
        getLog().info("Press Ctrl+C to stop");

        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running = false;
            server.stop();
            getLog().info("Play dev server stopped");
        }));

        // Block until interrupted
        try {
            while (running) {
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            // Normal shutdown
        } finally {
            server.stop();
            try {
                watchService.close();
            } catch (IOException ignored) {
            }
        }
    }

    private ReloadableServer startDevServer(BuildLink buildLink, URLClassLoader serverClassLoader) throws Exception {
        // Load DevServerStart$ singleton
        Class<?> devServerStartClass = serverClassLoader.loadClass("play.core.server.DevServerStart$");
        Object devServerStart = devServerStartClass.getField("MODULE$").get(null);

        if (httpsPort > 0 && httpPort > 0) {
            return (ReloadableServer) devServerStartClass
                    .getMethod("mainDevHttpAndHttpsMode", BuildLink.class, int.class, int.class, String.class)
                    .invoke(devServerStart, buildLink, httpPort, httpsPort, httpAddress);
        } else if (httpsPort > 0) {
            return (ReloadableServer) devServerStartClass
                    .getMethod("mainDevOnlyHttpsMode", BuildLink.class, int.class, String.class)
                    .invoke(devServerStart, buildLink, httpsPort, httpAddress);
        } else {
            return (ReloadableServer) devServerStartClass
                    .getMethod("mainDevHttpMode", BuildLink.class, int.class, String.class)
                    .invoke(devServerStart, buildLink, httpPort, httpAddress);
        }
    }

    private URL[] buildRuntimeClasspathUrls() throws Exception {
        List<URL> urls = new ArrayList<>();
        // conf/ must be on the classpath for application.conf and logback.xml
        File confDir = new File(project.getBasedir(), "conf");
        if (confDir.isDirectory()) {
            urls.add(confDir.toURI().toURL());
        }
        // Do NOT include the output directory here — application classes go on the
        // app classloader (created fresh on each reload) so hot-reload works.
        // Only dependency JARs go on the server classloader.
        String outputDir = new File(project.getBuild().getOutputDirectory()).toURI().toString();
        for (String element : project.getRuntimeClasspathElements()) {
            String elementUri = new File(element).toURI().toString();
            if (!elementUri.equals(outputDir)) {
                urls.add(new File(element).toURI().toURL());
            }
        }
        return urls.toArray(new URL[0]);
    }

    private List<Path> getWatchPaths() {
        List<Path> paths = new ArrayList<>();
        File basedir = project.getBasedir();

        if (watchDirectories != null && !watchDirectories.isEmpty()) {
            for (String dir : watchDirectories) {
                Path path = basedir.toPath().resolve(dir);
                if (Files.isDirectory(path)) {
                    paths.add(path);
                }
            }
        } else {
            for (String dir : new String[]{"app", "conf", "public"}) {
                Path path = basedir.toPath().resolve(dir);
                if (Files.isDirectory(path)) paths.add(path);
            }
        }
        return paths;
    }

    private void registerDirectoryTree(WatchService watchService, Path root, Map<WatchKey, Path> watchKeys) throws IOException {
        if (!Files.isDirectory(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                WatchKey key = dir.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
                watchKeys.put(key, dir);
                return FileVisitResult.CONTINUE;
            }
        });
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
            getLog().error("Maven invocation failed: " + e.getMessage());
            return false;
        }
    }
}
