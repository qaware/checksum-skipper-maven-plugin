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
