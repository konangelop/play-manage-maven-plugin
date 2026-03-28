package io.github.konangelop.play.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import sbt.internal.inc.IncrementalCompilerImpl;
import sbt.internal.inc.ScalaInstance;
import sbt.internal.inc.ZincUtil;
import sbt.internal.inc.FileAnalysisStore;
import xsbti.compile.AnalysisContents;

import xsbti.VirtualFile;
import xsbti.compile.AnalysisStore;
import xsbti.compile.ClasspathOptionsUtil;
import xsbti.compile.CompileAnalysis;
import xsbti.compile.CompileOptions;
import xsbti.compile.CompileResult;
import xsbti.compile.Compilers;
import xsbti.compile.DefinesClass;
import xsbti.compile.IncOptions;
import xsbti.compile.Inputs;
import xsbti.compile.PerClasspathEntryLookup;
import xsbti.compile.PreviousResult;
import xsbti.compile.ScalaCompiler;
import xsbti.compile.Setup;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

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

        try {
            List<File> classpathFiles = buildTestClasspath();

            File scalaLibraryJar = findScalaJar("scala-library");
            File scalaCompilerJar = findScalaJar("scala-compiler");
            File scalaReflectJar = findScalaJar("scala-reflect");

            if (scalaLibraryJar == null || scalaCompilerJar == null) {
                throw new MojoExecutionException(
                        "Could not find scala-library or scala-compiler JARs");
            }

            List<File> allScalaJarsList = new ArrayList<>();
            allScalaJarsList.add(scalaCompilerJar);
            allScalaJarsList.add(scalaLibraryJar);
            if (scalaReflectJar != null) {
                allScalaJarsList.add(scalaReflectJar);
            }
            File[] allScalaJars = allScalaJarsList.toArray(new File[0]);

            ScalaInstance scalaInstance = new ScalaInstance(
                    "2.13.15",
                    new URLClassLoader(toURLs(allScalaJars), ClassLoader.getSystemClassLoader()),
                    new URLClassLoader(new URL[]{scalaLibraryJar.toURI().toURL()}, ClassLoader.getSystemClassLoader()),
                    allScalaJarsList.toArray(new File[0]),
                    scalaCompilerJar,
                    allScalaJars,
                    scala.Option.<String>empty()
            );

            IncrementalCompilerImpl compiler = new IncrementalCompilerImpl();

            ScalaCompiler scalaCompiler = ZincUtil.scalaCompiler(scalaInstance, scalaCompilerJar);
            Compilers compilers = compiler.compilers(scalaInstance, ClasspathOptionsUtil.boot(), scala.Option.empty(), scalaCompiler);

            VirtualFile[] sources = sourceFiles.stream()
                    .map(f -> (VirtualFile) new sbt.internal.inc.PlainVirtualFile(f.toPath()))
                    .toArray(VirtualFile[]::new);

            VirtualFile[] classpathVF = classpathFiles.stream()
                    .map(f -> (VirtualFile) new sbt.internal.inc.PlainVirtualFile(f.toPath()))
                    .toArray(VirtualFile[]::new);

            String[] scalacArgs = scalacOptions != null ?
                    scalacOptions.toArray(new String[0]) : new String[0];
            String[] javacArgs = javacOptions != null ?
                    javacOptions.toArray(new String[0]) : new String[0];

            CompileOptions options = CompileOptions.create()
                    .withSources(sources)
                    .withClasspath(classpathVF)
                    .withClassesDirectory(outputDirectory.toPath())
                    .withScalacOptions(scalacArgs)
                    .withJavacOptions(javacArgs);

            AnalysisStore analysisStore = FileAnalysisStore.binary(analysisCacheFile);
            Optional<AnalysisContents> previousContentsOpt = analysisStore.get();

            PreviousResult previousResult;
            if (previousContentsOpt.isPresent()) {
                AnalysisContents prev = previousContentsOpt.get();
                previousResult = PreviousResult.of(
                        Optional.of(prev.getAnalysis()),
                        Optional.of(prev.getMiniSetup())
                );
            } else {
                previousResult = PreviousResult.of(Optional.empty(), Optional.empty());
            }

            PerClasspathEntryLookup lookup = new PerClasspathEntryLookup() {
                @Override
                public Optional<CompileAnalysis> analysis(VirtualFile classpathEntry) {
                    return Optional.empty();
                }

                @Override
                public DefinesClass definesClass(VirtualFile classpathEntry) {
                    return name -> false;
                }
            };

            xsbti.Reporter reporter = new xsbti.Reporter() {
                private boolean hasErrors = false;
                private boolean hasWarnings = false;

                @Override
                public void reset() {
                    hasErrors = false;
                    hasWarnings = false;
                }

                @Override
                public boolean hasErrors() { return hasErrors; }

                @Override
                public boolean hasWarnings() { return hasWarnings; }

                @Override
                public void printSummary() {}

                @Override
                public xsbti.Problem[] problems() { return new xsbti.Problem[0]; }

                @Override
                public void log(xsbti.Problem problem) {
                    switch (problem.severity()) {
                        case Error:
                            hasErrors = true;
                            getLog().error(problem.message());
                            break;
                        case Warn:
                            hasWarnings = true;
                            getLog().warn(problem.message());
                            break;
                        default:
                            getLog().info(problem.message());
                    }
                }

                @Override
                public void comment(xsbti.Position pos, String msg) {}
            };

            Setup setup = Setup.of(
                    lookup,
                    false,
                    analysisCacheFile.toPath(),
                    new sbt.internal.inc.FreshCompilerCache(),
                    IncOptions.of(),
                    reporter,
                    Optional.empty(),
                    new xsbti.T2[0]
            );

            Inputs inputs = Inputs.of(compilers, options, setup, previousResult);

            xsbti.Logger logger = new xsbti.Logger() {
                @Override
                public void error(Supplier<String> msg) { getLog().error(msg.get()); }
                @Override
                public void warn(Supplier<String> msg) { getLog().warn(msg.get()); }
                @Override
                public void info(Supplier<String> msg) { getLog().info(msg.get()); }
                @Override
                public void debug(Supplier<String> msg) { getLog().debug(msg.get()); }
                @Override
                public void trace(Supplier<Throwable> exception) {
                    getLog().debug(exception.get());
                }
            };

            CompileResult result = compiler.compile(inputs, logger);

            analysisStore.set(AnalysisContents.create(result.analysis(), result.setup()));

            getLog().info("Test compilation complete");

        } catch (MojoExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Test compilation failed: " + e.getMessage(), e);
        }
    }

    private List<File> collectTestSourceFiles() {
        List<File> sources = new ArrayList<>();
        for (String root : project.getTestCompileSourceRoots()) {
            File rootDir = new File(root);
            if (rootDir.exists()) {
                collectFiles(rootDir, sources, ".java", ".scala");
            }
        }
        return sources;
    }

    private void collectFiles(File directory, List<File> result, String... extensions) {
        try {
            Files.walkFileTree(directory.toPath(), new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    for (String ext : extensions) {
                        if (name.endsWith(ext)) {
                            result.add(file.toFile());
                            break;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            getLog().warn("Error scanning directory " + directory + ": " + e.getMessage());
        }
    }

    private List<File> buildTestClasspath() throws Exception {
        List<File> classpath = new ArrayList<>();
        for (String element : project.getTestClasspathElements()) {
            classpath.add(new File(element));
        }
        return classpath;
    }

    private File findScalaJar(String artifactName) {
        String m2Repo = System.getProperty("user.home") + "/.m2/repository";
        File jar = new File(m2Repo, "org/scala-lang/" + artifactName + "/2.13.15/" + artifactName + "-2.13.15.jar");
        if (jar.exists()) return jar;

        ClassLoader cl = getClass().getClassLoader();
        if (cl instanceof URLClassLoader) {
            for (URL url : ((URLClassLoader) cl).getURLs()) {
                String path = url.getPath();
                String fileName = new File(path).getName();
                if (fileName.startsWith(artifactName + "-") && fileName.endsWith(".jar")) {
                    return new File(url.getPath());
                }
            }
        }
        return null;
    }

    private static URL[] toURLs(File[] files) {
        URL[] urls = new URL[files.length];
        for (int i = 0; i < files.length; i++) {
            try {
                urls[i] = files[i].toURI().toURL();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return urls;
    }
}
