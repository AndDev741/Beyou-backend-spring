package beyou.beyouapp.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.routine.snapshot.XpDecayStrategy;
import beyou.beyouapp.backend.notification.preferences.NotificationPreferencesService;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.UserService;

/**
 * Two first reads of an account's preferences arriving at the same moment.
 *
 * <p>Not hypothetical: the web configuration page mounts the engagement switch and the reminder
 * settings side by side, and each reads the preferences on mount. For an account with no row yet
 * both requests see nothing and both insert. The loser used to fail on the primary key inside its
 * own transaction, which Hibernate then marks rollback-only, so the re-read that was meant to
 * rescue it answered 500 instead; the reminder settings stayed disabled on screen.
 *
 * <p>Committed rows and real threads, for the reason {@code FocusMicroTaskConcurrencyIT} gives:
 * {@link NotificationPreferencesIntegrationTest} is {@code @Transactional}, so a race cannot happen
 * there. Several rounds with several threads each, because one pair can miss each other by luck.
 */
class NotificationPreferencesConcurrencyIT extends AbstractIntegrationTest {

    private static final int ROUNDS = 8;
    private static final int THREADS = 4;

    @Autowired private NotificationPreferencesService service;
    @Autowired private UserRepository userRepository;
    @Autowired private UserService userService;
    @Autowired private TransactionTemplate transactionTemplate;

    private final List<UUID> users = new ArrayList<>();

    @AfterEach
    void tearDown() {
        // The preferences row goes with the user (V24, ON DELETE CASCADE).
        users.forEach(id -> transactionTemplate.executeWithoutResult(status ->
            userRepository.findById(id).ifPresent(userService::deleteUser)));
    }

    @Test
    @DisplayName("simultaneous first reads all get the same row, and none of them fails")
    void simultaneousFirstReadsShareOneRow() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                User user = newUser();
                users.add(user.getId());

                CountDownLatch start = new CountDownLatch(1);
                List<Future<String>> reads = new ArrayList<>();
                for (int i = 0; i < THREADS; i++) {
                    Callable<String> read = () -> {
                        start.await();
                        return service.getOrCreate(user).getUnsubscribeToken();
                    };
                    reads.add(pool.submit(read));
                }
                start.countDown();

                Set<String> tokens = new HashSet<>();
                for (Future<String> read : reads) {
                    // get() rethrows whatever a losing request threw, which is the failure itself.
                    tokens.add(read.get(30, TimeUnit.SECONDS));
                }
                assertThat(tokens).as("round %d: one row, one token", round).hasSize(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private User newUser() {
        User u = new User();
        u.setName("prefs-race");
        u.setEmail("prefs-race-" + UUID.randomUUID() + "@test.com");
        u.setPassword("password123");
        u.setGoogleAccount(false);
        u.setTimezone("UTC");
        u.setCompletedDays(new HashSet<>());
        u.setXpDecayStrategy(XpDecayStrategy.GRADUAL);
        u.setXpProgress(new XpProgress(0D, 0, 0D, 50D));
        return userRepository.saveAndFlush(u);
    }
}
