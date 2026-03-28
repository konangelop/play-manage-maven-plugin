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

        List<File> sourceFiles = collectSourceFiles();
        if (sourceFiles.isEmpty()) {
            getLog().info("No source files to compile");
            return;
        }

        getLog().info("Compiling " + sourceFiles.size() + " source file(s) with Zinc incremental compiler");

        try {
            List<File> classpathFiles = buildClasspath();

            // Find Scala JARs from plugin classpath or local repo
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

            // Create Scala instance
            ScalaInstance scalaInstance = new ScalaInstance(
                    "2.13.15",
                    new URLClassLoader(toURLs(allScalaJars), ClassLoader.getSystemClassLoader()),
                    new URLClassLoader(new URL[]{scalaLibraryJar.toURI().toURL()}, ClassLoader.getSystemClassLoader()),
                    allScalaJarsList.toArray(new File[0]),
                    scalaCompilerJar,
                    allScalaJars,
                    scala.Option.<String>empty()
            );

            // Create the incremental compiler
            IncrementalCompilerImpl compiler = new IncrementalCompilerImpl();

            // Create compilers
            ScalaCompiler scalaCompiler = ZincUtil.scalaCompiler(scalaInstance, scalaCompilerJar);
            Compilers compilers = compiler.compilers(scalaInstance, ClasspathOptionsUtil.boot(), scala.Option.empty(), scalaCompiler);

            // Convert source files to VirtualFile array
            VirtualFile[] sources = sourceFiles.stream()
                    .map(f -> (VirtualFile) new sbt.internal.inc.PlainVirtualFile(f.toPath()))
                    .toArray(VirtualFile[]::new);

            // Convert classpath to VirtualFile array
            VirtualFile[] classpathVF = classpathFiles.stream()
                    .map(f -> (VirtualFile) new sbt.internal.inc.PlainVirtualFile(f.toPath()))
                    .toArray(VirtualFile[]::new);

            // Build compiler options
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

            // Load previous analysis for incremental compilation
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

            // Per-classpath entry lookup
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

            // Reporter for compiler messages
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

            // Setup
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

            // Build inputs
            Inputs inputs = Inputs.of(compilers, options, setup, previousResult);

            // Logger
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

            // Compile!
            CompileResult result = compiler.compile(inputs, logger);

            // Store analysis for next incremental compile
            analysisStore.set(AnalysisContents.create(result.analysis(), result.setup()));

            getLog().info("Compilation complete");

        } catch (MojoExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Compilation failed: " + e.getMessage(), e);
        }
    }

    private List<File> collectSourceFiles() {
        List<File> sources = new ArrayList<>();
        for (String root : project.getCompileSourceRoots()) {
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

    private List<File> buildClasspath() throws Exception {
        List<File> classpath = new ArrayList<>();
        for (String element : project.getCompileClasspathElements()) {
            classpath.add(new File(element));
        }
        return classpath;
    }

    private File findScalaJar(String artifactName) {
        String m2Repo = System.getProperty("user.home") + "/.m2/repository";
        File jar = new File(m2Repo, "org/scala-lang/" + artifactName + "/2.13.15/" + artifactName + "-2.13.15.jar");
        if (jar.exists()) return jar;

        // Try to find on plugin classloader
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
