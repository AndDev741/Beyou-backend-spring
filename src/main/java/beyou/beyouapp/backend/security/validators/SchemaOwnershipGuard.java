package beyou.beyouapp.backend.security.validators;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Refuses to start when Flyway and Hibernate would both own the schema.
 *
 * <p>Since the Flyway cutover, migrations under {@code db/migration} are the
 * only schema writer; Hibernate must stay on {@code validate} (or {@code none}).
 * A stray {@code spring.jpa.hibernate.ddl-auto=update} override — an old .env,
 * a forgotten profile — would silently mutate the Flyway-owned schema and fork
 * it outside {@code flyway_schema_history}. Like {@link E2eSafetyCheck}, this
 * bails out before Hibernate touches the datasource.
 *
 * <p>The one sanctioned exception is baseline generation
 * ({@code BaselineGeneratorTest}), which disables Flyway explicitly.
 */
@Component
@Slf4j
public class SchemaOwnershipGuard {

    private static final Set<String> SAFE_DDL_AUTO = Set.of("validate", "none");

    @Value("${spring.flyway.enabled:true}")
    private boolean flywayEnabled;

    @Value("${spring.jpa.hibernate.ddl-auto:none}")
    private String ddlAuto;

    @PostConstruct
    void verifySingleSchemaOwner() {
        if (!flywayEnabled) {
            log.warn("[SCHEMA GUARD] Flyway is disabled — Hibernate ddl-auto '{}' is unguarded. "
                    + "Only baseline generation should run like this.", ddlAuto);
            return;
        }
        if (!SAFE_DDL_AUTO.contains(ddlAuto.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException(
                    "REFUSING TO START: Flyway is enabled but spring.jpa.hibernate.ddl-auto is '"
                    + ddlAuto + "'. Two schema writers means silent drift — set ddl-auto to "
                    + "'validate' (or 'none'), or disable Flyway explicitly for baseline generation.");
        }
        log.info("[SCHEMA GUARD] Flyway owns the schema; Hibernate ddl-auto '{}' is safe.", ddlAuto);
    }
}
