package beyou.beyouapp.backend.integration.quicknote;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.quicknote.QuickNote;
import beyou.beyouapp.backend.domain.quicknote.QuickNoteRepository;
import beyou.beyouapp.backend.domain.quicknote.QuickNoteService;
import beyou.beyouapp.backend.domain.quicknote.dto.QuickNoteCreateRequestDTO;
import beyou.beyouapp.backend.domain.quicknote.dto.QuickNoteResponseDTO;
import beyou.beyouapp.backend.domain.routine.snapshot.XpDecayStrategy;
import beyou.beyouapp.backend.domain.task.Task;
import beyou.beyouapp.backend.domain.task.TaskRepository;
import beyou.beyouapp.backend.domain.task.dto.CreateTaskRequestDTO;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.UserService;

/**
 * The two Ideas-board writes that race in real use, with committed rows and real threads.
 *
 * <p>Not a case in {@link QuickNoteServiceIT}: that class is {@code @Transactional}, so everything
 * in it shares one transaction and nothing can race.
 */
class QuickNoteConcurrencyIT extends AbstractIntegrationTest {

    private static final int ROUNDS = 10;

    @Autowired private QuickNoteService service;
    @Autowired private QuickNoteRepository noteRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserService userService;
    @Autowired private TransactionTemplate transactionTemplate;

    private User user;

    @BeforeEach
    void seed() {
        User u = new User();
        u.setName("ideas-race");
        u.setEmail("ideas-race-" + UUID.randomUUID() + "@test.com");
        u.setPassword("password123");
        u.setGoogleAccount(false);
        u.setTimezone("UTC");
        u.setCompletedDays(new HashSet<>());
        u.setXpDecayStrategy(XpDecayStrategy.GRADUAL);
        u.setXpProgress(new XpProgress(0D, 0, 0D, 50D));
        user = userRepository.saveAndFlush(u);
    }

    @AfterEach
    void tearDown() {
        // Notes go with the user (V42, ON DELETE CASCADE); tasks with the user's JPA cascade, and
        // the notes' link to them nulls on the way.
        transactionTemplate.executeWithoutResult(status ->
            userRepository.findById(user.getId()).ifPresent(userService::deleteUser));
    }

    /**
     * The first conversion is held open, uncommitted, while the second one runs. Without the row
     * lock the second reads "not converted yet" and makes a second task; with it, it waits for the
     * first to commit and then answers the conflict. The pause only widens the window the unlocked
     * code would fall into; the locked code waits however long it is.
     */
    @Test
    @DisplayName("a double submit of 'make it a task' makes one task and one conflict")
    void aDoubleSubmitMakesOneTask() throws Exception {
        UUID noteId = service.create(user, new QuickNoteCreateRequestDTO("book the dentist", null, null, null)).id();
        CreateTaskRequestDTO request = new CreateTaskRequestDTO("book the dentist", null, "lucide:check",
                null, null, null, true);

        CountDownLatch firstConverted = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<UUID> first = pool.submit(() -> transactionTemplate.execute(status -> {
                UUID taskId = service.convertToTask(user, noteId, request).taskId();
                firstConverted.countDown();
                try {
                    secondStarted.await(10, TimeUnit.SECONDS);
                    Thread.sleep(700);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return taskId;
            }));
            Future<Throwable> second = pool.submit(() -> {
                firstConverted.await(10, TimeUnit.SECONDS);
                secondStarted.countDown();
                try {
                    service.convertToTask(user, noteId, request);
                    return null;
                } catch (Throwable e) {
                    return e;
                }
            });

            UUID taskId = first.get(30, TimeUnit.SECONDS);
            Throwable refused = second.get(30, TimeUnit.SECONDS);

            assertThat(refused).as("the second submit must be refused, not served a second task")
                    .isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) refused).getErrorKey()).isEqualTo(ErrorKey.QUICK_NOTE_ALREADY_CONVERTED);
            assertThat(tasks()).extracting(Task::getId).containsExactly(taskId);
            assertThat(noteRepository.findById(noteId).orElseThrow().getConvertedTaskId()).isEqualTo(taskId);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * A double Enter on the capture field. Each create reads the board and picks the first free
     * cell; two at once would both see the same board and stack on the same cell. Several rounds,
     * because one pair of threads can miss each other by luck.
     */
    @Test
    @DisplayName("two notes created at once land on different cells")
    void twoCreatesAtOnceLandOnDifferentCells() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String text = "idea " + round;
            race(() -> service.create(user, new QuickNoteCreateRequestDTO(text, null, null, null)));
        }

        List<QuickNote> board = noteRepository.findByUserIdAndArchivedAtIsNullOrderByZAscCreatedAtAsc(user.getId());
        assertThat(board).hasSize(2 * ROUNDS);
        Set<String> spots = new HashSet<>();
        board.forEach(note -> spots.add(note.getX() + "," + note.getY()));
        assertThat(spots).as("every note on its own cell").hasSize(2 * ROUNDS);
        assertThat(board).extracting(QuickNote::getZ).as("and its own place in the stack").doesNotHaveDuplicates();
    }

    // ---- helpers ----

    private List<Task> tasks() {
        return taskRepository.findAllByUserId(user.getId()).orElse(List.of());
    }

    /** Runs the call on two threads released together. A thread that throws fails the test with it. */
    private List<QuickNoteResponseDTO> race(Callable<QuickNoteResponseDTO> call) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<QuickNoteResponseDTO>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return call.call();
                    } catch (Throwable e) {
                        failures.add(e);
                        throw e;
                    }
                }));
            }
            start.countDown();
            List<QuickNoteResponseDTO> results = new ArrayList<>();
            for (Future<QuickNoteResponseDTO> f : futures) {
                try {
                    results.add(f.get(30, TimeUnit.SECONDS));
                } catch (java.util.concurrent.ExecutionException ignored) {
                    // Reported through `failures` below, with the original exception.
                }
            }
            assertThat(failures).as("neither of two concurrent creates may fail").isEmpty();
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
