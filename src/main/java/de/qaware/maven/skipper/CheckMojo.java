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
            if (!decision.upToDate()) {
                // the step may now rewrite its outputs and fail; the old record must not outlive that
                Files.deleteIfExists(checksumFile.toPath());
            }
            warnIfDefinedInPom();
            project.getProperties().setProperty(property, String.valueOf(decision.upToDate()));
            getPluginContext().put(propertyKey(property), Boolean.TRUE);
            getPluginContext().put(contextKey(checksumFile), checksum);
            getLog().info(property + "=" + decision.upToDate() + " (" + decision.reason() + ")");
        } catch (IOException | UncheckedIOException e) {
            throw new MojoExecutionException("Could not check the inputs for " + checksumFile, e);
        }
    }

    /** Maven substitutes a property defined in the POM before this goal runs, so the skip parameter never sees ours. */
    private void warnIfDefinedInPom() {
        if (project.getProperties().containsKey(property) && !getPluginContext().containsKey(propertyKey(property))) {
            getLog().warn("The property " + property + " is defined in the POM (or a parent). Maven substitutes ${"
                    + property + "} before the check goal runs, so the skip parameter of the step will not see the"
                    + " computed value. Remove it from <properties>.");
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
