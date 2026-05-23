package beyou.beyouapp.backend.security;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Refuses to start the backend in the {@code e2e} profile unless the
 * configured datasource URL clearly points at a throwaway database.
 *
 * <p>Why: the e2e profile uses {@code ddl-auto: create-drop}. If the URL
 * accidentally points at the dev or production database, every table is
 * dropped on startup. This component bails out BEFORE Hibernate touches the
 * datasource so a misconfigured URL never gets a chance to wipe real data.
 *
 * <p>The "safe" heuristic is intentionally simple: the database name in the
 * JDBC URL must contain {@code e2e} or {@code test}. So
 * {@code jdbc:postgresql://localhost:5490/beyou_e2e} is fine; the dev URL
 * {@code jdbc:postgresql://localhost:5490/beyou} is rejected loudly.
 */
@Component
@Profile("e2e")
@Slf4j
public class E2eSafetyCheck {

    @Value("${spring.datasource.url:}")
    private String datasourceUrl;

    @PostConstruct
    void verifyDatasourceIsThrowaway() {
        String lower = datasourceUrl.toLowerCase();

        if (datasourceUrl.isBlank()) {
            throw new IllegalStateException(
                    "REFUSING TO START in e2e profile: spring.datasource.url is empty. " +
                    "Set it to a database whose name contains 'e2e' or 'test'.");
        }

        boolean isSafe = lower.contains("e2e") || lower.contains("test");
        if (!isSafe) {
            throw new IllegalStateException(
                    "REFUSING TO START in e2e profile: datasource URL '" + datasourceUrl +
                    "' does not look like a throwaway database. " +
                    "The e2e profile uses ddl-auto: create-drop, which would WIPE this database. " +
                    "Point at a database whose name contains 'e2e' or 'test' (e.g. beyou_e2e).");
        }

        log.info("[E2E SAFETY] Datasource '{}' looks safe for e2e (create-drop allowed).",
                datasourceUrl);
    }
}
