package io.github.konangelop.play.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import play.twirl.compiler.TwirlCompiler;

import scala.Option;
import scala.collection.immutable.Seq;
import scala.io.Codec;
import scala.jdk.javaapi.CollectionConverters;

import java.io.File;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiles Play Twirl template files (.scala.html, .scala.txt, .scala.xml, .scala.js)
 * into source code. Generated sources are added to the compile source roots
 * so that the {@code compile} goal can compile them alongside Java application code.
 */
@Mojo(name = "template-compile", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public class TemplateCompileMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * Root directory containing template files.
     */
    @Parameter(defaultValue = "${basedir}/app", property = "play.templateSourceDirectory")
    private File sourceDirectory;

    /**
     * Output directory for generated Scala sources.
     */
    @Parameter(defaultValue = "${project.build.directory}/generated-sources/twirl", property = "play.templateOutputDirectory")
    private File outputDirectory;

    /**
     * Additional imports to add to generated template files.
     */
    @Parameter(property = "play.templateAdditionalImports")
    private List<String> additionalImports;

    /**
     * Constructor annotations for generated template classes (e.g., "@javax.inject.Inject()").
     */
    @Parameter(property = "play.templateConstructorAnnotations")
    private List<String> constructorAnnotations;

    /**
     * Source file encoding.
     */
    @Parameter(defaultValue = "UTF-8", property = "play.templateEncoding")
    private String sourceEncoding;

    /**
     * Skip template compilation.
     */
    @Parameter(defaultValue = "false", property = "play.templates.skip")
    private boolean skip;

    private static final Map<String, String> DEFAULT_FORMATS = new HashMap<>();
    static {
        DEFAULT_FORMATS.put("html", "play.twirl.api.HtmlFormat");
        DEFAULT_FORMATS.put("txt", "play.twirl.api.TxtFormat");
        DEFAULT_FORMATS.put("xml", "play.twirl.api.XmlFormat");
        DEFAULT_FORMATS.put("js", "play.twirl.api.JavaScriptFormat");
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping template compilation");
            return;
        }

        if (!sourceDirectory.exists() || !sourceDirectory.isDirectory()) {
            getLog().info("No template source directory found at " + sourceDirectory.getAbsolutePath() + ", skipping");
            return;
        }

        List<File> templateFiles = findTemplateFiles(sourceDirectory);
        if (templateFiles.isEmpty()) {
            getLog().info("No template files found in " + sourceDirectory.getAbsolutePath());
            return;
        }

        outputDirectory.mkdirs();

        List<String> imports = additionalImports != null ? additionalImports : new ArrayList<>();
        List<String> annotations = constructorAnnotations != null ? constructorAnnotations : new ArrayList<>();
        Seq<String> scalaImports = CollectionConverters.asScala(imports).toList().toSeq();
        Seq<String> scalaAnnotations = CollectionConverters.asScala(annotations).toList().toSeq();
        Codec codec = Codec.apply(sourceEncoding);

        int compiledCount = 0;
        for (File templateFile : templateFiles) {
            String formatterType = getFormatterType(templateFile.getName());
            if (formatterType == null) {
                getLog().warn("Unknown template format for: " + templateFile.getName() + ", skipping");
                continue;
            }

            getLog().debug("Compiling template: " + templateFile.getAbsolutePath());

            try {
                Option<File> result = TwirlCompiler.compile(
                        templateFile,
                        sourceDirectory,
                        outputDirectory,
                        formatterType,
                        scalaImports,
                        scalaAnnotations,
                        codec,
                        false // inclusiveDot
                );

                if (result.isDefined()) {
                    compiledCount++;
                    getLog().debug("Generated: " + result.get().getAbsolutePath());
                }
            } catch (Exception e) {
                throw new MojoFailureException("Failed to compile template " + templateFile.getAbsolutePath() + ": " + e.getMessage(), e);
            }
        }

        getLog().info("Compiled " + compiledCount + " template file(s)");
        project.addCompileSourceRoot(outputDirectory.getAbsolutePath());
    }

    private String getFormatterType(String fileName) {
        // Template files are named like: foo.scala.html, bar.scala.txt, etc.
        // We extract the extension after ".scala."
        int scalaIdx = fileName.indexOf(".scala.");
        if (scalaIdx < 0) return null;

        String ext = fileName.substring(scalaIdx + ".scala.".length());
        return DEFAULT_FORMATS.get(ext);
    }

    private List<File> findTemplateFiles(File directory) {
        List<File> result = new ArrayList<>();
        try {
            Files.walkFileTree(directory.toPath(), new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    if (name.contains(".scala.") && DEFAULT_FORMATS.containsKey(
                            name.substring(name.lastIndexOf('.') + 1))) {
                        result.add(file.toFile());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception e) {
            getLog().warn("Error scanning template directory: " + e.getMessage());
        }
        return result;
    }
}
