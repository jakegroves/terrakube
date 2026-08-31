package io.terrakube.api.plugin.token.login;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "io.terrakube.token.login")
public class TerraformLoginProperties {

    public static final String CLIENT_ID = "terraform-cli";
    public static final int PORT_LOW = 10000;
    public static final int PORT_HIGH = 10010;

    private boolean enabled = false;
    private int defaultDays = 30;
    private int maxDays = 90;
    private String apiUrl;
    private int cleanupIntervalMs = 300000;

    @PostConstruct
    public void normalize() {
        if (maxDays > 365) maxDays = 365;
        if (maxDays < 1) maxDays = 1;
        if (defaultDays < 1) defaultDays = 1;
        if (defaultDays > maxDays) defaultDays = maxDays;
        if (enabled && (apiUrl == null || apiUrl.isBlank())) {
            throw new IllegalStateException(
                "io.terrakube.token.login.enabled=true requires io.terrakube.token.login.api-url");
        }
        if (apiUrl != null && apiUrl.endsWith("/")) {
            apiUrl = apiUrl.substring(0, apiUrl.length() - 1);
        }
    }

    public String getCallbackUrl() {
        return apiUrl + "/oauth/callback";
    }
}
