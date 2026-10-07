package de.qaware.maven.skipper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.assertj.core.api.Assertions.assertThat;

class ChecksumFileTest {

    @TempDir
    Path dir;

    @Test
    void readsNothingFromMissingFile() throws IOException {
        assertThat(ChecksumFile.read(dir.resolve("inputs.sha256"))).isEmpty();
    }

    @Test
    void readsNothingFromBlankFile() throws IOException {
        assertThat(ChecksumFile.read(Files.writeString(dir.resolve("inputs.sha256"), " \n"))).isEmpty();
    }

    @Test
    void readsChecksumWithSurroundingWhitespace() throws IOException {
        assertThat(ChecksumFile.read(Files.writeString(dir.resolve("inputs.sha256"), "  abc\r\n"))).contains("abc");
    }

    @Test
    void readsChecksumWithoutTrailingNewline() throws IOException {
        assertThat(ChecksumFile.read(Files.writeString(dir.resolve("inputs.sha256"), "abc"))).contains("abc");
    }

    @Test
    void writeCreatesParentDirectoriesAndRoundTrips() throws IOException {
        Path file = dir.resolve("nested/inputs.sha256");

        ChecksumFile.write(file, "abc");

        assertThat(file).hasContent("abc\n");
        assertThat(ChecksumFile.read(file)).contains("abc");
    }

    @Test
    void writeLeavesUnchangedFileUntouched() throws IOException {
        Path file = dir.resolve("inputs.sha256");
        ChecksumFile.write(file, "abc");
        FileTime past = FileTime.fromMillis(0);
        Files.setLastModifiedTime(file, past);

        ChecksumFile.write(file, "abc");

        assertThat(Files.getLastModifiedTime(file)).isEqualTo(past);
    }
}
