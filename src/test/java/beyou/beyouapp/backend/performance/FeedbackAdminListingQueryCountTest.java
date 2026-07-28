package beyou.beyouapp.backend.performance;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.HibernateStatistics;
import beyou.beyouapp.backend.domain.feedback.Feedback;
import beyou.beyouapp.backend.domain.feedback.FeedbackCategory;
import beyou.beyouapp.backend.domain.feedback.FeedbackService;
import beyou.beyouapp.backend.domain.feedback.FeedbackStatus;
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
 * R12 — one page of the admin inbox must cost one page's worth of queries.
 *
 * Every row of the listing carries its submitter ({@code FeedbackAdminItemDTO}
 * has a {@code submitter}), and {@code Feedback.user} is a plain
 * {@code @ManyToOne} — EAGER by JPA default, but Hibernate does NOT turn an
 * EAGER to-one into a join for a JPQL/criteria query: it issues a secondary
 * select per row instead. A hundred-row page therefore costs a hundred extra
 * round trips unless the query fetches the submitter itself.
 *
 * The assertion is a count, not a comment: the only thing that keeps a JOIN
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

    @Autowired
    private EntityManagerFactory emf;

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FeedbackService feedbackService;

    @Test
    @DisplayName("an admin listing page costs a bounded number of queries regardless of how many submitters it holds")
    void adminListingIsBoundedRegardlessOfSubmitterCount() {
        for (int i = 0; i < SUBMISSION_COUNT; i++) {
            seedSubmission(seedUser(i), i);
        }
        em.flush();
        em.clear();

        var stats = new HibernateStatistics(emf);

        var page = feedbackService.listForAdmin(
                FeedbackStatus.TAKING_CARE, FeedbackCategory.FEATURE_REQUEST, 0, SUBMISSION_COUNT * 2);

        assertThat(page.items()).hasSize(SUBMISSION_COUNT);
        assertThat(page.items())
                .as("the submitter is part of the row, so it has to come back with it")
                .allSatisfy(item -> assertThat(item.submitter()).isNotNull());
        assertThat(page.items().get(0).submitter().email()).isNotBlank();

        assertThat(stats.statementCount())
                .as("N+1 would mean ~%d statements for %d rows. Stats: %s",
                        2 + SUBMISSION_COUNT, SUBMISSION_COUNT, stats)
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
        // A combination the rest of the suite does not create, so a stray row
        // from another class cannot quietly widen this page.
        feedback.setCategory(FeedbackCategory.FEATURE_REQUEST);
        feedback.setStatus(FeedbackStatus.TAKING_CARE);
        feedback.setBody("query count seed " + index);
        em.persist(feedback);
    }
}
