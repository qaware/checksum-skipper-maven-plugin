package de.qaware.maven.skipper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Reads and writes the file holding the checksum of the last successful run.
 */
final class ChecksumFile {

    private ChecksumFile() {
    }

    /**
     * @return the recorded checksum without surrounding whitespace, or empty if the file is missing or blank
     */
    static Optional<String> read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        String content = Files.readString(file).trim();
        return content.isEmpty() ? Optional.empty() : Optional.of(content);
    }

    /**
     * Writes the checksum, creating parent directories; leaves the file untouched if it already holds the checksum.
     */
    static void write(Path file, String checksum) throws IOException {
        if (read(file).filter(checksum::equals).isPresent()) {
            return;
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, checksum + "\n");
    }
}
