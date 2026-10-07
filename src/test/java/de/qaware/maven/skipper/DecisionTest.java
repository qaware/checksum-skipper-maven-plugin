package de.qaware.maven.skipper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionTest {

    @TempDir
    Path dir;

    @Test
    void upToDateWhenChecksumUnchangedAndOutputsPresent() throws IOException {
        Path output = Files.createDirectories(dir.resolve("generated"));
        Files.writeString(output.resolve("Table.java"), "class Table {}");

        assertThat(Decision.decide("abc", Optional.of("abc"), List.of(output), false))
                .isEqualTo(new Decision(true, "inputs unchanged"));
    }

    @Test
    void notUpToDateWithoutPreviousChecksum() {
        assertThat(Decision.decide("abc", Optional.empty(), List.of(), false))
                .isEqualTo(new Decision(false, "no checksum recorded yet"));
    }

    @Test
    void notUpToDateWhenChecksumChanged() {
        assertThat(Decision.decide("abc", Optional.of("old"), List.of(), false))
                .isEqualTo(new Decision(false, "inputs changed"));
    }

    @Test
    void notUpToDateWhenForced() {
        assertThat(Decision.decide("abc", Optional.of("abc"), List.of(), true))
                .isEqualTo(new Decision(false, "forced"));
    }

    @Test
    void notUpToDateWhenOutputMissing() {
        Path output = dir.resolve("generated");

        assertThat(Decision.decide("abc", Optional.of("abc"), List.of(output), false))
                .isEqualTo(new Decision(false, "output missing: " + output));
    }

    @Test
    void notUpToDateWhenOutputDirectoryEmpty() throws IOException {
        Path output = Files.createDirectories(dir.resolve("generated"));

        assertThat(Decision.decide("abc", Optional.of("abc"), List.of(output), false))
                .isEqualTo(new Decision(false, "output missing: " + output));
    }

    @Test
    void upToDateWhenOutputIsExistingFile() throws IOException {
        Path output = Files.writeString(dir.resolve("schema.json"), "{}");

        assertThat(Decision.decide("abc", Optional.of("abc"), List.of(output), false).upToDate()).isTrue();
    }
}
