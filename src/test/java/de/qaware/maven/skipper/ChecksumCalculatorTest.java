package de.qaware.maven.skipper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChecksumCalculatorTest {

    @TempDir
    Path dir;

    @Test
    void emptyInputsYieldSha256OfNothing() throws IOException {
        assertThat(ChecksumCalculator.calculate(Map.of(), Map.of()))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void sameInputsYieldSameLowercaseHexChecksum() throws IOException {
        Path file = write("V1.sql", "create table a (id int);");

        String first = ChecksumCalculator.calculate(Map.of("db/V1.sql", file), Map.of("plugin", "v1"));
        String second = ChecksumCalculator.calculate(Map.of("db/V1.sql", file), Map.of("plugin", "v1"));

        assertThat(first).matches("[0-9a-f]{64}").isEqualTo(second);
    }

    @Test
    void changedContentChangesChecksum() throws IOException {
        Path file = write("V1.sql", "create table a (id int);");
        String before = ChecksumCalculator.calculate(Map.of("db/V1.sql", file), Map.of());

        Files.writeString(file, "create table b (id int);");

        assertThat(ChecksumCalculator.calculate(Map.of("db/V1.sql", file), Map.of())).isNotEqualTo(before);
    }

    @Test
    void renamedFileChangesChecksum() throws IOException {
        Path file = write("V1.sql", "create table a (id int);");

        assertThat(ChecksumCalculator.calculate(Map.of("db/V1.sql", file), Map.of()))
                .isNotEqualTo(ChecksumCalculator.calculate(Map.of("db/V2.sql", file), Map.of()));
    }

    @Test
    void changedValueChangesChecksum() throws IOException {
        assertThat(ChecksumCalculator.calculate(Map.of(), Map.of("plugin", "v1")))
                .isNotEqualTo(ChecksumCalculator.calculate(Map.of(), Map.of("plugin", "v2")));
    }

    @Test
    void checksumDoesNotDependOnInsertionOrder() throws IOException {
        Path a = write("a.sql", "a");
        Path b = write("b.sql", "b");
        Map<String, Path> ab = new LinkedHashMap<>();
        ab.put("a.sql", a);
        ab.put("b.sql", b);
        Map<String, Path> ba = new LinkedHashMap<>();
        ba.put("b.sql", b);
        ba.put("a.sql", a);

        assertThat(ChecksumCalculator.calculate(ab, Map.of()))
                .isEqualTo(ChecksumCalculator.calculate(ba, Map.of()));
    }

    private Path write(String name, String content) throws IOException {
        return Files.writeString(dir.resolve(name), content);
    }
}
