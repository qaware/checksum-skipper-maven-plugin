package de.qaware.maven.skipper;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.shared.model.fileset.FileSet;
import org.apache.maven.shared.model.fileset.util.FileSetManager;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Resolves the configured file sets to the files they contain.
 */
final class InputFiles {

    private InputFiles() {
    }

    /**
     * @param baseDir  the project base directory; relative file set directories are resolved against it
     * @param fileSets the file sets to resolve; their directory is replaced by the resolved absolute one
     * @return the included files, keyed by their path relative to the base directory with '/' separators
     * @throws MojoExecutionException if a file set has no directory or its directory does not exist
     */
    static Map<String, Path> resolve(Path baseDir, List<FileSet> fileSets) throws MojoExecutionException {
        FileSetManager manager = new FileSetManager();
        Map<String, Path> files = new TreeMap<>();
        for (FileSet fileSet : fileSets) {
            if (fileSet.getDirectory() == null) {
                throw new MojoExecutionException("Each <fileSet> needs a <directory>");
            }
            Path directory = baseDir.resolve(fileSet.getDirectory()).normalize();
            if (!Files.isDirectory(directory)) {
                throw new MojoExecutionException("Input directory does not exist: " + directory);
            }
            // FileSetManager resolves relative directories against the working directory, so hand it an absolute one
            fileSet.setDirectory(directory.toString());
            for (String included : manager.getIncludedFiles(fileSet)) {
                Path file = directory.resolve(included);
                files.put(baseDir.relativize(file).toString().replace(File.separatorChar, '/'), file);
            }
        }
        return files;
    }
}
