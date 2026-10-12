package beyou.beyouapp.backend.integration.notebook;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.notebook.NotebookPageService;
import beyou.beyouapp.backend.domain.notebook.dto.CreatePageRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.CreateTopicRequestDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.BlockMode;
import beyou.beyouapp.backend.domain.notebook.cycle.StudyCycleService;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.CycleConfigRequestDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.CycleDayDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.CycleTopicDTO;
import beyou.beyouapp.backend.domain.routine.snapshot.XpDecayStrategy;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;

/**
 * Two saves choosing overlapping subjects at the same moment, a topic in one cycle and a page
 * inside it in another.
 *
 * <p>The partial unique index only knows the same page twice, so nothing in the database stops
 * this pair: each save reads the other cycles' subjects, finds no overlap because the other has
 * not committed, and writes. The per-user advisory lock in {@code StudyCycleService} makes the
 * second save wait for the first and then read what it wrote.
 *
 * <p>Played in a fixed order rather than raced, so the outcome does not depend on timing: the
 * first save runs inside a transaction this test holds open, the second starts while it is open,
 * and only then does the first commit. Without the lock the second save finishes before the
 * first commits and both cycles exist. Not {@code @Transactional}, since both saves must commit
 * for real; every run makes its own user.
 */
class StudyCycleSubjectRaceIT extends AbstractIntegrationTest {

    @Autowired private StudyCycleService cycleService;
    @Autowired private NotebookPageService pageService;
    @Autowired private UserRepository userRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void aSecondSaveWaitsForTheFirstAndSeesItsSubject() throws Exception {
        User user = newUser();
        UUID root = pageService.createTopic(user, new CreateTopicRequestDTO("Software Engineer", null, null, null, null, null)).id();
        UUID area = pageService.createPage(user, new CreatePageRequestDTO(root, "Implementação", null)).id();

        CountDownLatch firstSaved = new CountDownLatch(1);
        CountDownLatch commitFirst = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                cycleService.create(user, config("Whole topic", root));
                firstSaved.countDown();
                await(commitFirst);
            }));
            assertThat(firstSaved.await(30, TimeUnit.SECONDS)).as("the first save ran").isTrue();

            Future<?> second = pool.submit(() -> cycleService.create(user, config("Areas", area)));
            // The second save is now either waiting on the lock or, without it, already done.
            Thread.sleep(500);
            commitFirst.countDown();
            first.get(30, TimeUnit.SECONDS);

            ExecutionException refused = null;
            try {
                second.get(30, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                refused = e;
            }
            assertThat(refused).as("the second save must be refused, not saved beside the first").isNotNull();
            assertThat(refused.getCause()).isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.getErrorKey()).isEqualTo(ErrorKey.STUDY_CYCLE_TOPIC_TAKEN);
                assertThat(e.getDetails()).containsEntry("topicId", area.toString())
                        .containsEntry("takenTopicId", root.toString());
            });
        } finally {
            commitFirst.countDown();
            pool.shutdownNow();
        }
        assertThat(cycleService.list(user)).singleElement().satisfies(c -> assertThat(c.name()).isEqualTo("Whole topic"));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static CycleConfigRequestDTO config(String name, UUID subject) {
        List<CycleDayDTO> days = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            days.add(new CycleDayDTO(day, 60, null));
        }
        return new CycleConfigRequestDTO(name, BlockMode.TIME, 90, days, List.of(new CycleTopicDTO(subject, 3, 3, null)));
    }

    private User newUser() {
        User u = new User();
        u.setName("cycle-race");
        u.setEmail("cycle-race-" + UUID.randomUUID() + "@test.com");
        u.setPassword("password123");
        u.setGoogleAccount(false);
        u.setTimezone("UTC");
        u.setCompletedDays(new HashSet<>());
        u.setXpDecayStrategy(XpDecayStrategy.GRADUAL);
        u.setXpProgress(new XpProgress(0D, 0, 0D, 50D));
        return userRepository.saveAndFlush(u);
    }
}
