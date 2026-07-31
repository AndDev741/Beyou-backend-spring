package beyou.beyouapp.backend.performance;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.HibernateStatistics;
import beyou.beyouapp.backend.domain.feedback.Feedback;
import beyou.beyouapp.backend.domain.feedback.FeedbackCategory;
import beyou.beyouapp.backend.domain.feedback.FeedbackService;
import beyou.beyouapp.backend.domain.feedback.FeedbackStatus;
import beyou.beyouapp.backend.domain.feedback.dto.FeedbackAdminPageDTO;
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
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R12 — one page of the admin inbox must cost one page's worth of queries, on
 * EVERY filter combination the console can ask for.
 *
 * <p>Every row of the listing carries its submitter ({@code FeedbackAdminItemDTO}
 * has a {@code submitter}), and {@code Feedback.user} is a plain
 * {@code @ManyToOne} — EAGER by JPA default, but Hibernate does NOT turn an
 * EAGER to-one into a join for a JPQL/criteria query: it issues a secondary
 * select per row instead. A hundred-row page therefore costs a hundred extra
 * round trips unless the query fetches the submitter itself.
 *
 * <p>{@code FeedbackService.listForAdmin} branches to FOUR different repository
 * methods — status+category, status, category, and unfiltered — and each one
 * carries its own {@code JOIN FETCH}. Four independent places to lose it, so all
 * four are exercised here. Guarding only one is worse than it looks: the
 * unfiltered variant is what the console loads when it first opens, so the one
 * shape every admin hits on every visit was the shape nothing covered.
 *
 * <p>The persistence context is cleared between variants. Without that the first
 * listing leaves all ten submitters in the L1 cache and the later ones resolve
 * their {@code user} references without touching the database — an N+1 hidden by
 * the test's own warm-up.
 *
 * <p>The assertion is a count, not a comment: the only thing that keeps a JOIN
 * FETCH from being dropped in a later refactor is a test that fails when it is.
 */
@Transactional
class FeedbackAdminListingQueryCountTest extends AbstractIntegrationTest {

    /** Each submission from a DIFFERENT user — one shared submitter would hide the N+1 in the L1 cache. */
    private static final int SUBMISSION_COUNT = 10;

    /**
     * 1 for the page of rows + 1 for the Pageable's count query. The margin
     * covers incidental overhead; an N+1 would be 1 + 1 + 10.
     */
    private static final int MAX_STATEMENTS = 3;

    /** Marks the seeded rows so a stray row from another class cannot pass as one. */
    private static final String SEED_MARKER = "query count seed ";

    /**
     * A combination the rest of the suite does not create, so the two filtered
     * variants see exactly the seeded page.
     */
    private static final FeedbackStatus SEED_STATUS = FeedbackStatus.TAKING_CARE;
    private static final FeedbackCategory SEED_CATEGORY = FeedbackCategory.FEATURE_REQUEST;

    @Autowired
    private EntityManagerFactory emf;

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FeedbackService feedbackService;

    @Test
    @DisplayName("every admin listing filter combination costs a bounded number of queries, including the unfiltered one the console opens on")
    void everyListingVariantIsBoundedRegardlessOfSubmitterCount() {
        for (int i = 0; i < SUBMISSION_COUNT; i++) {
            seedSubmission(seedUser(i), i);
        }
        em.flush();

        // The seeded rows are the newest in the table, and the listing sorts
        // newest-first, so a page of exactly SUBMISSION_COUNT is exactly them —
        // which is what lets the unfiltered variant be asserted as precisely as
        // the filtered ones despite whatever else the suite has committed.
        assertListingIsBounded("status + category",
                () -> feedbackService.listForAdmin(SEED_STATUS, SEED_CATEGORY, 0, SUBMISSION_COUNT));
        assertListingIsBounded("status only",
                () -> feedbackService.listForAdmin(SEED_STATUS, null, 0, SUBMISSION_COUNT));
        assertListingIsBounded("category only",
                () -> feedbackService.listForAdmin(null, SEED_CATEGORY, 0, SUBMISSION_COUNT));
        assertListingIsBounded("unfiltered (the console's first load)",
                () -> feedbackService.listForAdmin(null, null, 0, SUBMISSION_COUNT));
    }

    /**
     * Runs one listing variant from a cold persistence context and holds it to
     * the statement budget.
     */
    private void assertListingIsBounded(String variant, java.util.function.Supplier<FeedbackAdminPageDTO> listing) {
        // Cold: no submitter may already be resident, or an N+1 costs no queries.
        em.clear();

        var stats = new HibernateStatistics(emf);
        FeedbackAdminPageDTO page = listing.get();

        assertThat(page.items())
                .as("%s — the page has to be the seeded rows for the count below to mean anything", variant)
                .hasSize(SUBMISSION_COUNT)
                .allSatisfy(item -> assertThat(item.body()).startsWith(SEED_MARKER));
        assertThat(page.items())
                .as("%s — the submitter is part of the row, so it has to come back with it", variant)
                .allSatisfy(item -> assertThat(item.submitter()).isNotNull())
                .allSatisfy(item -> assertThat(item.submitter().email()).isNotBlank());
        assertThat(page.items().stream().map(item -> item.submitter().email()).distinct().count())
                .as("%s — %d distinct submitters, so an N+1 really would be %d extra selects",
                        variant, SUBMISSION_COUNT, SUBMISSION_COUNT)
                .isEqualTo(SUBMISSION_COUNT);

        assertThat(stats.statementCount())
                .as("%s — N+1 would mean ~%d statements for %d rows. Stats: %s",
                        variant, 2 + SUBMISSION_COUNT, SUBMISSION_COUNT, stats)
                .isLessThanOrEqualTo(MAX_STATEMENTS);
    }

    // --- seed helpers ---

    private User seedUser(int index) {
        User user = new User();
        user.setName("feedback query tester " + index);
        user.setEmail("feedback-query-test-" + index + "@beyou.test");
        user.setPassword("placeholder");
        user.setCreatedAt(Date.valueOf(Instant.now().atZone(ZoneOffset.UTC).toLocalDate()));
        return userRepository.saveAndFlush(user);
    }

    private void seedSubmission(User user, int index) {
        Feedback feedback = new Feedback();
        feedback.setUser(user);
        feedback.setCategory(SEED_CATEGORY);
        feedback.setStatus(SEED_STATUS);
        feedback.setBody(SEED_MARKER + index);
        em.persist(feedback);
    }
}
