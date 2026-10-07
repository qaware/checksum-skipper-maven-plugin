package de.qaware.maven.skipper;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.shared.model.fileset.FileSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InputFilesTest {

    @TempDir
    Path baseDir;

    @Test
    void resolvesRelativeDirectoryAgainstBaseDirNotWorkingDirectory() throws Exception {
        Path file = write("migrations/V1.sql");

        assertThat(InputFiles.resolve(baseDir, List.of(fileSet("migrations"))))
                .containsExactlyEntriesOf(Map.of("migrations/V1.sql", file));
    }

    @Test
    void keysAbsoluteDirectoryRelativeToBaseDirWithSlashes() throws Exception {
        write("src/db/nested/V1.sql");

        assertThat(InputFiles.resolve(baseDir, List.of(fileSet(baseDir.resolve("src/db").toString()))))
                .containsOnlyKeys("src/db/nested/V1.sql");
    }

    @Test
    void appliesIncludesAndExcludes() throws Exception {
        write("db/V1.sql");
        write("db/notes.txt");
        write("db/V2.sql");
        FileSet set = fileSet("db");
        set.addInclude("**/*.sql");
        set.addExclude("**/V2.sql");

        assertThat(InputFiles.resolve(baseDir, List.of(set))).containsOnlyKeys("db/V1.sql");
    }

    @Test
    void failsForMissingDirectory() {
        assertThatThrownBy(() -> InputFiles.resolve(baseDir, List.of(fileSet("does-not-exist"))))
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("does-not-exist");
    }

    @Test
    void failsForFileSetWithoutDirectory() {
        assertThatThrownBy(() -> InputFiles.resolve(baseDir, List.of(new FileSet())))
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("<directory>");
    }

    private static FileSet fileSet(String directory) {
        FileSet set = new FileSet();
        set.setDirectory(directory);
        return set;
    }

    private Path write(String relativePath) throws IOException {
        Path file = baseDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, relativePath);
    }
}
