package io.github.konangelop.play.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Optional;

/**
 * Stops a Play Framework application that was started with the {@code start} goal.
 * Reads the PID from the PID file and terminates the process.
 */
@Mojo(name = "stop", threadSafe = true)
public class StopMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * File containing the server process PID.
     */
    @Parameter(defaultValue = "${project.build.directory}/play.pid", property = "play.pidFile")
    private File pidFile;

    /**
     * Timeout in seconds to wait for the server to stop gracefully before force-killing.
     */
    @Parameter(defaultValue = "10", property = "play.stopTimeout")
    private int stopTimeout;

    /**
     * Skip stopping the server.
     */
    @Parameter(defaultValue = "false", property = "play.stop.skip")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping Play server stop");
            return;
        }

        if (!pidFile.exists()) {
            getLog().warn("PID file not found at " + pidFile.getAbsolutePath() + ". Is the server running?");
            return;
        }

        long pid;
        try {
            String pidStr = new String(Files.readAllBytes(pidFile.toPath()), StandardCharsets.UTF_8).trim();
            pid = Long.parseLong(pidStr);
        } catch (IOException | NumberFormatException e) {
            throw new MojoExecutionException("Failed to read PID file " + pidFile.getAbsolutePath() + ": " + e.getMessage(), e);
        }

        getLog().info("Stopping Play server (PID " + pid + ")...");

        Optional<ProcessHandle> processOpt = ProcessHandle.of(pid);
        if (!processOpt.isPresent()) {
            getLog().info("Process " + pid + " is not running. Cleaning up PID file.");
            deletePidFile();
            return;
        }

        ProcessHandle process = processOpt.get();

        // Send graceful termination signal
        process.destroy();

        // Wait for the process to exit
        long deadline = System.currentTimeMillis() + (stopTimeout * 1000L);
        while (process.isAlive() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // Force kill if still alive
        if (process.isAlive()) {
            getLog().warn("Server did not stop gracefully within " + stopTimeout + " seconds, force-killing...");
            process.destroyForcibly();

            // Wait a bit more for force kill
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            if (process.isAlive()) {
                throw new MojoFailureException("Failed to stop Play server (PID " + pid + ")");
            }
        }

        deletePidFile();
        getLog().info("Play server stopped successfully");
    }

    private void deletePidFile() {
        try {
            Files.deleteIfExists(pidFile.toPath());
        } catch (IOException e) {
            getLog().warn("Failed to delete PID file: " + e.getMessage());
        }
    }
}
