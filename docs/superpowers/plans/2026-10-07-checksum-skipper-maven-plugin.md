# Checksum Skipper Maven Plugin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Maven plugin that hashes the inputs of an expensive build step and sets a property, so that step's own `skip` parameter turns it off while its inputs are unchanged since the last successful run.

**Architecture:** Two goals that share state through the Maven plugin context. `check` (default phase `initialize`) builds a SHA-256 over the configured file sets plus the effective configuration of named plugins, compares it with the checksum file from the last run, and sets a project property to `true`/`false`. `record` (default phase `process-sources`) runs after the expensive step and writes the new checksum, so a failed step never records anything. The hashing, file resolution, plugin fingerprint and the decision itself are small package-private classes; the mojos only wire them together.

**Tech Stack:** Java 21, Maven 3.9 plugin API (`maven-plugin-api`, `maven-core`, `maven-plugin-annotations`), `org.apache.maven.shared:file-management` for Ant-style file sets, JUnit 5 + AssertJ, `maven-invoker-plugin` for the end-to-end test, GitHub Actions, Maven Central (Central Portal).

**Spec:** There's no separate spec document; the design is summarized under "Design" below. The motivating case is jOOQ code generation that starts a database container and runs Flyway migrations on every build, although the migrations rarely change.

## Design

- **Why not antrun or existing plugins:** `net.nicoulaj:checksum-maven-plugin` only writes checksums. `build-helper:uptodate-property` compares timestamps (a deleted input goes unnoticed). The Maven Build Cache Extension works per module, so any Java edit reruns the codegen. An inline `maven-antrun-plugin` hash check works, but it is an untestable script and has to hash the whole `pom.xml`, so every version bump regenerates.
- **Inputs:**
  - `fileSets`: Ant-style file sets. File paths relative to the project base directory and file contents go into the hash.
  - `plugins`: `groupId:artifactId` entries. Each plugin's version, dependencies, plugin-level configuration and executions (id, phase, goals, configuration) go into the hash. The project's own version does not.
- **Up to date** means all of these hold:
  - no force flag,
  - a recorded checksum exists,
  - every configured output exists (directories must not be empty),
  - the recorded checksum equals the current one.
- **Checksum file:** 64 lowercase hex chars plus a newline. It is read with `trim()`, so a file written by another tool (no trailing newline) or edited by hand still matches.
- **Hand-over:** `check` puts the checksum into the plugin context under a key derived from the normalized absolute `checksumFile`. `record` fails if it finds nothing under its key.

## Global Constraints

- Open source by QAware GmbH under the Apache License 2.0, like `de.qaware.maven:go-offline-maven-plugin`
- groupId `de.qaware.maven`, artifactId `checksum-skipper-maven-plugin`, first version `1.0.0-SNAPSHOT`, goal prefix `skipper` (not `checksum`: `checksum:check` would read like the verify goal of `net.nicoulaj:checksum-maven-plugin`)
- Java release 21; Maven prerequisite 3.9.0; Maven API dependencies in scope `provided`
- Only runtime dependency: `org.apache.maven.shared:file-management:3.1.0`
- Both mojos `threadSafe = true` (mvnd and `-T` builds)
- Package `de.qaware.maven.skipper`; helper classes package-private and `final`
- Hosted at `https://github.com/qaware/checksum-skipper-maven-plugin`, CI with GitHub Actions; releases go to Maven Central through the Central Portal (`central-publishing-maven-plugin`, server id `central`), triggered by a `v*` tag
- Every task works in this repository only. Code, tests, README and examples use only public tools.

## Review Focus

1. **Relative `fileSet` directory while Maven runs from another working directory** (`mvn -f sub/pom.xml`, reactor builds): it must resolve against the project base directory, not the CWD. Test in Task 2.
2. **Checksum file without a trailing newline or with stray whitespace** (written by another tool, or edited by hand): it must still count as equal. Test in Task 4.
3. **`record` with a differently spelled but equivalent `checksumFile`** (`target/x/../inputs.sha256` vs `target/inputs.sha256`): it must find the checksum. With no `check` at all it must fail loudly, not silently skip the write. Tests in Task 5.
4. **Output directory exists but is empty** (interrupted generation, or the generator created the directory before failing): this must count as not up to date. Test in Task 4.
5. **Two check/record pairs in one module** (e.g. jOOQ and OpenAPI): each must record its own checksum. Test in Task 5.

---

### Task 1: Project scaffold and checksum calculation

**Files:**
- Create: `pom.xml`
- Create: `.gitignore`
- Create: `src/main/java/de/qaware/maven/skipper/ChecksumCalculator.java`
- Test: `src/test/java/de/qaware/maven/skipper/ChecksumCalculatorTest.java`

**Interfaces:**
- Produces: `static String ChecksumCalculator.calculate(Map<String, Path> files, Map<String, String> values) throws IOException`. `files` is keyed by the relative path with `/` separators. Returns a lowercase hex SHA-256 that doesn't depend on map iteration order.

