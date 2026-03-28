package io.github.konangelop.play.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Shared Zinc incremental compiler infrastructure used by both
 * {@link CompileMojo} and {@link TestCompileMojo}.
 */
class ZincCompilerSupport {

    private final Log log;
    private final Set<Artifact> projectArtifacts;
    private final Collection<Artifact> pluginArtifacts;

    ZincCompilerSupport(Log log, Set<Artifact> projectArtifacts, Collection<Artifact> pluginArtifacts) {
        this.log = log;
        this.projectArtifacts = projectArtifacts;
        this.pluginArtifacts = pluginArtifacts;
    }

    /**
     * Compiles sources using the Zinc incremental compiler.
     */
    void compile(List<File> sourceFiles, List<File> classpathFiles,
                 File outputDirectory, File analysisCacheFile,
                 List<String> scalacOptions, List<String> javacOptions)
            throws MojoExecutionException {
        try {
            File scalaLibraryJar = findScalaJar("scala-library");
            File scalaCompilerJar = findScalaJar("scala-compiler");
            File scalaReflectJar = findScalaJar("scala-reflect");

            // Prefer scala2-sbt-bridge (published by the Scala team, matches exact compiler version),
            // fall back to zinc's compiler-bridge_2.13 for compatibility
            File compilerBridgeJar = findArtifactJar("org.scala-lang", "scala2-sbt-bridge");
            if (compilerBridgeJar == null) {
                compilerBridgeJar = findArtifactJar("org.scala-sbt", "compiler-bridge_2.13");
            }

            if (scalaLibraryJar == null || scalaCompilerJar == null) {
                throw new MojoExecutionException(
                        "Could not find scala-library or scala-compiler JARs. "
                        + "Ensure org.scala-lang:scala-library and org.scala-lang:scala-compiler "
                        + "are on the project classpath.");
            }
            if (compilerBridgeJar == null) {
                throw new MojoExecutionException(
                        "Could not find compiler-bridge JAR. "
                        + "Ensure org.scala-sbt:compiler-bridge_2.13 is available.");
            }

            List<File> allScalaJarsList = new ArrayList<>();
            allScalaJarsList.add(scalaCompilerJar);
            allScalaJarsList.add(scalaLibraryJar);
            if (scalaReflectJar != null) {
                allScalaJarsList.add(scalaReflectJar);
            }
            File[] allScalaJars = allScalaJarsList.toArray(new File[0]);

            // Derive the version from the resolved scala-library artifact
            String scalaVersion = findScalaVersion();
            if (scalaVersion == null) {
                scalaVersion = "2.13.17"; // fallback
            }

            ScalaInstance scalaInstance = new ScalaInstance(
                    scalaVersion,
                    new URLClassLoader(toURLs(allScalaJars), ClassLoader.getSystemClassLoader()),
                    new URLClassLoader(new URL[]{scalaLibraryJar.toURI().toURL()}, ClassLoader.getSystemClassLoader()),
                    allScalaJars,
                    scalaCompilerJar,
                    allScalaJars,
                    scala.Option.<String>empty()
            );

            IncrementalCompilerImpl compiler = new IncrementalCompilerImpl();

            ScalaCompiler scalaComp = ZincUtil.scalaCompiler(scalaInstance, compilerBridgeJar);
            Compilers compilers = compiler.compilers(scalaInstance, ClasspathOptionsUtil.boot(), scala.Option.empty(), scalaComp);

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

            Map<Path, DefinesClass> definesClassCache = new HashMap<>();
            PerClasspathEntryLookup lookup = new PerClasspathEntryLookup() {
                @Override
                public Optional<CompileAnalysis> analysis(VirtualFile classpathEntry) {
                    return Optional.empty();
                }

                @Override
                public DefinesClass definesClass(VirtualFile classpathEntry) {
                    Path path = ((sbt.internal.inc.PlainVirtualFile) classpathEntry).id();
                    return definesClassCache.computeIfAbsent(path, ZincCompilerSupport::createDefinesClass);
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
                            log.error(problem.message());
                            break;
                        case Warn:
                            hasWarnings = true;
                            log.warn(problem.message());
                            break;
                        default:
                            log.info(problem.message());
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
                public void error(Supplier<String> msg) { log.error(msg.get()); }
                @Override
                public void warn(Supplier<String> msg) { log.warn(msg.get()); }
                @Override
                public void info(Supplier<String> msg) { log.info(msg.get()); }
                @Override
                public void debug(Supplier<String> msg) { log.debug(msg.get()); }
                @Override
                public void trace(Supplier<Throwable> exception) {
                    log.debug(exception.get());
                }
            };

            CompileResult result = compiler.compile(inputs, logger);

            analysisStore.set(AnalysisContents.create(result.analysis(), result.setup()));

        } catch (MojoExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Compilation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Collects files matching the given extensions from a directory tree.
     */
    static void collectFiles(File directory, List<File> result, Log log, String... extensions) {
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
            log.warn("Error scanning directory " + directory + ": " + e.getMessage());
        }
    }

    /**
     * Finds an artifact JAR by groupId and artifactId, checking project
     * artifacts first then falling back to plugin artifacts.
     */
    private File findArtifactJar(String groupId, String artifactId) {
        if (projectArtifacts != null) {
            for (Artifact artifact : projectArtifacts) {
                if (groupId.equals(artifact.getGroupId())
                        && artifactId.equals(artifact.getArtifactId())
                        && artifact.getFile() != null) {
                    return artifact.getFile();
                }
            }
        }
        if (pluginArtifacts != null) {
            for (Artifact artifact : pluginArtifacts) {
                if (groupId.equals(artifact.getGroupId())
                        && artifactId.equals(artifact.getArtifactId())
                        && artifact.getFile() != null) {
                    return artifact.getFile();
                }
            }
        }
        return null;
    }

    /**
     * Finds a Scala JAR, checking plugin artifacts first to ensure version
     * consistency with the compiler bridge, then falling back to project artifacts.
     */
    private File findScalaJar(String artifactName) {
        // Check plugin artifacts first — the ScalaInstance must use JARs matching
        // the compiler bridge version, which comes from the plugin's dependencies
        if (pluginArtifacts != null) {
            for (Artifact artifact : pluginArtifacts) {
                if ("org.scala-lang".equals(artifact.getGroupId())
                        && artifactName.equals(artifact.getArtifactId())
                        && artifact.getFile() != null) {
                    return artifact.getFile();
                }
            }
        }
        // Fall back to project artifacts
        if (projectArtifacts != null) {
            for (Artifact artifact : projectArtifacts) {
                if ("org.scala-lang".equals(artifact.getGroupId())
                        && artifactName.equals(artifact.getArtifactId())
                        && artifact.getFile() != null) {
                    return artifact.getFile();
                }
            }
        }
        return null;
    }

    /**
     * Derives the Scala version from the plugin's scala-library artifact.
     */
    private String findScalaVersion() {
        if (pluginArtifacts != null) {
            for (Artifact artifact : pluginArtifacts) {
                if ("org.scala-lang".equals(artifact.getGroupId())
                        && "scala-library".equals(artifact.getArtifactId())) {
                    return artifact.getVersion();
                }
            }
        }
        if (projectArtifacts != null) {
            for (Artifact artifact : projectArtifacts) {
                if ("org.scala-lang".equals(artifact.getGroupId())
                        && "scala-library".equals(artifact.getArtifactId())) {
                    return artifact.getVersion();
                }
            }
        }
        return null;
    }

    /**
     * Creates a DefinesClass for a classpath entry. For directories, checks if
     * the .class file exists on disk. For JARs, loads the entry index into a
     * HashSet for O(1) lookups. This lets Zinc distinguish external dependencies
     * (stable) from source-compiled classes (may change), preventing unnecessary
     * recompilation rounds.
     */
    private static DefinesClass createDefinesClass(Path path) {
        File file = path.toFile();
        if (file.isDirectory()) {
            return className -> {
                String relativePath = className.replace('.', File.separatorChar) + ".class";
                return new File(file, relativePath).isFile();
            };
        } else if (file.isFile() && file.getName().endsWith(".jar")) {
            Set<String> classNames = new HashSet<>();
            try (JarFile jar = new JarFile(file)) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (name.endsWith(".class") && !name.equals("module-info.class")) {
                        classNames.add(name.substring(0, name.length() - 6).replace('/', '.'));
                    }
                }
            } catch (IOException e) {
                // JAR can't be read — treat as empty
            }
            return classNames::contains;
        }
        return className -> false;
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
