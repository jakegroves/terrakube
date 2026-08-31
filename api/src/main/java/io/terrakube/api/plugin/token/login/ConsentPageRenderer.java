package io.terrakube.api.plugin.token.login;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ConsentPageRenderer {

    private static final List<Integer> CHOICES = List.of(1, 7, 14, 30, 60, 90);

    public String renderConsent(String email, int defaultDays, int maxDays, String errorMessage) {
        StringBuilder options = new StringBuilder();
        for (int d : CHOICES) {
            if (d > maxDays) {
                continue;
            }
            options.append("<option value=\"").append(d).append("\"")
                   .append(d == defaultDays ? " selected" : "")
                   .append(">").append(d).append(d == 1 ? " day" : " days").append("</option>");
        }
        String error = errorMessage == null ? ""
            : "<p class=\"error\">" + escape(errorMessage) + "</p>";
        return page("Authorize Terrakube CLI login", """
            <h1>Terrakube CLI login</h1>
            <p>A Terraform / OpenTofu CLI is requesting access to Terrakube as
               <strong>%s</strong>.</p>
            %s
            <form method="post" action="/oauth/consent">
              <label for="days">Token duration</label>
              <select id="days" name="days">%s</select>
              <div class="actions">
                <button type="submit" name="decision" value="authorize">Authorize</button>
                <button type="submit" name="decision" value="deny" class="secondary">Deny</button>
              </div>
            </form>
            """.formatted(escape(email), error, options));
    }

    public String renderError(String message) {
        return page("Terrakube CLI login", """
            <h1>Login failed</h1>
            <p>%s</p>
            <p>You can close this window and try <code>terraform login</code> again.</p>
            """.formatted(escape(message)));
    }

    public String renderSuccess() {
        return page("Terrakube CLI login", """
            <h1>Authorized</h1>
            <p>Return to your terminal &mdash; you can close this window.</p>
            """);
    }

    private String page(String title, String body) {
        return """
            <!doctype html><html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>%s</title>
            <style>
              body{font-family:system-ui,sans-serif;max-width:34rem;margin:4rem auto;padding:0 1rem;color:#1f2430}
              h1{font-size:1.35rem}
              select,button{font-size:1rem;padding:.5rem .75rem;border-radius:.4rem}
              .actions{margin-top:1.25rem;display:flex;gap:.75rem}
              button{background:#5c4ee5;color:#fff;border:0;cursor:pointer}
              button.secondary{background:#e6e6ef;color:#1f2430}
              .error{color:#b3261e;font-weight:600}
              label{display:block;margin-bottom:.35rem;font-weight:600}
            </style></head><body>%s</body></html>
            """.formatted(escape(title), body);
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