- [ ] **Step 1: Create `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>de.qaware.maven</groupId>
    <artifactId>checksum-skipper-maven-plugin</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>maven-plugin</packaging>

    <name>Checksum Skipper Maven Plugin</name>
    <description>Skips expensive build steps while their inputs are unchanged since the last successful run</description>
    <url>https://github.com/qaware/checksum-skipper-maven-plugin</url>
    <inceptionYear>2026</inceptionYear>

    <licenses>
        <license>
            <name>Apache License, Version 2.0</name>
            <url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>
        </license>
    </licenses>
    <organization>
        <name>QAware GmbH</name>
        <url>https://www.qaware.de/</url>
    </organization>
    <developers>
        <developer>
            <id>ajanning</id>
            <name>Andreas Janning</name>
            <email>andreas.janning@qaware.de</email>
            <organization>QAware GmbH</organization>
            <organizationUrl>https://www.qaware.de/</organizationUrl>
        </developer>
    </developers>

    <prerequisites>
        <maven>3.9.0</maven>
    </prerequisites>

    <scm>
        <connection>scm:git:https://github.com/qaware/checksum-skipper-maven-plugin.git</connection>
        <developerConnection>scm:git:https://github.com/qaware/checksum-skipper-maven-plugin.git</developerConnection>
        <url>https://github.com/qaware/checksum-skipper-maven-plugin</url>
        <tag>HEAD</tag>
    </scm>
    <issueManagement>
        <system>GitHub</system>
        <url>https://github.com/qaware/checksum-skipper-maven-plugin/issues</url>
    </issueManagement>

    <properties>
        <maven.compiler.release>21</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <maven.version>3.9.9</maven.version>
        <maven-plugin-tools.version>3.15.1</maven-plugin-tools.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.apache.maven</groupId>
            <artifactId>maven-plugin-api</artifactId>
            <version>${maven.version}</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.apache.maven</groupId>
            <artifactId>maven-core</artifactId>
            <version>${maven.version}</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.apache.maven.plugin-tools</groupId>
            <artifactId>maven-plugin-annotations</artifactId>
            <version>${maven-plugin-tools.version}</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.apache.maven.shared</groupId>
            <artifactId>file-management</artifactId>
            <version>3.1.0</version>
        </dependency>

        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>5.11.4</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <version>3.27.3</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <version>3.13.0</version>
                </plugin>
                <plugin>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <version>3.5.2</version>
                </plugin>
                <plugin>
                    <artifactId>maven-jar-plugin</artifactId>
                    <version>3.4.2</version>
                </plugin>
                <plugin>
                    <artifactId>maven-install-plugin</artifactId>
                    <version>3.1.3</version>
                </plugin>
                <plugin>
                    <artifactId>maven-deploy-plugin</artifactId>
                    <version>3.1.3</version>
                </plugin>
            </plugins>
        </pluginManagement>
        <plugins>
            <plugin>
                <artifactId>maven-plugin-plugin</artifactId>
                <version>${maven-plugin-tools.version}</version>
                <configuration>
                    <goalPrefix>skipper</goalPrefix>
                    <!-- the mojos only arrive in Task 5 -->
                    <skipErrorNoDescriptorsFound>true</skipErrorNoDescriptorsFound>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

There is no `<distributionManagement>`: the `release` profile from Task 7 publishes through the Central Portal.

- [ ] **Step 2: Create `.gitignore`**

```
target/
.idea/
*.iml
.vscode/
.DS_Store
```

- [ ] **Step 3: Write the failing test**

`src/test/java/de/qaware/maven/skipper/ChecksumCalculatorTest.java`:

```java
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
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `mvn -q test -Dtest=ChecksumCalculatorTest`
Expected: `COMPILATION ERROR` … `cannot find symbol` … `ChecksumCalculator`

- [ ] **Step 5: Write the implementation**

`src/main/java/de/qaware/maven/skipper/ChecksumCalculator.java`:

```java
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
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `mvn -q test -Dtest=ChecksumCalculatorTest`
Expected: BUILD SUCCESS, 6 tests run, 0 failures

- [ ] **Step 7: Commit**

```bash
git add pom.xml .gitignore src docs
git commit -m "Add plugin scaffold and SHA-256 checksum over files and values"
```

---

### Task 2: Resolve file sets to input files

**Files:**
- Create: `src/main/java/de/qaware/maven/skipper/InputFiles.java`
- Test: `src/test/java/de/qaware/maven/skipper/InputFilesTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `static Map<String, Path> InputFiles.resolve(Path baseDir, List<FileSet> fileSets) throws MojoExecutionException`, where `FileSet` is `org.apache.maven.shared.model.fileset.FileSet`. Keys are paths relative to `baseDir` with `/` separators, and the result plugs straight into `ChecksumCalculator.calculate`.

- [ ] **Step 1: Write the failing test**

`src/test/java/de/qaware/maven/skipper/InputFilesTest.java`:

```java
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q test -Dtest=InputFilesTest`
Expected: `COMPILATION ERROR` … `cannot find symbol` … `InputFiles`

- [ ] **Step 3: Write the implementation**

`src/main/java/de/qaware/maven/skipper/InputFiles.java`:

```java
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
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q test -Dtest=InputFilesTest`
Expected: BUILD SUCCESS, 5 tests run, 0 failures

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "Resolve file sets relative to the project base directory"
```

---

### Task 3: Plugin fingerprint

**Files:**
- Create: `src/main/java/de/qaware/maven/skipper/PluginFingerprint.java`
- Test: `src/test/java/de/qaware/maven/skipper/PluginFingerprintTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `static String PluginFingerprint.of(MavenProject project, String key) throws MojoExecutionException`. `key` is `groupId:artifactId`. Returns a stable multi-line text of the version, dependencies, configuration and executions.

