package beyou.beyouapp.backend.integration.schema;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schema parity: every index and named unique constraint declared on an
 * entity's {@code @Table} annotation must actually exist in the
 * Flyway-migrated schema.
 *
 * <p>Hibernate's {@code ddl-auto: validate} checks tables and columns but
 * ignores indexes entirely — an {@code @Index} annotation can silently be a
 * lie (the gap behind the 2026-05-23 HabitGroupCheck incident). This test
 * closes it: annotations either match the migrations or the build fails.
 */
class SchemaIndexParityTest extends AbstractIntegrationTest {

    @Autowired
    private EntityManager entityManager;

    @Test
    @SuppressWarnings("unchecked")
    void everyDeclaredIndexAndUniqueConstraintExistsInTheMigratedSchema() {
        Set<String> declared = new HashSet<>();
        entityManager.getMetamodel().getEntities().forEach(entityType -> {
            Table table = entityType.getJavaType().getAnnotation(Table.class);
            if (table == null) {
                return;
            }
            for (Index index : table.indexes()) {
                if (!index.name().isBlank()) {
                    declared.add(index.name().toLowerCase());
                }
            }
            for (UniqueConstraint constraint : table.uniqueConstraints()) {
                if (!constraint.name().isBlank()) {
                    declared.add(constraint.name().toLowerCase());
                }
            }
        });

        // Sanity-check the metamodel scan works — assert entities were found,
        // not that `declared` is populated (that would silently depend on some
        // entity keeping a named @Index).
        assertThat(entityManager.getMetamodel().getEntities()).isNotEmpty();

        // V2 indexes exist only in SQL (no @Index annotation), so enumerate them
        // explicitly — otherwise dropping one from V2 wouldn't fail this test.
        declared.addAll(List.of(
                "idx_habits_user_id", "idx_tasks_user_id", "idx_goals_user_id",
                "idx_categories_user_id", "idx_routines_user_id", "idx_refresh_tokens_user_id",
                "idx_password_reset_user_created", "idx_users_verification_token",
                "idx_users_timezone"));

        List<String> actual = entityManager
                .createNativeQuery("SELECT indexname FROM pg_indexes WHERE schemaname = 'public' "
                        + "UNION SELECT conname FROM pg_constraint "
                        + "WHERE connamespace = 'public'::regnamespace")
                .getResultList();
        Set<String> existing = new HashSet<>();
        actual.forEach(name -> existing.add(name.toLowerCase()));

        assertThat(declared)
                .as("indexes/constraints declared on entities (or load-bearing in V2) "
                        + "that are missing from the migrated schema")
                .isSubsetOf(existing);
    }
}
