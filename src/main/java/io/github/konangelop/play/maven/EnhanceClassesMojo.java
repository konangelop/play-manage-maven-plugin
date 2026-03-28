package io.github.konangelop.play.maven;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtField;
import javassist.CtMethod;
import javassist.CtNewMethod;
import javassist.Modifier;
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
import java.util.List;

/**
 * Enhances compiled Java classes with Play-specific bytecode modifications.
 * Generates public accessor methods for non-public fields in model classes,
 * similar to what Play's enhancer does in SBT builds.
 */
@Mojo(name = "enhance-classes", defaultPhase = LifecyclePhase.PROCESS_CLASSES,
        requiresDependencyResolution = ResolutionScope.COMPILE, threadSafe = true)
public class EnhanceClassesMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * Directory containing compiled classes to enhance.
     */
    @Parameter(defaultValue = "${project.build.outputDirectory}", property = "play.classesDirectory")
    private File classesDirectory;

    /**
     * Skip enhancement.
     */
    @Parameter(defaultValue = "false", property = "play.enhance.skip")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping class enhancement");
            return;
        }

        if (!classesDirectory.exists() || !classesDirectory.isDirectory()) {
            getLog().info("No classes directory found at " + classesDirectory.getAbsolutePath() + ", skipping");
            return;
        }

        List<File> classFiles = ClassFileUtils.findClassFiles(classesDirectory, getLog());
        if (classFiles.isEmpty()) {
            getLog().info("No class files found to enhance");
            return;
        }

        try {
            ClassPool classPool = new ClassPool(true);
            classPool.appendClassPath(classesDirectory.getAbsolutePath());

            // Add compile classpath entries
            List<String> classpathElements = project.getCompileClasspathElements();
            for (String element : classpathElements) {
                classPool.appendClassPath(element);
            }

            int enhancedCount = 0;
            for (File classFile : classFiles) {
                String className = ClassFileUtils.classFileToClassName(classesDirectory, classFile);
                try {
                    CtClass ctClass = classPool.get(className);

                    if (ctClass.isInterface() || ctClass.isAnnotation() || ctClass.isEnum()) {
                        continue;
                    }

                    boolean modified = enhanceClass(ctClass);
                    if (modified) {
                        ctClass.writeFile(classesDirectory.getAbsolutePath());
                        enhancedCount++;
                        getLog().debug("Enhanced: " + className);
                    }
                    ctClass.detach();
                } catch (NotFoundException e) {
                    getLog().debug("Skipping " + className + ": " + e.getMessage());
                }
            }

            getLog().info("Enhanced " + enhancedCount + " class(es)");
        } catch (Exception e) {
            throw new MojoExecutionException("Class enhancement failed: " + e.getMessage(), e);
        }
    }

    private boolean enhanceClass(CtClass ctClass) throws Exception {
        boolean modified = false;
        CtField[] fields = ctClass.getDeclaredFields();

        for (CtField field : fields) {
            int modifiers = field.getModifiers();

            // Only enhance non-static, non-final fields that aren't already public
            if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers) || Modifier.isPublic(modifiers)) {
                continue;
            }

            String fieldName = field.getName();
            String capitalizedName = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
            String getterName = "get" + capitalizedName;
            String setterName = "set" + capitalizedName;

            // Convert JVM type name to Java source syntax (e.g. [Ljava.lang.String; → java.lang.String[])
            String typeName = toJavaSourceTypeName(field.getType());

            // Generate getter if it doesn't exist
            if (!hasMethod(ctClass, getterName, 0)) {
                String getterBody = "public " + typeName + " " + getterName + "() { return this." + fieldName + "; }";
                CtMethod getter = CtNewMethod.make(getterBody, ctClass);
                ctClass.addMethod(getter);
                modified = true;
            }

            // Generate setter if it doesn't exist
            if (!hasMethod(ctClass, setterName, 1)) {
                String setterBody = "public void " + setterName + "(" + typeName + " value) { this." + fieldName + " = value; }";
                CtMethod setter = CtNewMethod.make(setterBody, ctClass);
                ctClass.addMethod(setter);
                modified = true;
            }
        }

        return modified;
    }

    private String toJavaSourceTypeName(CtClass type) throws NotFoundException {
        if (type.isArray()) {
            return toJavaSourceTypeName(type.getComponentType()) + "[]";
        }
        return type.getName();
    }

    private boolean hasMethod(CtClass ctClass, String methodName, int paramCount) {
        for (CtMethod method : ctClass.getDeclaredMethods()) {
            try {
                if (method.getName().equals(methodName) && method.getParameterTypes().length == paramCount) {
                    return true;
                }
            } catch (NotFoundException e) {
                // ignore
            }
        }
        return false;
    }

}
