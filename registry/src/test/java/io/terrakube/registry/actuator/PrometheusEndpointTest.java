package io.terrakube.registry.actuator;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import io.restassured.RestAssured;

/**
 * Verifies the registry serves a Prometheus scrape on /actuator/prometheus. Uses the same
 * full-context pattern as {@link io.terrakube.registry.OpenRegistryApplicationTests}; the "test"
 * profile selects LOCAL auth, which permits all requests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PrometheusEndpointTest {

    @LocalServerPort
    int port;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    void prometheusEndpointIsExposed() {
        given()
                .when().get("/actuator/prometheus")
                .then().statusCode(200)
                .body(containsString("jvm_"));
    }
}