- [ ] **Step 1: Write the failing test**

`src/test/java/de/qaware/maven/skipper/PluginFingerprintTest.java`:

```java
package de.qaware.maven.skipper;

import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginFingerprintTest {

    private static final String KEY = "org.example:codegen";

    @Test
    void sameConfigurationYieldsSameFingerprint() throws Exception {
        assertThat(PluginFingerprint.of(project("1.0", codegen("1.0", "public")), KEY))
                .isEqualTo(PluginFingerprint.of(project("1.0", codegen("1.0", "public")), KEY));
    }

    @Test
    void projectVersionDoesNotChangeFingerprint() throws Exception {
        assertThat(PluginFingerprint.of(project("1.0", codegen("1.0", "public")), KEY))
                .isEqualTo(PluginFingerprint.of(project("2.0", codegen("1.0", "public")), KEY));
    }

    @Test
    void pluginVersionChangesFingerprint() throws Exception {
        assertThat(PluginFingerprint.of(project("1.0", codegen("1.0", "public")), KEY))
                .isNotEqualTo(PluginFingerprint.of(project("1.0", codegen("1.1", "public")), KEY));
    }

    @Test
    void executionConfigurationChangesFingerprint() throws Exception {
        assertThat(PluginFingerprint.of(project("1.0", codegen("1.0", "public")), KEY))
                .isNotEqualTo(PluginFingerprint.of(project("1.0", codegen("1.0", "other")), KEY));
    }

    @Test
    void pluginDependencyChangesFingerprint() throws Exception {
        Plugin withDependency = codegen("1.0", "public");
        Dependency dependency = new Dependency();
        dependency.setGroupId("org.jooq");
        dependency.setArtifactId("jooq-codegen");
        dependency.setVersion("3.21.9");
        withDependency.addDependency(dependency);

        assertThat(PluginFingerprint.of(project("1.0", codegen("1.0", "public")), KEY))
                .isNotEqualTo(PluginFingerprint.of(project("1.0", withDependency), KEY));
    }

    @Test
    void failsForPluginNotInBuild() {
        assertThatThrownBy(() -> PluginFingerprint.of(project("1.0", codegen("1.0", "public")), "org.example:missing"))
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("org.example:missing");
    }

    private static MavenProject project(String version, Plugin plugin) {
        Model model = new Model();
        model.setGroupId("org.example");
        model.setArtifactId("app");
        model.setVersion(version);
        Build build = new Build();
        build.addPlugin(plugin);
        model.setBuild(build);
        return new MavenProject(model);
    }

    private static Plugin codegen(String version, String schema) {
        Plugin plugin = new Plugin();
        plugin.setGroupId("org.example");
        plugin.setArtifactId("codegen");
        plugin.setVersion(version);
        Xpp3Dom configuration = new Xpp3Dom("configuration");
        Xpp3Dom schemaElement = new Xpp3Dom("schema");
        schemaElement.setValue(schema);
        configuration.addChild(schemaElement);
        PluginExecution execution = new PluginExecution();
        execution.setId("generate");
        execution.addGoal("generate");
        execution.setConfiguration(configuration);
        plugin.addExecution(execution);
        return plugin;
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q test -Dtest=PluginFingerprintTest`
Expected: `COMPILATION ERROR` … `cannot find symbol` … `PluginFingerprint`

- [ ] **Step 3: Write the implementation**

`src/main/java/de/qaware/maven/skipper/PluginFingerprint.java`:

```java
package de.qaware.maven.skipper;

import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;

import java.util.Comparator;

/**
 * Describes the effective setup of a build plugin, so that changing its version, dependencies or configuration
 * changes the checksum, while unrelated pom changes such as the project version do not.
 */
final class PluginFingerprint {

    private PluginFingerprint() {
    }

    /**
     * @param project the project whose build contains the plugin
     * @param key     the plugin as groupId:artifactId
     * @return version, dependencies, configuration and executions of the plugin in a stable textual form
     * @throws MojoExecutionException if the plugin is not part of the project's build
     */
    static String of(MavenProject project, String key) throws MojoExecutionException {
        Plugin plugin = project.getBuild().getPluginsAsMap().get(key);
        if (plugin == null) {
            throw new MojoExecutionException("Plugin " + key + " is not configured in the build of " + project.getId());
        }
        StringBuilder fingerprint = new StringBuilder();
        fingerprint.append("version=").append(plugin.getVersion()).append('\n');
        plugin.getDependencies().stream()
                .map(dependency -> "dependency=" + dependency.getManagementKey() + ":" + dependency.getVersion() + "\n")
                .sorted()
                .forEach(fingerprint::append);
        fingerprint.append("configuration=").append(plugin.getConfiguration()).append('\n');
        plugin.getExecutions().stream()
                .sorted(Comparator.comparing(PluginExecution::getId))
                .forEach(execution -> fingerprint
                        .append("execution=").append(execution.getId())
                        .append(" phase=").append(execution.getPhase())
                        .append(" goals=").append(execution.getGoals())
                        .append(" configuration=").append(execution.getConfiguration())
                        .append('\n'));
        return fingerprint.toString();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q test -Dtest=PluginFingerprintTest`
