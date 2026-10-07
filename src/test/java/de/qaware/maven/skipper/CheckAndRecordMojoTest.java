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
