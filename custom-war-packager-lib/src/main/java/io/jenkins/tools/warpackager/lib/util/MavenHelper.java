package io.jenkins.tools.warpackager.lib.util;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.jenkins.tools.warpackager.lib.config.Config;
import io.jenkins.tools.warpackager.lib.config.DependencyInfo;
import io.jenkins.tools.warpackager.lib.config.SourceInfo;

import javax.annotation.CheckReturnValue;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author Oleg Nenashev
 */
public class MavenHelper {

    private static final String USER_HOME_PROPERTY = System.getProperty("user.home");
    private static final String USER_HOME =
            USER_HOME_PROPERTY != null && !USER_HOME_PROPERTY.isEmpty() ? USER_HOME_PROPERTY : "~";
    private static final Logger LOGGER = Logger.getLogger(MavenHelper.class.getName());

    private Config cfg;
    private static final String mvnCommand = MavenHelper.getOsSpecificMavenCommand();

    public MavenHelper(Config cfg) {
        this.cfg = cfg;
    }

    private String readJvmConfig(File buildDir) {
        File jvmConfigFile = new File(new File(buildDir, ".mvn"), "jvm.config");
        if (jvmConfigFile.isFile() && jvmConfigFile.canRead()) {
            try (Stream<String> lines = Files.lines(jvmConfigFile.toPath())) {
                String jvmOpts = lines.map(String::trim)
                                   .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                                   .collect(Collectors.joining(" "));
                if (!jvmOpts.isEmpty()) {
                    return jvmOpts;
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to read .mvn/jvm.config from " + buildDir.getAbsolutePath(), e);
            }
        }
        return null;
    }

    @SuppressFBWarnings(value = "RV_RETURN_VALUE_IGNORED", justification = "Passed parameter is true for failOnError")
    public void run(File buildDir, String ... args) throws IOException, InterruptedException {
        run(buildDir, true, args);
    }

    @SuppressFBWarnings("PA_PUBLIC_PRIMITIVE_ATTRIBUTE")
    @CheckReturnValue
    public int run(File buildDir, boolean failOnError, String ... args) throws IOException, InterruptedException {
        ArrayList<String> commandList = new ArrayList<>();
        commandList.add(MavenHelper.mvnCommand);

        if (cfg.getBuildSettings() != null) {
            File settingsFile = cfg.getBuildSettings().getMvnSettingsFile();
            if (settingsFile != null) {
                commandList.add("-s");
                commandList.add(settingsFile.getAbsolutePath());
            }
        }
        Collections.addAll(commandList, args); // Add user-provided Maven goals/phases
        if (cfg.getBuildSettings() != null) {
            cfg.getBuildSettings().getMvnOptions();
            commandList.addAll(cfg.getBuildSettings().getMvnOptions()); // Add configured Maven options
        }

        ProcessBuilder pb = new ProcessBuilder(commandList);
        pb.directory(buildDir);

        // Ensure .mvn/jvm.config exists by copying from resources if not present
        File mvnDir = new File(buildDir, ".mvn");
        File jvmConfigFileInBuildDir = new File(mvnDir, "jvm.config");
        if (!jvmConfigFileInBuildDir.exists()) {
            try (InputStream defaultConfigStream = MavenHelper.class.getResourceAsStream("/mvn/jvm.config")) {
                if (defaultConfigStream != null) {
                    if (!mvnDir.exists()) {
                        if (mvnDir.mkdirs()) {
                            LOGGER.log(Level.INFO, "Created .mvn directory in " + buildDir.getAbsolutePath());
                        } else {
                            LOGGER.log(Level.WARNING, "Failed to create .mvn directory in " + buildDir.getAbsolutePath());
                            // Continue, maybe readJvmConfig will still work or it's not critical
                        }
                    }
                    if (mvnDir.isDirectory()) { // Check if directory creation was successful or it already existed
                        Files.copy(defaultConfigStream, jvmConfigFileInBuildDir.toPath());
                        LOGGER.log(Level.INFO, "Copied default jvm.config to " + jvmConfigFileInBuildDir.getAbsolutePath());
                    } else {
                        LOGGER.log(Level.WARNING, ".mvn path exists but is not a directory in " + buildDir.getAbsolutePath());
                    }
                } else {
                    LOGGER.log(Level.WARNING, "Default /mvn/jvm.config not found in classpath resources.");
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to copy default jvm.config to " + jvmConfigFileInBuildDir.getAbsolutePath(), e);
            }
        }

        // Read .mvn/jvm.config and set MAVEN_OPTS
        String jvmConfigOpts = readJvmConfig(buildDir); // This will now read the copied or pre-existing file
        String mavenOptsToSet = null;
        if (jvmConfigOpts != null) {
            String existingMavenOpts = pb.environment().get("MAVEN_OPTS");
            if (existingMavenOpts != null && !existingMavenOpts.isEmpty()) {
                mavenOptsToSet = jvmConfigOpts + " " + existingMavenOpts;
            } else {
                mavenOptsToSet = jvmConfigOpts;
            }
        }
        // If jvmConfigOpts is null, but there are existing MAVEN_OPTS, preserve them.
        // If both are null, MAVEN_OPTS remains unset, which is fine.
        // If only jvmConfigOpts is present, it becomes the new MAVEN_OPTS.
        // If existing MAVEN_OPTS were already there and jvmConfigOpts is null, they are preserved by default.
        if (mavenOptsToSet != null) {
             pb.environment().put("MAVEN_OPTS", mavenOptsToSet);
             LOGGER.log(Level.INFO, "Setting MAVEN_OPTS for subprocess: " + mavenOptsToSet);
        }

        // It's generally good practice to inherit IO for build tools unless specific parsing is needed.
        // This makes the output visible to the user in real-time.
        pb.inheritIO();

        Process process = pb.start();
        int exitCode = process.waitFor();

        if (failOnError && exitCode != 0) {
            throw new IOException("Maven command failed with exit code " + exitCode +
                                  " in directory " + buildDir.getAbsolutePath() +
                                  ". Command: " + String.join(" ", commandList) +
                                  (mavenOptsToSet != null ? " MAVEN_OPTS=" + mavenOptsToSet : ""));
        }
        return exitCode;
    }

    private static String getOsSpecificMavenCommand() {
        String mvnCmd = "mvn";

        String osName = System.getProperty("os.name");
        if(osName != null && osName.toLowerCase().contains("windows")) {
            mvnCmd = "mvn.cmd";
        }

        return mvnCmd;
    }

    public boolean artifactExistsInLocalCache(DependencyInfo dep, String version, String packaging) {
        final String folder = "repository";
        String path = getDependencyPath(folder, dep, version, packaging);
        File expectedFile = new File(path);
        LOGGER.log(Level.INFO, "Checking {0}", expectedFile.getAbsolutePath());
        return expectedFile.exists();
    }

    private static String getDependencyPath(final String folder, final DependencyInfo dep, final String version, final String packaging) {
        return String.format("%s/.m2/%s/%s/%s/%s/%s-%s.%s",
                    USER_HOME,
                    folder,
                    dep.groupId.replaceAll("\\.", "/"),
                    dep.artifactId,
                    version,
                    dep.artifactId,
                    version,
                    packaging);
    }

    public boolean artifactExists(File buildDir, DependencyInfo dep, String version, String packaging) throws IOException, InterruptedException {
        final String path = getDependencyPath("cwp_non_hpi_cache", dep, version, packaging);
        final boolean isHpi = "hpi".equals(packaging);
        final File folder = new File(path);
        String gai = dep.groupId + ":" + dep.artifactId + ":" + version;
        if (isHpi && folder.isDirectory()) {
            final String msg = "Dependency {0} was found in the non-HPI source.  " +
                    "Delete {1} to attempt another resolution attempt.";
            LOGGER.log(Level.INFO, msg, new Object[]{gai, path});
            return false;
        }
        int res = run(buildDir, false,"dependency:get",
                "-Dartifact=" + gai,
                "-Dpackaging=" + packaging,
                "-Dtransitive=false", "-q", "-B");
        final boolean found = res == 0;
        if (isHpi && !found) {
            final String msg = "Could not download {0}, assuming it is not an HPI and creating {1} to remember the assumption.";
            LOGGER.log(Level.INFO, msg, new Object[]{gai, path});
            final boolean dirsCreated = folder.mkdirs();
            if (!dirsCreated) {
                LOGGER.log(Level.WARNING, "Unable to create the {0} folder.", folder.getAbsolutePath());
            }
        }
        return found;
    }

    public void downloadJAR(File buildDir, DependencyInfo dep, String version, File destination)
            throws IOException, InterruptedException {
        downloadArtifact(buildDir, dep, version, "jar", destination);
    }

    public List<DependencyInfo> listDependenciesFromPom(File buildDir, File pom, File destination) throws IOException, InterruptedException {
        List<DependencyInfo> dependencies = new LinkedList<>();
        LOGGER.log(Level.INFO, "Listing dependencies from POM file: {0}", pom);
        int listed = run(buildDir, true, "dependency:list", "-B", "-DoutputFile=" + destination.getAbsolutePath(),  "-DincludeScope=runtime", "-DexcludeClassifiers=tests", "-f", pom.getAbsolutePath());
        if (listed == 0) {
            try (Stream<String> stream = Files.lines(destination.toPath())) {
                stream.skip(2).filter(line -> !line.isEmpty()).forEach(line -> {
                    line = line.trim();
                    String[] dependencyData = line.split(":");
                    DependencyInfo dep = new DependencyInfo();
                    dep.groupId = dependencyData[0].trim();
                    dep.artifactId = dependencyData[1].trim();
                    dep.type = dependencyData[2].trim();
                    dep.source = new SourceInfo();
                    dep.source.version = dependencyData[3].trim();
                    dependencies.add(dep);
                });
            }
            return dependencies;
        } else {
            throw new IOException("Unable to list dependencies for pom file");
        }
    }

    public void downloadArtifact(File buildDir, DependencyInfo dep, String version, String packaging, File destination)
            throws IOException, InterruptedException {
        run(buildDir, "com.googlecode.maven-download-plugin:download-maven-plugin:1.4.0:artifact",
                "-DgroupId=" + dep.groupId,
                "-DartifactId=" + dep.artifactId,
                "-Dversion=" + version,
                "-DoutputDirectory=" + destination.getParentFile().getAbsolutePath(),
                "-DoutputFileName=" + destination.getName(),
                "-Dtype=" + packaging,
                "-q");
    }

}
