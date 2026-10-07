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
