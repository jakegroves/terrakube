package io.terrakube.api.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Keeps {@code docs/observability.md} honest: every Micrometer meter registered in the api,
 * executor and registry main source must be listed in the catalog. If this fails, the failure
 * message names the missing meters - add them to section 1 of the doc.
 */
class SignalCatalogDriftTest {

    private static final Pattern METER = Pattern.compile(
            "(?:Counter|Gauge|Timer|Meter|DistributionSummary)\\.builder\\(\\s*\"([a-zA-Z0-9._]+)\"");

    @Test
    void everyRegisteredMeterIsDocumented() throws IOException {
        Path repoRoot = repoRoot();
        Path doc = repoRoot.resolve("docs/observability.md");
        assertThat(doc).as("docs/observability.md must exist").exists();
        String docText = Files.readString(doc);

        Set<String> meterNames = new TreeSet<>();
        for (String module : new String[] {"api", "executor", "registry"}) {
            Path src = repoRoot.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(src)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(src)) {
                files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                    try {
                        Matcher m = METER.matcher(Files.readString(p));
                        while (m.find()) {
                            meterNames.add(m.group(1));
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
        }

        assertThat(meterNames).as("no meters found - regex or source layout changed").isNotEmpty();

        Set<String> undocumented = new TreeSet<>();
        for (String name : meterNames) {
            if (!docText.contains(name)) {
                undocumented.add(name);
            }
        }

        assertThat(undocumented)
                .as("meters registered in source but missing from docs/observability.md")
                .isEmpty();
    }

    private static Path repoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("docs/observability.md"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("Could not locate repo root containing docs/observability.md");
        }
        return dir;
    }
}
