package io.github.konangelop.play.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Starts a Play Framework application in production mode as a background process.
 * The server process PID is written to a file so it can be stopped later with the
 * {@code stop} goal.
 */
@Mojo(name = "start", requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
public class StartMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * HTTP port for the Play server.
     */
    @Parameter(defaultValue = "9000", property = "play.httpPort")
    private int httpPort;

    /**
     * HTTPS port for the Play server. Set to -1 to disable.
     */
    @Parameter(defaultValue = "-1", property = "play.httpsPort")
    private int httpsPort;

    /**
     * Address to bind the server to.
     */
    @Parameter(defaultValue = "0.0.0.0", property = "play.httpAddress")
    private String httpAddress;

    /**
     * Main class for the Play application.
     */
    @Parameter(defaultValue = "play.core.server.ProdServerStart", property = "play.mainClass")
    private String mainClass;

    /**
     * Additional JVM arguments for the server process.
     */
    @Parameter(property = "play.serverJvmArgs")
    private List<String> serverJvmArgs;

    /**
     * Production settings passed as system properties (e.g. {@code -Dkey=value}).
     */
    @Parameter(property = "play.prodSettings")
    private List<String> prodSettings;

    /**
     * Timeout in seconds to wait for the server to start.
     */
    @Parameter(defaultValue = "30", property = "play.startTimeout")
    private int startTimeout;

    /**
     * URL path to poll to confirm the server has started (e.g. "/").
     * Set to empty string to skip the health check.
     */
    @Parameter(defaultValue = "/", property = "play.startCheckUrl")
    private String startCheckUrl;

    /**
     * File to write the server process PID to.
     */
    @Parameter(defaultValue = "${project.build.directory}/play.pid", property = "play.pidFile")
    private File pidFile;

    /**
     * File to redirect server output to.
     */
    @Parameter(defaultValue = "${project.build.directory}/play.log", property = "play.logFile")
    private File logFile;

    /**
     * Skip starting the server.
     */
    @Parameter(defaultValue = "false", property = "play.start.skip")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping Play server start");
            return;
        }

        // Check if already running
        if (pidFile.exists()) {
            try {
                long existingPid = Long.parseLong(
                        new String(Files.readAllBytes(pidFile.toPath()), StandardCharsets.UTF_8).trim());
                if (ProcessHandle.of(existingPid).isPresent()) {
                    throw new MojoFailureException(
                            "Play server is already running (PID " + existingPid + "). Stop it first with the stop goal.");
                }
                // Stale PID file, remove it
                Files.delete(pidFile.toPath());
            } catch (NumberFormatException | IOException e) {
                // Ignore, will be overwritten
            }
        }

        getLog().info("Starting Play application in production mode on port " + httpPort);

        try {
            String classpath = buildClasspath();
            List<String> command = new ArrayList<>();
            command.add(getJavaExecutable());

            if (serverJvmArgs != null) {
                command.addAll(serverJvmArgs);
            }

            command.add("-Dplay.server.http.port=" + httpPort);
            command.add("-Dplay.server.http.address=" + httpAddress);
            if (httpsPort > 0) {
                command.add("-Dplay.server.https.port=" + httpsPort);
            }

            if (prodSettings != null) {
                command.addAll(prodSettings);
            }

            command.add("-cp");
            command.add(classpath);
            command.add(mainClass);

            // Ensure log file parent directory exists
            logFile.getParentFile().mkdirs();

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(project.getBasedir());
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
            pb.redirectErrorStream(true);

            Process process = pb.start();
            long pid = process.pid();

            // Write PID file
            pidFile.getParentFile().mkdirs();
            Files.write(pidFile.toPath(), String.valueOf(pid).getBytes(StandardCharsets.UTF_8));
            getLog().info("Play server started with PID " + pid);
            getLog().info("PID file: " + pidFile.getAbsolutePath());
            getLog().info("Log file: " + logFile.getAbsolutePath());

            // Health check
            if (startCheckUrl != null && !startCheckUrl.isEmpty()) {
                waitForServer(pid);
            }

        } catch (MojoFailureException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to start Play application: " + e.getMessage(), e);
        }
    }

    private void waitForServer(long pid) throws MojoFailureException {
        String checkUrl = "http://" + httpAddress + ":" + httpPort + startCheckUrl;
        // Use localhost if address is 0.0.0.0
        if ("0.0.0.0".equals(httpAddress)) {
            checkUrl = "http://localhost:" + httpPort + startCheckUrl;
        }

        getLog().info("Waiting for server to start (checking " + checkUrl + ")...");

        long deadline = System.currentTimeMillis() + (startTimeout * 1000L);
        while (System.currentTimeMillis() < deadline) {
            // Check if process is still alive
            if (!ProcessHandle.of(pid).isPresent()) {
                throw new MojoFailureException(
                        "Play server process exited prematurely. Check " + logFile.getAbsolutePath() + " for details.");
            }

            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(checkUrl).openConnection();
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                conn.setRequestMethod("GET");
                int responseCode = conn.getResponseCode();
                conn.disconnect();

                if (responseCode >= 200 && responseCode < 500) {
                    getLog().info("Server is ready (HTTP " + responseCode + ")");
                    return;
                }
            } catch (IOException e) {
                // Server not ready yet
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        getLog().warn("Server health check timed out after " + startTimeout + " seconds. "
                + "The server may still be starting. Check " + logFile.getAbsolutePath());
    }

    private String buildClasspath() throws Exception {
        List<String> elements = new ArrayList<>();
        elements.add(project.getBuild().getOutputDirectory());
        elements.addAll(project.getRuntimeClasspathElements());
        return String.join(File.pathSeparator, elements);
    }

    private String getJavaExecutable() {
        String javaHome = System.getProperty("java.home");
        return javaHome + File.separator + "bin" + File.separator + "java";
    }
}
