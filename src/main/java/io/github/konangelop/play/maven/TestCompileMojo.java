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
 * Compiles mixed Java and Scala test source code using the Zinc incremental compiler.
 * This is the test counterpart of the {@code compile} goal — it compiles test sources
 * against the main classes and test dependencies.
 */
@Mojo(name = "test-compile", defaultPhase = LifecyclePhase.TEST_COMPILE,
        requiresDependencyResolution = ResolutionScope.TEST, threadSafe = true)
public class TestCompileMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${plugin.artifacts}", readonly = true, required = true)
    private List<Artifact> pluginArtifacts;

    /**
     * Output directory for compiled test classes.
     */
    @Parameter(defaultValue = "${project.build.testOutputDirectory}", property = "play.testCompile.outputDirectory")
    private File outputDirectory;

    /**
     * Zinc analysis cache file for incremental test compilation.
     */
    @Parameter(defaultValue = "${project.build.directory}/zinc-test-analysis", property = "play.testCompile.analysisCache")
    private File analysisCacheFile;

    /**
     * Additional compiler options passed to scalac.
     */
    @Parameter(property = "play.testCompile.scalacOptions")
    private List<String> scalacOptions;

    /**
     * Additional compiler options passed to javac.
     */
    @Parameter(property = "play.testCompile.javacOptions")
    private List<String> javacOptions;

    /**
     * Skip test compilation.
     */
    @Parameter(defaultValue = "false", property = "play.testCompile.skip")
    private boolean skip;

    /**
     * Skip test compilation when tests are skipped.
     */
    @Parameter(defaultValue = "false", property = "maven.test.skip")
    private boolean mavenTestSkip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip || mavenTestSkip) {
            getLog().info("Skipping test compilation");
            return;
        }

        outputDirectory.mkdirs();

        List<File> sourceFiles = collectTestSourceFiles();
        if (sourceFiles.isEmpty()) {
            getLog().info("No test source files to compile");
            return;
        }

        getLog().info("Compiling " + sourceFiles.size() + " test source file(s) with Zinc incremental compiler");

        List<File> classpathFiles = buildTestClasspath();

        new ZincCompilerSupport(getLog(), project.getArtifacts(), pluginArtifacts).compile(
                sourceFiles, classpathFiles, outputDirectory, analysisCacheFile,
                scalacOptions, javacOptions);

        getLog().info("Test compilation complete");
    }

    private List<File> collectTestSourceFiles() {
        List<File> sources = new ArrayList<>();
        for (String root : project.getTestCompileSourceRoots()) {
            File rootDir = new File(root);
            if (rootDir.exists()) {
                ZincCompilerSupport.collectFiles(rootDir, sources, getLog(), ".java", ".scala");
            }
        }
        return sources;
    }

    private List<File> buildTestClasspath() {
        List<File> classpath = new ArrayList<>();
        try {
            for (String element : project.getTestClasspathElements()) {
                classpath.add(new File(element));
            }
        } catch (Exception e) {
            getLog().warn("Error building test classpath: " + e.getMessage());
        }
        return classpath;
    }
}
