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
 * failed step records nothing (the {@code check} goal already discarded the old record) and the next build runs the
 * step again. The checksum is stored whether or not the step actually ran.
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
