package beyou.beyouapp.backend.performance;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.HibernateStatistics;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Date;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sanity check that the {@link HibernateStatistics} helper works against the
 * Testcontainers PostgreSQL setup. If this test fails, the rest of the N+1
 * detection suite cannot be trusted.
 */
@Transactional
class HibernateStatisticsSanityTest extends AbstractIntegrationTest {

    @Autowired
    private EntityManagerFactory emf;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("Statistics helper records exactly one statement for a single findByEmail")
    void singleFinderQuery_isExactlyOneStatement() {
        // Arrange — seed a known user
        User user = new User();
        user.setName("Sanity Test");
        user.setEmail("sanity-test@example.com");
        user.setPassword("doesnt-matter-not-validated-here");
        user.setCreatedAt(Date.valueOf(Instant.now().atZone(java.time.ZoneOffset.UTC).toLocalDate()));
        userRepository.saveAndFlush(user);

        // Clear the persistence context so the upcoming read is a true cache-miss.
        // Without this, Hibernate sees the User is already managed (we just saved it)
        // and reuses the cached instance — statementCount still increments because
        // the SQL still fires, but entityLoadCount stays at 0 because no new
        // hydration happens.
        entityManager.clear();

        // Reset counters AFTER seeding+clear so we only measure the read
        var stats = new HibernateStatistics(emf);

        // Act
        var found = userRepository.findByEmail("sanity-test@example.com");

        // Assert
        assertThat(found).isPresent();
        assertThat(stats.statementCount())
                .as("findByEmail should be exactly 1 SELECT — actual stats: %s", stats)
                .isEqualTo(1);
        assertThat(stats.entityLoadCount())
                .as("Should hydrate exactly 1 User entity — actual stats: %s", stats)
                .isEqualTo(1);
    }
}
