package io.github.konangelop.play.maven;

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
import java.util.stream.Collectors;

/**
 * Runs a Play Framework application in development mode with hot-reload.
 * Watches source directories for changes and triggers incremental rebuilds
 * via Maven, then restarts the application in a forked JVM process.
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
     * Additional JVM arguments for the Play server process.
     */
    @Parameter(property = "play.serverJvmArgs")
    private String serverJvmArgs;

    /**
     * Dev mode settings, passed as system properties to the Play application.
     * Format: "key1=value1,key2=value2"
     */
    @Parameter(property = "play.devSettings")
    private String devSettings;

    /**
     * Directories to watch for changes. Defaults to app and conf.
     */
    @Parameter(property = "play.watchDirectories")
    private List<String> watchDirectories;

    /**
     * Maven goals to execute on rebuild. Default: generate-sources process-classes.
     */
    @Parameter(defaultValue = "generate-sources process-classes", property = "play.runGoals")
    private String runGoals;

    /**
     * Main class for the Play application.
     */
    @Parameter(defaultValue = "play.core.server.ProdServerStart", property = "play.mainClass")
    private String mainClass;

    /**
     * Skip the run goal.
     */
    @Parameter(defaultValue = "false", property = "play.run.skip")
    private boolean skip;

    private volatile boolean running = true;
    private volatile Process playProcess;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping Play run");
            return;
        }

        getLog().info("Starting Play application in dev mode on port " + httpPort);

        List<Path> watchPaths = getWatchPaths();
        if (watchPaths.isEmpty()) {
            throw new MojoExecutionException("No watch directories found");
        }

        // Initial build
        getLog().info("Performing initial build...");
        boolean buildSuccess = invokeMavenBuild();
        if (!buildSuccess) {
            getLog().warn("Initial build failed, starting watch mode anyway");
        }

        // Start Play in forked process
        startPlayProcess();

        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running = false;
            stopPlayProcess();
            getLog().info("Shutting down Play dev server...");
        }));

        // Watch for changes
        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            Map<WatchKey, Path> watchKeys = new HashMap<>();
            for (Path watchPath : watchPaths) {
                registerDirectoryTree(watchService, watchPath, watchKeys);
            }

            getLog().info("Watching for changes in: " + watchPaths);
            getLog().info("Press Ctrl+C to stop");

            while (running) {
                WatchKey key;
                try {
                    key = watchService.take();
                } catch (InterruptedException e) {
                    break;
                }

                Path dir = watchKeys.get(key);
                if (dir == null) {
                    key.reset();
                    continue;
                }

                boolean hasChanges = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    if (kind == StandardWatchEventKinds.OVERFLOW) continue;

                    Path changed = dir.resolve((Path) event.context());
                    String fileName = changed.getFileName().toString();

                    if (fileName.endsWith(".java") || fileName.endsWith(".scala") ||
                            fileName.endsWith(".html") || fileName.endsWith(".routes") ||
                            fileName.equals("routes") || fileName.endsWith(".conf") ||
                            fileName.endsWith(".xml") || fileName.endsWith(".txt") ||
                            fileName.endsWith(".js")) {
                        getLog().info("Change detected: " + changed);
                        hasChanges = true;
                    }

                    if (kind == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(changed)) {
                        registerDirectoryTree(watchService, changed, watchKeys);
                    }
                }

                if (hasChanges) {
                    getLog().info("Rebuilding...");
                    stopPlayProcess();
                    boolean success = invokeMavenBuild();
                    if (success) {
                        getLog().info("Build successful, restarting application...");
                        startPlayProcess();
                    } else {
                        getLog().warn("Build failed, fix errors and save to retry");
                    }
                }

                if (!key.reset()) {
                    watchKeys.remove(key);
                }
            }
        } catch (IOException e) {
            throw new MojoExecutionException("File watching failed: " + e.getMessage(), e);
        } finally {
            stopPlayProcess();
        }
    }

    private void startPlayProcess() throws MojoExecutionException {
        try {
            String classpath = buildClasspath();
            List<String> command = new ArrayList<>();
            command.add(getJavaExecutable());

            // JVM args
            if (serverJvmArgs != null && !serverJvmArgs.isEmpty()) {
                command.addAll(Arrays.asList(serverJvmArgs.split("\\s+")));
            }

            // Play system properties
            command.add("-Dplay.server.http.port=" + httpPort);
            command.add("-Dplay.server.http.address=" + httpAddress);
            if (httpsPort > 0) {
                command.add("-Dplay.server.https.port=" + httpsPort);
            }

            // Dev settings
            if (devSettings != null && !devSettings.isEmpty()) {
                for (String setting : devSettings.split(",")) {
                    String[] kv = setting.trim().split("=", 2);
                    if (kv.length == 2) {
                        command.add("-D" + kv[0].trim() + "=" + kv[1].trim());
                    }
                }
            }

            command.add("-cp");
            command.add(classpath);
            command.add(mainClass);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(project.getBasedir());
            pb.inheritIO();

            playProcess = pb.start();
            getLog().info("Play application starting on http://" + httpAddress + ":" + httpPort);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to start Play application: " + e.getMessage(), e);
        }
    }

    private void stopPlayProcess() {
        if (playProcess != null && playProcess.isAlive()) {
            playProcess.destroy();
            try {
                playProcess.waitFor(java.util.concurrent.TimeUnit.SECONDS.toMillis(5), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                playProcess.destroyForcibly();
            }
            playProcess = null;
        }
    }

    private String buildClasspath() throws MojoExecutionException {
        try {
            List<String> elements = new ArrayList<>();
            elements.add(project.getBuild().getOutputDirectory());
            elements.addAll(project.getRuntimeClasspathElements());
            return String.join(File.pathSeparator, elements);
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to build classpath: " + e.getMessage(), e);
        }
    }

    private String getJavaExecutable() {
        String javaHome = System.getProperty("java.home");
        return javaHome + File.separator + "bin" + File.separator + "java";
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
            Path app = basedir.toPath().resolve("app");
            Path conf = basedir.toPath().resolve("conf");
            Path publicDir = basedir.toPath().resolve("public");

            if (Files.isDirectory(app)) paths.add(app);
            if (Files.isDirectory(conf)) paths.add(conf);
            if (Files.isDirectory(publicDir)) paths.add(publicDir);
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
