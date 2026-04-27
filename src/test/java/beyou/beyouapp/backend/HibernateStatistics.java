package beyou.beyouapp.backend;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;

/**
 * Test helper for detecting N+1 queries via Hibernate's built-in statistics.
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * @Autowired EntityManagerFactory emf;
 *
 * @Test
 * void categories_shouldNotHaveNPlusOne() {
 *     seedCategories(10);
 *     var stats = new HibernateStatistics(emf); // resets counters
 *
 *     categoryService.getAllCategories(userId);
 *
 *     assertThat(stats.statementCount())
 *         .as("Should fetch 10 categories in 1-2 queries, not 11")
 *         .isLessThanOrEqualTo(2);
 * }
 * }</pre>
 *
 * <p><b>Counter cheat sheet:</b>
 * <ul>
 *   <li>{@code statementCount()} — every JDBC statement sent to the DB.
 *       <b>Primary signal for N+1.</b></li>
 *   <li>{@code entityLoadCount()} — number of entities hydrated. Many entities
 *       loaded from few statements is healthy (one big query). Many entities
 *       from many statements is the N+1 signature.</li>
 *   <li>{@code collectionFetchCount()} — separate {@code @OneToMany} fetches.
 *       Each one is a round trip unless batched via {@code @BatchSize}.</li>
 *   <li>{@code queryExecutionCount()} — JPQL/HQL/Criteria queries. Lower than
 *       statement count means lazy loads are happening behind the scenes.</li>
 * </ul>
 */
public class HibernateStatistics {

    private final Statistics stats;

    /**
     * Creates a snapshot scoped to the calling test. Counters are reset on
     * construction so callers see only the queries triggered after this point.
     */
    public HibernateStatistics(EntityManagerFactory emf) {
        this.stats = emf.unwrap(SessionFactory.class).getStatistics();
        this.stats.setStatisticsEnabled(true);
        this.stats.clear();
    }

    /** Number of JDBC prepared statements sent to the database. Primary N+1 signal. */
    public long statementCount() {
        return stats.getPrepareStatementCount();
    }

    /** Number of entities hydrated from result sets. */
    public long entityLoadCount() {
        return stats.getEntityLoadCount();
    }

    /** Number of {@code @OneToMany}/{@code @ManyToMany} collection fetches. */
    public long collectionFetchCount() {
        return stats.getCollectionFetchCount();
    }

    /** Number of JPQL/HQL/Criteria queries executed. */
    public long queryExecutionCount() {
        return stats.getQueryExecutionCount();
    }

    /** Diagnostic dump for failure messages. */
    @Override
    public String toString() {
        return String.format(
                "statements=%d, entityLoads=%d, collectionFetches=%d, queries=%d",
                statementCount(), entityLoadCount(), collectionFetchCount(), queryExecutionCount());
    }
}