Expected: BUILD SUCCESS, 6 tests run, 0 failures

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "Fingerprint the effective setup of a build plugin"
```

---

### Task 4: Up-to-date decision and checksum file

**Files:**
- Create: `src/main/java/de/qaware/maven/skipper/Decision.java`
- Create: `src/main/java/de/qaware/maven/skipper/ChecksumFile.java`
- Test: `src/test/java/de/qaware/maven/skipper/DecisionTest.java`
- Test: `src/test/java/de/qaware/maven/skipper/ChecksumFileTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `record Decision(boolean upToDate, String reason)` with `static Decision decide(String checksum, Optional<String> previousChecksum, List<Path> outputs, boolean force)`
  - `static Optional<String> ChecksumFile.read(Path file) throws IOException`: empty if the file is missing or blank, otherwise the trimmed content
  - `static void ChecksumFile.write(Path file, String checksum) throws IOException`: creates parent directories and writes `checksum + "\n"`, only if the content differs

- [ ] **Step 1: Write the failing tests**

`src/test/java/de/qaware/maven/skipper/DecisionTest.java`:

```java
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
```

`src/test/java/de/qaware/maven/skipper/ChecksumFileTest.java`:

```java
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

        assertThat(file).hasContent("abc");
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
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q test -Dtest='DecisionTest,ChecksumFileTest'`
Expected: `COMPILATION ERROR` … `cannot find symbol` … `Decision` / `ChecksumFile`

- [ ] **Step 3: Write the implementation**

`src/main/java/de/qaware/maven/skipper/Decision.java`:

```java
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
```

`src/main/java/de/qaware/maven/skipper/ChecksumFile.java`:

