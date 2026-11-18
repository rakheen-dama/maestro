package io.b2mash.maestro.integration.upgrade.rolling;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * The classpaths of every rolling-upgrade node, as the
 * {@code :maestro-integration-tests:rolling-node:rollingNodeManifest} task
 * wrote them: this build's node, and one node per previous release, all
 * compiled from the one {@code rolling-node} source set.
 *
 * <p>The {@code rollingUpgradeTest} task passes the manifest's path as the
 * system property {@value #MANIFEST_PROPERTY}; the test is not meant to run any
 * other way, so its absence fails loudly.
 *
 * @param currentClasspath  this build's node
 * @param previousVersions  the previous releases, oldest first
 * @param previousSource    where the previous releases were resolved from
 *                          (GitHub Packages, or a local Maven repository)
 * @param properties        the raw manifest
 */
record RollingNodeManifest(
        String currentClasspath,
        List<String> previousVersions,
        String previousSource,
        Properties properties
) {

    static final String MANIFEST_PROPERTY = "maestro.rolling.manifest";

    /** @return the manifest named by {@value #MANIFEST_PROPERTY} */
    static RollingNodeManifest load() {
        var path = System.getProperty(MANIFEST_PROPERTY);
        if (path == null) {
            throw new IllegalStateException("System property " + MANIFEST_PROPERTY + " is not set: run the "
                    + "rolling-upgrade test through ./gradlew :maestro-integration-tests:rollingUpgradeTest");
        }
        var properties = new Properties();
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            properties.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the rolling-node manifest " + path, e);
        }
        var versions = Arrays.stream(properties.getProperty("previous.versions", "").split(","))
                .map(String::strip)
                .filter(v -> !v.isEmpty())
                .toList();
        return new RollingNodeManifest(
                require(properties, "current.classpath"),
                versions,
                require(properties, "previous.source"),
                properties);
    }

    /**
     * @param version a previous release listed in {@link #previousVersions()}
     * @return that release's node classpath
     */
    String previousClasspath(String version) {
        return require(properties, "previous." + version + ".classpath");
    }

    private static String require(Properties properties, String key) {
        var value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("The rolling-node manifest has no " + key);
        }
        return value;
    }
}
