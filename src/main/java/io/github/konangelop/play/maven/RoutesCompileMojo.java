package io.github.konangelop.play.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import play.routes.compiler.InjectedRoutesGenerator$;
import play.routes.compiler.RoutesCompilationError;
import play.routes.compiler.RoutesCompiler;
import play.routes.compiler.RoutesCompiler$;
import play.routes.compiler.RoutesGenerator;

import scala.collection.immutable.Seq;
import scala.jdk.javaapi.CollectionConverters;
import scala.util.Either;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Compiles Play Framework routes files into source code for the Play router.
 * Generated sources are added to the compile source roots so that the
 * {@code compile} goal can compile them alongside Java application code.
 */
@Mojo(name = "routes-compile", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public class RoutesCompileMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * Directory containing routes files. Scans for files named "routes" or "*.routes".
     */
    @Parameter(defaultValue = "${basedir}/conf", property = "play.routesDirectory")
    private File routesDirectory;

    /**
     * Output directory for generated Scala sources.
     */
    @Parameter(defaultValue = "${project.build.directory}/generated-sources/play-routes", property = "play.routesOutputDirectory")
    private File outputDirectory;

    /**
     * Additional imports to add to generated routes files.
     */
    @Parameter(property = "play.routesAdditionalImports")
    private List<String> additionalImports;

    /**
     * Whether to generate the forwards router.
     */
    @Parameter(defaultValue = "true", property = "play.forwardsRouter")
    private boolean forwardsRouter;

    /**
     * Whether to generate the reverse router.
     */
    @Parameter(defaultValue = "true", property = "play.reverseRouter")
    private boolean reverseRouter;

    /**
     * Whether to namespace the reverse router.
     */
    @Parameter(defaultValue = "false", property = "play.namespaceReverseRouter")
    private boolean namespaceReverseRouter;

    /**
     * Skip routes compilation.
     */
    @Parameter(defaultValue = "false", property = "play.routes.skip")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping routes compilation");
            return;
        }

        if (!routesDirectory.exists() || !routesDirectory.isDirectory()) {
            getLog().info("No routes directory found at " + routesDirectory.getAbsolutePath() + ", skipping");
            return;
        }

        List<File> routesFiles = findRoutesFiles(routesDirectory);
        if (routesFiles.isEmpty()) {
            getLog().info("No routes files found in " + routesDirectory.getAbsolutePath());
            return;
        }

        outputDirectory.mkdirs();
        project.addCompileSourceRoot(outputDirectory.getAbsolutePath());

        RoutesGenerator generator = InjectedRoutesGenerator$.MODULE$;
        List<String> imports = additionalImports != null ? additionalImports : new ArrayList<>();
        Seq<String> scalaImports = CollectionConverters.asScala(imports).toList().toSeq();

        int compiledCount = 0;
        int upToDateCount = 0;
        for (File routesFile : routesFiles) {
            if (isUpToDate(routesFile)) {
                getLog().debug("Routes file up-to-date: " + routesFile.getAbsolutePath());
                upToDateCount++;
                continue;
            }

            getLog().info("Compiling routes file: " + routesFile.getAbsolutePath());

            RoutesCompiler.RoutesCompilerTask task = new RoutesCompiler.RoutesCompilerTask(
                    routesFile,
                    scalaImports,
                    forwardsRouter,
                    reverseRouter,
                    namespaceReverseRouter
            );

            Either<scala.collection.immutable.Seq<RoutesCompilationError>, scala.collection.immutable.Seq<File>> result =
                    RoutesCompiler$.MODULE$.compile(task, generator, outputDirectory);

            if (result.isLeft()) {
                scala.collection.immutable.Seq<RoutesCompilationError> errors = result.left().get();
                StringBuilder sb = new StringBuilder("Routes compilation failed:\n");
                scala.collection.Iterator<RoutesCompilationError> it = errors.iterator();
                while (it.hasNext()) {
                    RoutesCompilationError error = it.next();
                    sb.append("  ").append(error.source().getAbsolutePath());
                    sb.append(":").append(error.line().<Integer>getOrElse(() -> 0));
                    sb.append(": ").append(error.message()).append("\n");
                }
                throw new MojoFailureException(sb.toString());
            }

            scala.collection.immutable.Seq<File> generated = result.right().get();
            compiledCount += generated.size();
            touchMarker(routesFile);
        }

        if (compiledCount > 0) {
            getLog().info("Compiled " + compiledCount + " routes source file(s)" +
                    (upToDateCount > 0 ? ", " + upToDateCount + " route(s) up-to-date" : ""));
        } else {
            getLog().info("All " + routesFiles.size() + " routes file(s) are up-to-date");
        }
    }

    /**
     * Checks if the generated output for a routes file is up-to-date by comparing
     * the source file's last-modified time against a marker file written after
     * successful compilation.
     */
    private boolean isUpToDate(File routesFile) {
        File marker = markerFile(routesFile);
        return marker.exists() && marker.lastModified() >= routesFile.lastModified();
    }

    private void touchMarker(File routesFile) {
        File marker = markerFile(routesFile);
        marker.getParentFile().mkdirs();
        try {
            Files.writeString(marker.toPath(), String.valueOf(routesFile.lastModified()));
        } catch (IOException e) {
            getLog().debug("Failed to write routes cache marker: " + e.getMessage());
        }
    }

    private File markerFile(File routesFile) {
        return new File(outputDirectory, ".cache/" + routesFile.getName() + ".marker");
    }

    private List<File> findRoutesFiles(File directory) {
        List<File> result = new ArrayList<>();
        File[] files = directory.listFiles();
        if (files == null) return result;

        for (File file : files) {
            if (file.isFile() && (file.getName().equals("routes") || file.getName().endsWith(".routes"))) {
                result.add(file);
            }
        }
        return result;
    }
}