```java
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
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q test -Dtest='DecisionTest,ChecksumFileTest'`
Expected: BUILD SUCCESS, 13 tests run, 0 failures

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "Decide up-to-dateness and read/write the checksum file"
```

---

### Task 5: `check` and `record` goals

**Files:**
- Create: `src/main/java/de/qaware/maven/skipper/CheckMojo.java`
- Create: `src/main/java/de/qaware/maven/skipper/RecordMojo.java`
- Modify: `pom.xml` (remove `<skipErrorNoDescriptorsFound>` and its comment from `maven-plugin-plugin`)
- Test: `src/test/java/de/qaware/maven/skipper/CheckAndRecordMojoTest.java`

**Interfaces:**
- Consumes: `ChecksumCalculator.calculate`, `InputFiles.resolve`, `PluginFingerprint.of`, `Decision.decide`, `ChecksumFile.read/write` (Tasks 1–4)
- Produces:
  - goal `check`, default phase `initialize`. Parameters: `fileSets` (`List<FileSet>`), `plugins` (`List<String>`), `outputs` (`List<File>`), `checksumFile` (`File`, required), `property` (`String`, required), `force` (`boolean`, user property `skipper.force`).
  - goal `record`, default phase `process-sources`. Parameter: `checksumFile` (`File`, required).
  - `static String CheckMojo.contextKey(File checksumFile)`: the plugin-context key shared by both goals.

- [ ] **Step 1: Write the failing test**

`src/test/java/de/qaware/maven/skipper/CheckAndRecordMojoTest.java`:

```java
package de.qaware.maven.skipper;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.model.fileset.FileSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckAndRecordMojoTest {

    private static final String PROPERTY = "codegen.skip";

    @TempDir
    Path baseDir;

    private MavenProject project;
    private File checksumFile;

    @BeforeEach
    void setUp() throws IOException {
        project = new MavenProject();
        project.setFile(baseDir.resolve("pom.xml").toFile());
        Files.createDirectories(baseDir.resolve("migrations"));
        Files.writeString(baseDir.resolve("migrations/V1.sql"), "create table a (id int);");
        checksumFile = baseDir.resolve("target/inputs.sha256").toFile();
    }

    @Test
    void firstBuildIsNotUpToDateSecondBuildIs() throws Exception {
        build();
        assertThat(project.getProperties()).containsEntry(PROPERTY, "false");

        build();
        assertThat(project.getProperties()).containsEntry(PROPERTY, "true");
    }

    @Test
    void changedInputIsNotUpToDate() throws Exception {
        build();
        Files.writeString(baseDir.resolve("migrations/V2.sql"), "create table b (id int);");

        check(new HashMap<>(), checksumFile).execute();

        assertThat(project.getProperties()).containsEntry(PROPERTY, "false");
    }

    @Test
    void forceIsNotUpToDate() throws Exception {
        build();
        CheckMojo check = check(new HashMap<>(), checksumFile);
        check.force = true;

        check.execute();

        assertThat(project.getProperties()).containsEntry(PROPERTY, "false");
    }

    @Test
    void missingOutputIsNotUpToDate() throws Exception {
        build();
        CheckMojo check = check(new HashMap<>(), checksumFile);
        check.outputs = List.of(baseDir.resolve("target/generated-sources/jooq").toFile());

        check.execute();

        assertThat(project.getProperties()).containsEntry(PROPERTY, "false");
    }

    @Test
    void buildFailingBeforeRecordLeavesNextBuildNotUpToDate() throws Exception {
        check(new HashMap<>(), checksumFile).execute();
        // the expensive step failed, record never ran

        check(new HashMap<>(), checksumFile).execute();

        assertThat(project.getProperties()).containsEntry(PROPERTY, "false");
        assertThat(checksumFile).doesNotExist();
    }

    @Test
    void recordWithoutCheckFails() {
        RecordMojo record = record(new HashMap<>(), checksumFile);

        assertThatThrownBy(record::execute)
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining(checksumFile.getName());
    }

    @Test
    void recordFindsCheckWithEquivalentChecksumFileSpelling() throws Exception {
        Map<Object, Object> context = new HashMap<>();
        check(context, baseDir.resolve("target/x/../inputs.sha256").toFile()).execute();

        record(context, checksumFile).execute();

        assertThat(checksumFile).content().matches("[0-9a-f]{64}\n");
    }

    @Test
    void twoCheckRecordPairsKeepTheirOwnChecksums() throws Exception {
        Files.createDirectories(baseDir.resolve("api"));
        Files.writeString(baseDir.resolve("api/spec.yaml"), "openapi: 3.1.0");
        File apiChecksumFile = baseDir.resolve("target/api.sha256").toFile();
        Map<Object, Object> context = new HashMap<>();
        check(context, checksumFile).execute();
        CheckMojo apiCheck = check(context, apiChecksumFile);
        apiCheck.fileSets = new ArrayList<>(List.of(fileSet("api")));
        apiCheck.execute();

        record(context, checksumFile).execute();
        record(context, apiChecksumFile).execute();

        assertThat(Files.readString(checksumFile.toPath())).isNotEqualTo(Files.readString(apiChecksumFile.toPath()));
    }

    @Test
    void checkWithoutInputsFails() {
        CheckMojo check = check(new HashMap<>(), checksumFile);
        check.fileSets = new ArrayList<>();

        assertThatThrownBy(check::execute)
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("<fileSet>");
    }

    /** One successful build: check, the expensive step, record. */
    private void build() throws MojoExecutionException {
        Map<Object, Object> context = new HashMap<>();
        check(context, checksumFile).execute();
        record(context, checksumFile).execute();
    }

    private CheckMojo check(Map<Object, Object> context, File file) {
        CheckMojo mojo = new CheckMojo();
        mojo.setPluginContext(context);
        mojo.project = project;
        mojo.fileSets = new ArrayList<>(List.of(fileSet("migrations")));
        mojo.checksumFile = file;
        mojo.property = PROPERTY;
        return mojo;
    }

    private static RecordMojo record(Map<Object, Object> context, File file) {
        RecordMojo mojo = new RecordMojo();
        mojo.setPluginContext(context);
        mojo.checksumFile = file;
        return mojo;
    }

    private static FileSet fileSet(String directory) {
        FileSet set = new FileSet();
        set.setDirectory(directory);
        return set;
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q test -Dtest=CheckAndRecordMojoTest`
Expected: `COMPILATION ERROR` … `cannot find symbol` … `CheckMojo` / `RecordMojo`

- [ ] **Step 3: Write `CheckMojo`**

`src/main/java/de/qaware/maven/skipper/CheckMojo.java`:

```java
package de.qaware.maven.skipper;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.model.fileset.FileSet;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Calculates a checksum over the configured inputs and sets {@link #property} to {@code true} if the inputs are
 * unchanged since the last run recorded by the {@code record} goal and all outputs exist, else to {@code false}.
 * Pass the property to the skip parameter of the expensive plugin.
 */
@Mojo(name = "check", defaultPhase = LifecyclePhase.INITIALIZE, threadSafe = true)
public class CheckMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    /** Files whose paths and contents are part of the checksum. */
    @Parameter
    List<FileSet> fileSets = new ArrayList<>();

    /** Plugins as groupId:artifactId whose version, dependencies and configuration are part of the checksum. */
    @Parameter
    List<String> plugins = new ArrayList<>();

    /** Files or directories the step produces; the step is only up to date if they exist and directories are not empty. */
    @Parameter
    List<File> outputs = new ArrayList<>();

    /** Where the record goal stores the checksum of the last successful run. */
    @Parameter(required = true)
    File checksumFile;

    /** The project property to set to true if the step is up to date, else to false. */
    @Parameter(required = true)
    String property;

    /** Treats the inputs as changed regardless of the checksum. */
    @Parameter(property = "skipper.force", defaultValue = "false")
    boolean force;

    @Override
    @SuppressWarnings("unchecked")
    public void execute() throws MojoExecutionException {
        if (fileSets.isEmpty() && plugins.isEmpty()) {
            throw new MojoExecutionException("Configure at least one <fileSet> or <plugin> as input");
        }
        Map<String, String> values = new TreeMap<>();
        for (String plugin : plugins) {
            values.put("plugin:" + plugin, PluginFingerprint.of(project, plugin));
        }
        try {
            String checksum = ChecksumCalculator.calculate(
                    InputFiles.resolve(project.getBasedir().toPath(), fileSets), values);
            Decision decision = Decision.decide(checksum, ChecksumFile.read(checksumFile.toPath()),
                    outputs.stream().map(File::toPath).toList(), force);
            project.getProperties().setProperty(property, String.valueOf(decision.upToDate()));
            getPluginContext().put(contextKey(checksumFile), checksum);
            getLog().info(property + "=" + decision.upToDate() + " (" + decision.reason() + ")");
        } catch (IOException | UncheckedIOException e) {
            throw new MojoExecutionException("Could not check the inputs for " + checksumFile, e);
        }
    }

    /**
     * @return the plugin context key under which check hands the checksum to record, equal for equivalent paths
     */
    static String contextKey(File checksumFile) {
        return "skipper:" + checksumFile.getAbsoluteFile().toPath().normalize();
    }
}
```

- [ ] **Step 4: Write `RecordMojo`**

`src/main/java/de/qaware/maven/skipper/RecordMojo.java`:

```java
package de.qaware.maven.skipper;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;

/**
 * Records the checksum calculated by the {@code check} goal. Bind it to a phase after the expensive step, so a
 * failed step leaves the previous checksum in place and the next build runs the step again.
 */
@Mojo(name = "record", defaultPhase = LifecyclePhase.PROCESS_SOURCES, threadSafe = true)
public class RecordMojo extends AbstractMojo {

    /** Must point to the same file as the checksumFile of the check goal. */
    @Parameter(required = true)
    File checksumFile;

    @Override
    public void execute() throws MojoExecutionException {
        Object checksum = getPluginContext().get(CheckMojo.contextKey(checksumFile));
        if (checksum == null) {
            throw new MojoExecutionException("No check goal with checksumFile " + checksumFile
                    + " ran before record in this build");
        }
        try {
            ChecksumFile.write(checksumFile.toPath(), (String) checksum);
        } catch (IOException e) {
            throw new MojoExecutionException("Could not write " + checksumFile, e);
        }
    }
}
```

- [ ] **Step 5: Remove the scaffold flag from `pom.xml`**

In the `maven-plugin-plugin` configuration, delete these two lines:

```xml
                    <!-- the mojos only arrive in Task 5 -->
                    <skipErrorNoDescriptorsFound>true</skipErrorNoDescriptorsFound>
```

- [ ] **Step 6: Run all tests and verify the plugin descriptor**

Run: `mvn -q verify && unzip -p target/checksum-skipper-maven-plugin-1.0.0-SNAPSHOT.jar META-INF/maven/plugin.xml | grep -E "<goal>(check|record)</goal>"`
Expected: BUILD SUCCESS, 39 tests run, 0 failures; the grep prints `<goal>check</goal>` and `<goal>record</goal>`

- [ ] **Step 7: Commit**

```bash
git add pom.xml src
git commit -m "Add check and record goals"
```

---

### Task 6: End-to-end test with maven-invoker

**Files:**
- Modify: `pom.xml` (add `maven-invoker-plugin` to `<build><plugins>`)
- Create: `src/it/skip-when-unchanged/pom.xml`
- Create: `src/it/skip-when-unchanged/invoker.properties`
- Create: `src/it/skip-when-unchanged/src/main/db/V1__init.sql`
- Create: `src/it/skip-when-unchanged/verify.groovy`

**Interfaces:**
- Consumes: goals `check` and `record` (Task 5)
- Produces: proof that another plugin really sees the property in a real Maven run, across three invocations (first run, unchanged, forced)

- [ ] **Step 1: Add the invoker plugin to `pom.xml`**

Inside `<build><plugins>`, after `maven-plugin-plugin`:

```xml
            <plugin>
                <artifactId>maven-invoker-plugin</artifactId>
                <version>3.9.0</version>
                <configuration>
                    <cloneProjectsTo>${project.build.directory}/it</cloneProjectsTo>
                    <localRepositoryPath>${project.build.directory}/local-repo</localRepositoryPath>
                    <postBuildHookScript>verify</postBuildHookScript>
                </configuration>
                <executions>
                    <execution>
                        <id>integration-test</id>
                        <goals>
                            <goal>install</goal>
                            <goal>integration-test</goal>
                            <goal>verify</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
```

- [ ] **Step 2: Create the test project**

`src/it/skip-when-unchanged/pom.xml`. The antrun step stands in for the expensive codegen and appends the property value it sees, comma-separated, to a file:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>de.qaware.maven.it</groupId>
    <artifactId>skip-when-unchanged</artifactId>
    <version>1.0</version>
    <packaging>pom</packaging>

    <build>
        <plugins>
            <plugin>
                <groupId>@project.groupId@</groupId>
                <artifactId>@project.artifactId@</artifactId>
                <version>@project.version@</version>
                <configuration>
                    <checksumFile>${project.build.directory}/inputs.sha256</checksumFile>
                </configuration>
                <executions>
                    <execution>
                        <id>check-codegen-inputs</id>
                        <goals>
                            <goal>check</goal>
                        </goals>
                        <configuration>
                            <fileSets>
                                <fileSet>
                                    <directory>src/main/db</directory>
                                </fileSet>
                            </fileSets>
                            <plugins>
                                <plugin>org.apache.maven.plugins:maven-antrun-plugin</plugin>
                            </plugins>
                            <property>codegen.skip</property>
                        </configuration>
                    </execution>
                    <execution>
                        <id>record-codegen-inputs</id>
                        <goals>
                            <goal>record</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <artifactId>maven-antrun-plugin</artifactId>
                <version>3.1.0</version>
                <executions>
                    <execution>
                        <id>codegen</id>
                        <phase>generate-sources</phase>
                        <goals>
                            <goal>run</goal>
                        </goals>
                        <configuration>
                            <target>
                                <mkdir dir="${project.build.directory}"/>
                                <echo file="${project.build.directory}/decisions.txt" append="true">${codegen.skip},</echo>
                            </target>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

`src/it/skip-when-unchanged/src/main/db/V1__init.sql`:

```sql
create table system (id int primary key);
```

`src/it/skip-when-unchanged/invoker.properties`:

```properties
invoker.goals.1 = process-sources
invoker.goals.2 = process-sources
invoker.goals.3 = process-sources -Dskipper.force=true
```

- [ ] **Step 3: Write the verify script**

`src/it/skip-when-unchanged/verify.groovy`:

```groovy
def decisions = new File(basedir, 'target/decisions.txt').text.tokenize(',')
assert decisions == ['false', 'true', 'false'] : "codegen saw codegen.skip=${decisions}, expected first run, unchanged, forced"

def checksum = new File(basedir, 'target/inputs.sha256').text
assert checksum ==~ /[0-9a-f]{64}\n/ : "unexpected checksum file content '${checksum}'"

return true
```

- [ ] **Step 4: Run the end-to-end test**

Run: `mvn -q verify`
Expected: BUILD SUCCESS. The invoker summary reports `Passed: 1, Failed: 0, Errors: 0, Skipped: 0`. If it fails, `target/it/skip-when-unchanged/build.log` has the Maven output of the invocations.

- [ ] **Step 5: Show that the test catches a broken plugin**

Temporarily change `String.valueOf(decision.upToDate())` in `CheckMojo` to `"false"` and run `mvn -q verify`.
Expected: the invoker build fails with `codegen saw codegen.skip=[false, false, false]`. Revert the change, then run `mvn -q verify` again: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/it
git commit -m "Add end-to-end test for skip-when-unchanged"
```

---

### Task 7: README, license and release to Maven Central

**Files:**
- Create: `README.md`
- Create: `LICENSE`
- Modify: `pom.xml` (add the `release` profile)
- Create: `.github/workflows/build.yml`
- Create: `.github/workflows/release.yml`

**Interfaces:**
- Consumes: goal and parameter names from Task 5
- Produces: usage docs for consumers; CI on every push and pull request; a tag `vX.Y.Z` publishes `X.Y.Z` to Maven Central

- [ ] **Step 1: Write `README.md`**

````markdown
# checksum-skipper-maven-plugin

Skips an expensive build step, such as code generation from a database container, while its inputs are unchanged
since the last successful run.

- `check` (default phase `initialize`) hashes the inputs and sets a property to `true` if the step is up to date.
- The expensive plugin takes that property as its `skip` parameter.
- `record` (default phase `process-sources`) stores the checksum after the step succeeded. A failed step records
  nothing, so the next build runs it again.

## Usage

```xml
<plugin>
    <groupId>de.qaware.maven</groupId>
    <artifactId>checksum-skipper-maven-plugin</artifactId>
    <version>1.0.0</version>
    <configuration>
        <checksumFile>${project.build.directory}/jooq-inputs.sha256</checksumFile>
    </configuration>
    <executions>
        <execution>
            <id>check-jooq-inputs</id>
            <goals>
                <goal>check</goal>
            </goals>
            <configuration>
                <fileSets>
                    <fileSet>
                        <directory>src/main/resources/db/migration</directory>
                    </fileSet>
                </fileSets>
                <plugins>
                    <plugin>org.testcontainers:testcontainers-jooq-codegen-maven-plugin</plugin>
                </plugins>
                <outputs>
                    <output>${project.build.directory}/generated-sources/jooq</output>
                </outputs>
                <property>jooq.codegen.skip</property>
            </configuration>
        </execution>
        <execution>
            <id>record-jooq-inputs</id>
            <goals>
                <goal>record</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

On the expensive plugin, set `<skip>${jooq.codegen.skip}</skip>`. If that plugin registers its output directory as a
source root only when it runs, add the directory with `build-helper-maven-plugin:add-source`.

## When is a step up to date?

All of these must hold:

1. `skipper.force` is not set (`mvn … -Dskipper.force` forces a run).
2. `checksumFile` exists. It lives in `target/`, so `mvn clean` forces a run.
3. Every `output` exists, and directories are not empty.
4. The checksum equals the recorded one. It covers:
   - the path and content of every file in `fileSets`,
   - version, dependencies and configuration of every plugin in `plugins`.

   The project version is not part of it, so a release bump does not rerun the step. Plugin configuration holds
   interpolated absolute paths, so moving the checkout reruns the step once.

## Parameters

| Goal | Parameter | Required | Description |
|---|---|---|---|
| check | `fileSets` | one of fileSets/plugins | Ant-style file sets; relative directories resolve against the project base directory |
| check | `plugins` | one of fileSets/plugins | `groupId:artifactId` of plugins whose setup is an input |
| check | `outputs` | no | Files or directories that must exist |
| check | `property` | yes | Project property set to `true`/`false` |
| check | `force` | no | User property `skipper.force` |
| check, record | `checksumFile` | yes | Must be the same file for both goals |

Bind `record` after the expensive step. Its default phase `process-sources` fits steps in `generate-sources`; for
steps in `generate-resources` bind it to `process-resources`.
````

- [ ] **Step 2: Add the license**

Append to `README.md`:

```markdown

## License

Copyright 2026 QAware GmbH. Licensed under the [Apache License, Version 2.0](LICENSE).
```

Run: `curl -sSfo LICENSE https://www.apache.org/licenses/LICENSE-2.0.txt && head -4 LICENSE`
Expected: the file starts with `Apache License` / `Version 2.0, January 2004`

- [ ] **Step 3: Add the `release` profile to `pom.xml`**

Maven Central requires sources, javadoc and GPG signatures. After `</build>`, add:

```xml
    <profiles>
        <profile>
            <id>release</id>
            <build>
                <plugins>
                    <plugin>
                        <artifactId>maven-source-plugin</artifactId>
                        <version>3.4.0</version>
                        <executions>
                            <execution>
                                <id>attach-sources</id>
                                <goals>
                                    <goal>jar-no-fork</goal>
                                </goals>
                            </execution>
                        </executions>
                    </plugin>
                    <plugin>
                        <artifactId>maven-javadoc-plugin</artifactId>
                        <version>3.12.0</version>
                        <executions>
                            <execution>
                                <id>attach-javadoc</id>
                                <goals>
                                    <goal>jar</goal>
                                </goals>
                            </execution>
                        </executions>
                    </plugin>
                    <plugin>
                        <artifactId>maven-gpg-plugin</artifactId>
                        <version>3.2.8</version>
                        <executions>
                            <execution>
                                <id>sign</id>
                                <phase>verify</phase>
                                <goals>
                                    <goal>sign</goal>
                                </goals>
                            </execution>
                        </executions>
                    </plugin>
                    <plugin>
                        <groupId>org.sonatype.central</groupId>
                        <artifactId>central-publishing-maven-plugin</artifactId>
                        <version>0.11.0</version>
                        <extensions>true</extensions>
                        <configuration>
                            <publishingServerId>central</publishingServerId>
                            <autoPublish>true</autoPublish>
                            <waitUntil>published</waitUntil>
                        </configuration>
                    </plugin>
                </plugins>
            </build>
        </profile>
    </profiles>
```

The GPG passphrase comes from the environment variable `MAVEN_GPG_PASSPHRASE`, which `maven-gpg-plugin` 3.2 reads by default.

- [ ] **Step 4: Write `.github/workflows/build.yml`**

The build also runs the `release` profile without signing, so a javadoc error shows up in a pull request rather than during a release.

```yaml
name: Build

on:
  push:
    branches: [main]
  pull_request:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: maven
      - run: mvn -B verify -Prelease -Dgpg.skip
```

- [ ] **Step 5: Write `.github/workflows/release.yml`**

A tag `v1.2.3` publishes version `1.2.3`. The version is only set in the CI workspace; `main` keeps its `-SNAPSHOT` version.

```yaml
name: Release

on:
  push:
    tags: ['v*']

jobs:
  release:
    runs-on: ubuntu-latest
    permissions:
      contents: read
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: maven
          server-id: central
          server-username: CENTRAL_USERNAME
          server-password: CENTRAL_TOKEN
          gpg-private-key: ${{ secrets.GPG_PRIVATE_KEY }}
          gpg-passphrase: MAVEN_GPG_PASSPHRASE
      - name: Set version from tag
        run: mvn -B org.codehaus.mojo:versions-maven-plugin:2.22.0:set -DnewVersion="${GITHUB_REF_NAME#v}" -DgenerateBackupPoms=false
      - name: Publish to Maven Central
        run: mvn -B deploy -Prelease
        env:
          CENTRAL_USERNAME: ${{ secrets.CENTRAL_USERNAME }}
          CENTRAL_TOKEN: ${{ secrets.CENTRAL_TOKEN }}
          MAVEN_GPG_PASSPHRASE: ${{ secrets.GPG_PASSPHRASE }}
```

- [ ] **Step 6: Verify the build the way CI runs it**

Run: `mvn -B clean install -Prelease -Dgpg.skip && ls target/*.jar`
Expected: BUILD SUCCESS, unit tests and the invoker test pass; `target/` holds the plugin jar plus `-sources.jar` and `-javadoc.jar`; the plugin is installed to `~/.m2/repository/de/qaware/maven/checksum-skipper-maven-plugin/1.0.0-SNAPSHOT/`

- [ ] **Step 7: Commit**

```bash
git add README.md LICENSE pom.xml .github
git commit -m "Add README, Apache 2.0 license and release to Maven Central"
```

- [ ] **Step 8: Hand over the steps outside this repo**

These need a person with the access rights, so report them rather than doing them:
- get approval to publish under QAware's name, if the QAware open-source process needs one
- create the public GitHub repo `qaware/checksum-skipper-maven-plugin` and push `main`
- on `central.sonatype.com`, make sure the account has the verified namespace `de.qaware` (it already publishes `de.qaware.maven:go-offline-maven-plugin`), and generate a user token
- add the repository secrets `CENTRAL_USERNAME` and `CENTRAL_TOKEN` (the token pair), `GPG_PRIVATE_KEY` (ASCII-armored) and `GPG_PASSPHRASE`; the public key must be on `keys.openpgp.org` or `keyserver.ubuntu.com`
- push the tag `v1.0.0`, check that `de.qaware.maven:checksum-skipper-maven-plugin:1.0.0` appears on Maven Central, then bump `main` to `1.0.1-SNAPSHOT`
