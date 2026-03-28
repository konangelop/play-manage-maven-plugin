package io.github.konangelop.play.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;

import org.codehaus.plexus.archiver.zip.ZipArchiver;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;

/**
 * Creates a standalone distribution archive (zip) for deploying the Play application.
 * The archive contains lib/ (JARs), bin/ (start scripts), and conf/ (configuration).
 */
@Mojo(name = "dist", defaultPhase = LifecyclePhase.PACKAGE,
        requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
public class DistMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Component
    private MavenProjectHelper projectHelper;

    /**
     * Output directory for the distribution archive.
     */
    @Parameter(defaultValue = "${project.build.directory}", property = "play.distOutputDirectory")
    private File distOutputDirectory;

    /**
     * Base name of the distribution archive.
     */
    @Parameter(defaultValue = "${project.build.finalName}", property = "play.distArchiveName")
    private String distArchiveName;

    /**
     * Top-level directory name inside the zip archive.
     */
    @Parameter(defaultValue = "${project.build.finalName}", property = "play.distTopLevelDirectory")
    private String topLevelDirectory;

    /**
     * Main class for the start scripts.
     */
    @Parameter(defaultValue = "play.core.server.ProdServerStart", property = "play.distMainClass")
    private String mainClass;

    /**
     * Additional JVM arguments for the start scripts.
     */
    @Parameter(property = "play.distJvmArgs")
    private String jvmArgs;

    /**
     * Production settings (system properties) for the start scripts.
     * Format: "-Dkey1=value1 -Dkey2=value2"
     */
    @Parameter(property = "play.distProdSettings")
    private String prodSettings;

    /**
     * Classifier for the distribution artifact.
     */
    @Parameter(defaultValue = "dist", property = "play.distClassifier")
    private String distClassifier;

    /**
     * Whether to attach the distribution as a Maven artifact.
     */
    @Parameter(defaultValue = "true", property = "play.distAttach")
    private boolean distAttach;

    /**
     * Skip distribution creation.
     */
    @Parameter(defaultValue = "false", property = "play.dist.skip")
    private boolean skip;

    /**
     * Configuration directory to include in the distribution.
     */
    @Parameter(defaultValue = "${basedir}/conf", property = "play.distConfDirectory")
    private File confDirectory;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping distribution creation");
            return;
        }

        distOutputDirectory.mkdirs();

        File distZip = new File(distOutputDirectory, distArchiveName + "-" + distClassifier + ".zip");

        try {
            ZipArchiver archiver = new ZipArchiver();
            archiver.setDestFile(distZip);

            // Add the main project JAR
            File mainJar = project.getArtifact().getFile();
            if (mainJar != null && mainJar.exists()) {
                archiver.addFile(mainJar, topLevelDirectory + "/lib/" + mainJar.getName());
            }

            // Add runtime dependencies
            Set<Artifact> artifacts = project.getArtifacts();
            for (Artifact artifact : artifacts) {
                if (Artifact.SCOPE_RUNTIME.equals(artifact.getScope()) ||
                        Artifact.SCOPE_COMPILE.equals(artifact.getScope())) {
                    File file = artifact.getFile();
                    if (file != null && file.exists()) {
                        archiver.addFile(file, topLevelDirectory + "/lib/" + file.getName());
                    }
                }
            }

            // Add conf directory
            if (confDirectory.exists() && confDirectory.isDirectory()) {
                archiver.addDirectory(confDirectory, topLevelDirectory + "/conf/");
            }

            // Generate and add start scripts
            File scriptsDir = new File(distOutputDirectory, "dist-scripts");
            scriptsDir.mkdirs();

            File unixScript = generateUnixScript(scriptsDir);
            archiver.addFile(unixScript, topLevelDirectory + "/bin/start", 0755);

            File windowsScript = generateWindowsScript(scriptsDir);
            archiver.addFile(windowsScript, topLevelDirectory + "/bin/start.bat");

            archiver.createArchive();
            getLog().info("Distribution archive created: " + distZip.getAbsolutePath());

            // Attach as Maven artifact
            if (distAttach) {
                projectHelper.attachArtifact(project, "zip", distClassifier, distZip);
            }

        } catch (Exception e) {
            throw new MojoExecutionException("Failed to create distribution: " + e.getMessage(), e);
        }
    }

    private File generateUnixScript(File scriptsDir) throws IOException {
        String jvmArgsStr = jvmArgs != null ? jvmArgs : "";
        String prodSettingsStr = prodSettings != null ? prodSettings : "";

        StringBuilder sb = new StringBuilder();
        sb.append("#!/bin/bash\n");
        sb.append("\n");
        sb.append("# Play Framework Application Start Script\n");
        sb.append("# Generated by play-manage-maven-plugin\n");
        sb.append("\n");
        sb.append("DIR=\"$(cd \"$(dirname \"$0\")/..\" && pwd)\"\n");
        sb.append("\n");
        sb.append("CLASSPATH=\"$DIR/lib/*\"\n");
        sb.append("\n");
        sb.append("exec java \\\n");
        if (!jvmArgsStr.isEmpty()) {
            sb.append("  ").append(jvmArgsStr).append(" \\\n");
        }
        if (!prodSettingsStr.isEmpty()) {
            sb.append("  ").append(prodSettingsStr).append(" \\\n");
        }
        sb.append("  -cp \"$CLASSPATH\" \\\n");
        sb.append("  -Dplay.server.dir=\"$DIR\" \\\n");
        sb.append("  ").append(mainClass).append(" \\\n");
        sb.append("  \"$@\"\n");

        File script = new File(scriptsDir, "start");
        Files.write(script.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        return script;
    }

    private File generateWindowsScript(File scriptsDir) throws IOException {
        String jvmArgsStr = jvmArgs != null ? jvmArgs : "";
        String prodSettingsStr = prodSettings != null ? prodSettings : "";

        StringBuilder sb = new StringBuilder();
        sb.append("@echo off\r\n");
        sb.append("\r\n");
        sb.append("rem Play Framework Application Start Script\r\n");
        sb.append("rem Generated by play-manage-maven-plugin\r\n");
        sb.append("\r\n");
        sb.append("set DIR=%~dp0\\..\r\n");
        sb.append("set CLASSPATH=%DIR%\\lib\\*\r\n");
        sb.append("\r\n");
        sb.append("java");
        if (!jvmArgsStr.isEmpty()) {
            sb.append(" ").append(jvmArgsStr);
        }
        if (!prodSettingsStr.isEmpty()) {
            sb.append(" ").append(prodSettingsStr);
        }
        sb.append(" -cp \"%CLASSPATH%\"");
        sb.append(" -Dplay.server.dir=\"%DIR%\"");
        sb.append(" ").append(mainClass);
        sb.append(" %*\r\n");

        File script = new File(scriptsDir, "start.bat");
        Files.write(script.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        return script;
    }
}
