package io.terrakube.api.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Locks in that the default profile requests ECS structured console output and that the "local"
 * profile neutralises it. A regression here silently reverts every deployment to unparseable
 * plain-text logs, or leaves developers with JSON in their terminal.
 *
 * <p>Asserting the property files directly (rather than booting a context) keeps this cheap and
 * matches how the other config-only guards in this change are written.
 */
class StructuredLoggingConfigTest {

    @Test
    void defaultProfileEmitsEcsJsonToConsole() throws IOException {
        String properties = Files.readString(Path.of("src/main/resources/application.properties"));

        assertThat(properties).contains("logging.structured.format.console=ecs");
    }

    @Test
    void localProfileRevertsToHumanReadableConsole() throws IOException {
        String properties = Files.readString(Path.of("src/main/resources/application-local.properties"));

        assertThat(properties).contains("logging.structured.format.console=\n");
    }
}
