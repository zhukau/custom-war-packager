package io.jenkins.tools.warpackager.lib.util;

import io.jenkins.tools.warpackager.lib.config.BuildSettings;
import io.jenkins.tools.warpackager.lib.config.Config;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class MavenHelperTest {

    @TempDir
    Path tempDir;

    @Mock
    Config mockConfig;

    @Mock
    BuildSettings mockBuildSettings;

    MavenHelper mavenHelper;

    private TestLogHandler testLogHandler;
    private static final Logger MAVEN_HELPER_LOGGER = Logger.getLogger(MavenHelper.class.getName());

    // Helper class to capture log records
    private static class TestLogHandler extends Handler {
        private final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {}

        @Override
        public void close() throws SecurityException {}

        public List<LogRecord> getRecords() {
            return records;
        }
    }

    @BeforeEach
    void setUp() {
        when(mockConfig.getBuildSettings()).thenReturn(mockBuildSettings);
        // Provide default behavior for buildSettings if needed, e.g., returning null for settings file
        when(mockBuildSettings.getMvnSettingsFile()).thenReturn(null);
        when(mockBuildSettings.getMvnOptions()).thenReturn(Collections.emptyList());

        mavenHelper = new MavenHelper(mockConfig);

        // Setup log capture
        testLogHandler = new TestLogHandler();
        MAVEN_HELPER_LOGGER.addHandler(testLogHandler);
        MAVEN_HELPER_LOGGER.setLevel(Level.INFO); // Ensure INFO messages are captured
    }

    @Test
    void testRunWithExistingJvmConfig_usesExistingFile() throws IOException, InterruptedException {
        Path mvnDir = tempDir.resolve(".mvn");
        Files.createDirectories(mvnDir);
        Path jvmConfig = mvnDir.resolve("jvm.config");
        String customOpts = "-Dtest.prop=existing -Xmx512m";
        Files.writeString(jvmConfig, customOpts);

        // We expect the Maven command to fail as 'mvn' might not be a real command here,
        // but we are interested in MAVEN_OPTS setup. failOnError=false
        mavenHelper.run(tempDir.toFile(), false, "clean");

        // Verify the original jvm.config was not overwritten
        assertEquals(customOpts, Files.readString(jvmConfig));

        // Verify MAVEN_OPTS in logs
        assertTrue(testLogHandler.getRecords().stream()
                .anyMatch(record -> record.getMessage().contains("Setting MAVEN_OPTS for subprocess: " + customOpts)),
                "Expected MAVEN_OPTS log message not found or incorrect.");
    }

    @Test
    void testRunWithNoJvmConfig_copiesDefaultAndUsesIt() throws IOException, InterruptedException {
        // Ensure the default jvm.config resource is available and has known content
        // For this test, we'll read what MavenHelper *would* read.
        String expectedDefaultOpts = null;
        try (InputStream defaultConfigStream = MavenHelper.class.getResourceAsStream("/mvn/jvm.config")) {
            assertNotNull(defaultConfigStream, "Default /mvn/jvm.config not found in classpath for test setup.");
            expectedDefaultOpts = new String(defaultConfigStream.readAllBytes(), StandardCharsets.UTF_8)
                                    .lines()
                                    .map(String::trim)
                                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                                    .collect(java.util.stream.Collectors.joining(" "));
        }
        assertTrue(expectedDefaultOpts != null && !expectedDefaultOpts.isEmpty(), "Default jvm.config resource is empty or only comments.");

        mavenHelper.run(tempDir.toFile(), false, "package");

        Path copiedJvmConfig = tempDir.resolve(".mvn/jvm.config");
        assertTrue(Files.exists(copiedJvmConfig), ".mvn/jvm.config was not copied");

        String copiedContent = Files.readString(copiedJvmConfig).lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .collect(java.util.stream.Collectors.joining(" "));
        assertEquals(expectedDefaultOpts, copiedContent, "Content of copied jvm.config does not match default resource.");

        // Verify MAVEN_OPTS in logs
        final String finalExpectedDefaultOpts = expectedDefaultOpts;
        assertTrue(testLogHandler.getRecords().stream()
            .anyMatch(record -> record.getMessage().contains("Setting MAVEN_OPTS for subprocess: " + finalExpectedDefaultOpts)),
            "Expected MAVEN_OPTS log message for default config not found or incorrect. Logged messages: " +
            testLogHandler.getRecords().stream().map(LogRecord::getMessage).collect(java.util.stream.Collectors.toList()));

        // Verify log message for copying
         assertTrue(testLogHandler.getRecords().stream()
            .anyMatch(record -> record.getMessage().contains("Copied default jvm.config to ")), "Log for copying default jvm.config not found");
    }

    @Test
    void testReadJvmConfig_ignoresCommentsAndEmptyLines() throws IOException, InterruptedException {
        Path mvnDir = tempDir.resolve(".mvn");
        Files.createDirectories(mvnDir);
        Path jvmConfig = mvnDir.resolve("jvm.config");
        String fileContent = "# This is a comment\n-Dprop1=val1\n\n   -Dprop2=val2 # Inline comment\n";
        Files.writeString(jvmConfig, fileContent);

        // Access private method readJvmConfig via a wrapper or by re-implementing its logic for test
        // For simplicity, assuming `run` calls it and we check MAVEN_OPTS via logs
        mavenHelper.run(tempDir.toFile(), false, "validate");

        String expectedOpts = "-Dprop1=val1 -Dprop2=val2";
        assertTrue(testLogHandler.getRecords().stream()
                .anyMatch(record -> record.getMessage().contains("Setting MAVEN_OPTS for subprocess: " + expectedOpts)),
                "MAVEN_OPTS from jvm.config with comments/empty lines not processed correctly.");
    }
}
