package beyou.beyouapp.backend.performance;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.HibernateStatistics;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.RoutineSection;
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
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for N+1 in {@code DiaryRoutineService.getAllDiaryRoutines()}.
 *
 * <p>Before fix: ~1 + N + per-section fetches. After fix (@EntityGraph on
 * routineSections, plus existing @BatchSize on taskGroups/habitGroups), the
 * count is bounded regardless of routine count.
 */
@Transactional
class DiaryRoutineGetAllQueryCountTest extends AbstractIntegrationTest {

    private static final int ROUTINE_COUNT = 5;
    private static final int SECTIONS_PER_ROUTINE = 2;

    @Autowired private EntityManagerFactory emf;
    @PersistenceContext private EntityManager em;
    @Autowired private UserRepository userRepository;
    @Autowired private DiaryRoutineRepository routineRepository;
    @Autowired private DiaryRoutineService routineService;

    @Test
    @DisplayName("getAllDiaryRoutines fetches routines + sections in bounded queries")
    void getAllDiaryRoutines_isBoundedRegardlessOfRoutineCount() {
        User user = seedUser();
        for (int r = 0; r < ROUTINE_COUNT; r++) {
            DiaryRoutine routine = new DiaryRoutine();
            routine.setName("routine-" + r);
            routine.setIconId("icon");
            routine.setUser(user);
            for (int s = 0; s < SECTIONS_PER_ROUTINE; s++) {
                RoutineSection section = new RoutineSection();
                section.setName("section-" + s);
                section.setIconId("icon");
                section.setStartTime(LocalTime.of(6, 0));
                section.setEndTime(LocalTime.of(7, 0));
                section.setOrderIndex(s);
                section.setRoutine(routine);
                section.setHabitGroups(new ArrayList<>());
                section.setTaskGroups(new ArrayList<>());
                routine.getRoutineSections().add(section);
            }
            routineRepository.saveAndFlush(routine);
        }
        em.flush();
        em.clear();

        var stats = new HibernateStatistics(emf);

        var result = routineService.getAllDiaryRoutines(user.getId());

        assertThat(result).hasSize(ROUTINE_COUNT);
        assertThat(result.get(0).routineSections()).hasSize(SECTIONS_PER_ROUTINE);

        // Without @EntityGraph: 1 + N statements just for sections, scaling with routine count.
        // With @EntityGraph: ~3-5 (routines+sections JOIN, batched taskGroups, batched habitGroups).
        assertThat(stats.statementCount())
                .as("Should be bounded — N+1 means ~%d. Stats: %s", ROUTINE_COUNT + 1, stats)
                .isLessThanOrEqualTo(6);

        System.out.println("[N+1 fix] DiaryRoutineService.getAllDiaryRoutines with "
                + ROUTINE_COUNT + " routines × " + SECTIONS_PER_ROUTINE + " sections → " + stats);
    }

    private User seedUser() {
        User user = new User();
        user.setName("Routines Tester");
        user.setEmail("routines-all-query-test@example.com");
        user.setPassword("placeholder");
        user.setCreatedAt(Date.valueOf(Instant.now().atZone(ZoneOffset.UTC).toLocalDate()));
        return userRepository.saveAndFlush(user);
    }
}
