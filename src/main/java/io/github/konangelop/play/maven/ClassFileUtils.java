package io.github.konangelop.play.maven;

import org.apache.maven.plugin.logging.Log;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared utilities for bytecode enhancement Mojos that operate on compiled class files.
 */
class ClassFileUtils {

    static List<File> findClassFiles(File directory, Log log) {
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
            log.warn("Error scanning classes directory: " + e.getMessage());
        }
        return result;
    }

    static String classFileToClassName(File classesDir, File classFile) {
        String relativePath = classesDir.toPath().relativize(classFile.toPath()).toString();
        return relativePath
                .replace(File.separatorChar, '.')
                .replaceAll("\\.class$", "");
    }
}
