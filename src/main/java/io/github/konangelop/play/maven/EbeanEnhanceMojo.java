package io.github.konangelop.play.maven;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtField;
import javassist.NotFoundException;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * Enhances Ebean entity classes with bytecode for lazy loading,
 * dirty checking, and other Ebean ORM features.
 *
 * This mojo uses the Ebean agent (io.ebean:ebean-agent) from the project's
 * compile classpath to perform enhancement. The ebean-agent dependency must
 * be present in the consumer project's dependencies.
 */
@Mojo(name = "ebean-enhance", defaultPhase = LifecyclePhase.PROCESS_CLASSES,
        requiresDependencyResolution = ResolutionScope.COMPILE, threadSafe = true)
public class EbeanEnhanceMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * Directory containing compiled classes to enhance.
     */
    @Parameter(defaultValue = "${project.build.outputDirectory}", property = "play.classesDirectory")
    private File classesDirectory;

    /**
     * Ebean model packages to enhance (wildcard patterns supported).
     * Example: "models.*,com.example.models.*"
     */
    @Parameter(property = "play.ebeanModels")
    private String ebeanModels;

    /**
     * Skip Ebean enhancement.
     */
    @Parameter(defaultValue = "false", property = "play.ebean.skip")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping Ebean enhancement");
            return;
        }

        if (!classesDirectory.exists() || !classesDirectory.isDirectory()) {
            getLog().info("No classes directory found at " + classesDirectory.getAbsolutePath() + ", skipping");
            return;
        }

        try {
            URL[] classpathUrls = buildClasspath();
            URLClassLoader classLoader = new URLClassLoader(classpathUrls, getClass().getClassLoader());

            // Load the Ebean Transformer dynamically from the project's classpath
            Class<?> transformerClass;
            try {
                transformerClass = classLoader.loadClass("io.ebean.enhance.Transformer");
            } catch (ClassNotFoundException e) {
                getLog().warn("Ebean agent not found on classpath. Add io.ebean:ebean-agent as a dependency to enable Ebean enhancement.");
                return;
            }

            // Create transformer: new Transformer(classLoader, "debug=0")
            Object transformer = transformerClass
                    .getConstructor(ClassLoader.class, String.class)
                    .newInstance(classLoader, "debug=0");

            java.lang.reflect.Method transformMethod = transformerClass.getMethod(
                    "transform", ClassLoader.class, String.class, Class.class,
                    java.security.ProtectionDomain.class, byte[].class);

            List<File> classFiles = findClassFiles(classesDirectory);
            int enhancedCount = 0;

            for (File classFile : classFiles) {
                String className = classFileToClassName(classesDirectory, classFile);

                // If ebeanModels is specified, only enhance matching classes
                if (ebeanModels != null && !ebeanModels.isEmpty() && !matchesModels(className)) {
                    continue;
                }

                byte[] classBytes = Files.readAllBytes(classFile.toPath());
                byte[] enhanced = (byte[]) transformMethod.invoke(
                        transformer, classLoader, className.replace('.', '/'),
                        null, null, classBytes);

                if (enhanced != null) {
                    Files.write(classFile.toPath(), enhanced);
                    enhancedCount++;
                    getLog().debug("Ebean enhanced: " + className);
                }
            }

            getLog().info("Ebean enhanced " + enhancedCount + " class(es)");
        } catch (Exception e) {
            throw new MojoExecutionException("Ebean enhancement failed: " + e.getMessage(), e);
        }
    }

    private boolean matchesModels(String className) {
        String[] patterns = ebeanModels.split(",");
        for (String pattern : patterns) {
            pattern = pattern.trim();
            if (pattern.isEmpty()) continue;

            String regex = pattern
                    .replace(".", "\\.")
                    .replace("*", ".*");
            if (className.matches(regex)) {
                return true;
            }
        }
        return false;
    }

    private URL[] buildClasspath() throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(classesDirectory.toURI().toURL());

        List<String> classpathElements = project.getCompileClasspathElements();
        for (String element : classpathElements) {
            urls.add(new File(element).toURI().toURL());
        }
        return urls.toArray(new URL[0]);
    }

    private String classFileToClassName(File classesDir, File classFile) {
        String relativePath = classesDir.toPath().relativize(classFile.toPath()).toString();
        return relativePath
                .replace(File.separatorChar, '.')
                .replaceAll("\\.class$", "");
    }

    private List<File> findClassFiles(File directory) {
        List<File> result = new ArrayList<>();
        try {
            Files.walkFileTree(directory.toPath(), new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.toString().endsWith(".class")) {
                        result.add(file.toFile());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            getLog().warn("Error scanning classes directory: " + e.getMessage());
        }
        return result;
    }
}
