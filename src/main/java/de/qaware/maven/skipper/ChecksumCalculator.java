package de.qaware.maven.skipper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Calculates a SHA-256 checksum over input files and named string inputs.
 */
final class ChecksumCalculator {

    private ChecksumCalculator() {
    }

    /**
     * @param files  the input files, keyed by their path relative to the project base directory with '/' separators
     * @param values further named inputs, e.g. plugin fingerprints
     * @return the lowercase hex checksum, independent of the iteration order of both maps
     */
    static String calculate(Map<String, Path> files, Map<String, String> values) throws IOException {
        MessageDigest total = sha256();
        for (Map.Entry<String, Path> file : new TreeMap<>(files).entrySet()) {
            update(total, "file:" + file.getKey() + "=" + hash(file.getValue()));
        }
        for (Map.Entry<String, String> value : new TreeMap<>(values).entrySet()) {
            // hashing the value keeps multi-line values like plugin configurations unambiguous
            update(total, "value:" + value.getKey() + "=" + hex(sha256().digest(value.getValue().getBytes(UTF_8))));
        }
        return hex(total.digest());
    }

    private static String hash(Path file) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return hex(digest.digest());
    }

    private static void update(MessageDigest digest, String line) {
        digest.update((line + "\n").getBytes(UTF_8));
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every JVM must support SHA-256", e);
        }
    }
}
