package de.qaware.maven.skipper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Whether the outputs of a build step are up to date with its inputs, and why.
 *
 * @param upToDate true if the step can be skipped
 * @param reason   human-readable reason for the log
 */
record Decision(boolean upToDate, String reason) {

    /**
     * @param checksum         the checksum of the current inputs
     * @param previousChecksum the checksum recorded after the last successful run, if any
     * @param outputs          files or directories the step produces; directories must not be empty
     * @param force            treat the inputs as changed in any case
     */
    static Decision decide(String checksum, Optional<String> previousChecksum, List<Path> outputs, boolean force) {
        if (force) {
            return new Decision(false, "forced");
        }
        if (previousChecksum.isEmpty()) {
            return new Decision(false, "no checksum recorded yet");
        }
        for (Path output : outputs) {
            if (isMissing(output)) {
                return new Decision(false, "output missing: " + output);
            }
        }
        if (!previousChecksum.get().equals(checksum)) {
            return new Decision(false, "inputs changed");
        }
        return new Decision(true, "inputs unchanged");
    }

    private static boolean isMissing(Path output) {
        if (!Files.isDirectory(output)) {
            return !Files.exists(output);
        }
        try (Stream<Path> entries = Files.list(output)) {
            return entries.findAny().isEmpty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
