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
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Calculates a checksum over the configured inputs and sets {@link #property} to {@code true} if the inputs are
 * unchanged since the last run recorded by the {@code record} goal and all outputs exist, else to {@code false}.
 * When the step is not up to date, the old record is deleted, so a step that fails half-way is never mistaken for
 * up to date, not even after the inputs are reverted.
 * Pass the property to the skip parameter of the expensive plugin.
 */
@Mojo(name = "check", defaultPhase = LifecyclePhase.INITIALIZE, threadSafe = true)
public class CheckMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    @Parameter(defaultValue = "${session.userProperties}", readonly = true, required = true)
    Properties userProperties;

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
        verifyPropertyNotPreset();
        Map<String, String> values = new TreeMap<>();
        for (String plugin : plugins) {
            values.put("plugin:" + plugin, PluginFingerprint.of(project, plugin));
        }
        try {
            String checksum = ChecksumCalculator.calculate(
                    InputFiles.resolve(project.getBasedir().toPath(), fileSets), values);
            Decision decision = Decision.decide(checksum, ChecksumFile.read(checksumFile.toPath()),
                    outputs.stream().map(File::toPath).toList(), force);
            if (!decision.upToDate()) {
                deleteRecord();
            }
            project.getProperties().setProperty(property, String.valueOf(decision.upToDate()));
            getPluginContext().put(propertyKey(property), Boolean.TRUE);
            getPluginContext().put(contextKey(checksumFile), checksum);
            getLog().info(property + "=" + decision.upToDate() + " (" + decision.reason() + ")");
        } catch (IOException | UncheckedIOException e) {
            throw new MojoExecutionException("Could not check the inputs for " + checksumFile, e);
        }
    }

    /**
     * Maven substitutes a property known at model building time before this goal runs, so the skip parameter of the
     * step never sees the computed value. A -D user property is a deliberate override and only warned about.
     */
    private void verifyPropertyNotPreset() throws MojoExecutionException {
        if (userProperties.containsKey(property)) {
            getLog().warn("The property " + property + " is set on the command line. Maven substitutes ${" + property
                    + "} before the check goal runs, so the skip parameter of the step uses the command-line value.");
        } else if (project.getProperties().containsKey(property)
                && !getPluginContext().containsKey(propertyKey(property))) {
            throw new MojoExecutionException("The property " + property + " is already defined, in the <properties>"
                    + " of this POM, a parent or a settings.xml profile. Maven substitutes ${" + property
                    + "} before the check goal runs, so the skip parameter of the step would never see the computed"
                    + " value. Remove the definition.");
        }
    }

    /** The step may now rewrite its outputs and fail; the old record must not outlive that. */
    private void deleteRecord() {
        try {
            Files.deleteIfExists(checksumFile.toPath());
        } catch (IOException e) {
            getLog().warn("Could not delete the old record " + checksumFile + "; if the step fails, the next build"
                    + " may consider it up to date", e);
        }
    }

    private static String propertyKey(String property) {
        return "skipper:property:" + property;
    }

    /**
     * @return the plugin context key under which check hands the checksum to record, equal for equivalent paths
     */
    static String contextKey(File checksumFile) {
        return "skipper:" + checksumFile.getAbsoluteFile().toPath().normalize();
    }
}
