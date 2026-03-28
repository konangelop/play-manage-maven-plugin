package io.github.konangelop.play.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Compiles mixed Java and Scala source code using the Zinc incremental compiler.
 * This handles compilation of Java application code alongside generated Scala code
 * from routes and Twirl template compilation. Only changed files are recompiled
 * thanks to Zinc's incremental analysis cache.
 */
@Mojo(name = "compile", defaultPhase = LifecyclePhase.COMPILE,
        requiresDependencyResolution = ResolutionScope.COMPILE, threadSafe = true)
public class CompileMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${plugin.artifacts}", readonly = true, required = true)
    private List<Artifact> pluginArtifacts;

    /**
     * Output directory for compiled classes.
     */
    @Parameter(defaultValue = "${project.build.outputDirectory}", property = "play.compile.outputDirectory")
    private File outputDirectory;

    /**
     * Zinc analysis cache file for incremental compilation.
     */
    @Parameter(defaultValue = "${project.build.directory}/zinc-analysis", property = "play.compile.analysisCache")
    private File analysisCacheFile;

    /**
     * Additional compiler options passed to scalac.
     */
    @Parameter(property = "play.compile.scalacOptions")
    private List<String> scalacOptions;

    /**
     * Additional compiler options passed to javac.
     */
    @Parameter(property = "play.compile.javacOptions")
    private List<String> javacOptions;

    /**
     * Skip compilation.
     */
    @Parameter(defaultValue = "false", property = "play.compile.skip")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping compilation");
            return;
        }

        outputDirectory.mkdirs();

        List<String> sourceRoots = project.getCompileSourceRoots();
        getLog().info("Compile source roots: " + sourceRoots);

        List<File> sourceFiles = collectSourceFiles(sourceRoots);
        if (sourceFiles.isEmpty()) {
            getLog().info("No source files to compile");
            return;
        }

        getLog().info("Compiling " + sourceFiles.size() + " source file(s) with Zinc incremental compiler");

        List<File> classpathFiles = buildClasspath();
        getLog().info("Compile classpath: " + classpathFiles.size() + " entries");

        new ZincCompilerSupport(getLog(), project.getArtifacts(), pluginArtifacts).compile(
                sourceFiles, classpathFiles, outputDirectory, analysisCacheFile,
                scalacOptions, javacOptions);

        getLog().info("Compilation complete");
    }

    private List<File> collectSourceFiles(List<String> sourceRoots) {
        List<File> sources = new ArrayList<>();
        for (String root : sourceRoots) {
            File rootDir = new File(root);
            if (rootDir.exists()) {
                int before = sources.size();
                ZincCompilerSupport.collectFiles(rootDir, sources, getLog(), ".java", ".scala");
                getLog().info("  " + root + " -> " + (sources.size() - before) + " file(s)");
            } else {
                getLog().info("  " + root + " (does not exist, skipped)");
            }
        }
        return sources;
    }

    private List<File> buildClasspath() throws MojoExecutionException {
        List<File> classpath = new ArrayList<>();
        try {
            for (String element : project.getCompileClasspathElements()) {
                classpath.add(new File(element));
            }
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to resolve compile classpath: " + e.getMessage(), e);
        }
        return classpath;
    }
}
