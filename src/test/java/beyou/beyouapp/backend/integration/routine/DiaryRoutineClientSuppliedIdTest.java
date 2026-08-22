package beyou.beyouapp.backend.integration.routine;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.CategoryService;
import beyou.beyouapp.backend.domain.category.dto.CategoryRequestDTO;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevel;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevelRepository;
import beyou.beyouapp.backend.domain.common.ExperienceLevel;
import beyou.beyouapp.backend.domain.habit.HabitService;
import beyou.beyouapp.backend.domain.habit.dto.CreateHabitDTO;
import beyou.beyouapp.backend.domain.routine.checks.HabitGroupCheck;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineResponseDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.HabitGroupDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.RoutineSectionRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.CheckGroupRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.HabitGroupRequestDTO;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * GlitchTip issue 37 — a client (the AI agent) echoes a routine back with real
 * habitGroup ids while asking for a NEW section, and the new section enters a
 * cascade = ALL collection carrying children that already exist in the database.
 */
class DiaryRoutineClientSuppliedIdTest extends AbstractIntegrationTest {

    @Autowired private DiaryRoutineService diaryRoutineService;
    @Autowired private DiaryRoutineRepository diaryRoutineRepository;
    @Autowired private CategoryService categoryService;
    @Autowired private HabitService habitService;
    @Autowired private UserRepository userRepository;
    @Autowired private XpByLevelRepository xpByLevelRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    private User user;
    private UUID habitId;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setName("Client Supplied Id IT User");
        user.setEmail("client-id-" + UUID.randomUUID() + "@test.com");
        user.setPassword("password123");
        user = userRepository.saveAndFlush(user);
        if (xpByLevelRepository.findByLevel(0) == null) xpByLevelRepository.save(new XpByLevel(0, 0));
        if (xpByLevelRepository.findByLevel(1) == null) xpByLevelRepository.save(new XpByLevel(1, 100));

        Category category = categoryService.createCategoryEntity(
                new CategoryRequestDTO("Health", "ic", null, ExperienceLevel.BEGINNER), user);
        habitId = habitService.createHabitEntity(
                new CreateHabitDTO("Read", null, null, "ic", 3, 3, List.of(category.getId()),
                        ExperienceLevel.BEGINNER),
                user.getId()).getId();
    }

    /** The reported crash: existing habitGroup id reparented into a brand-new section. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void updateMovingExistingGroupIntoNewSectionByEchoingItsId() {
        DiaryRoutineResponseDTO created = created();
        UUID routineId = created.id();
        UUID sectionId = created.routineSections().get(0).id();
        UUID habitGroupId = created.routineSections().get(0).habitGroup().get(0).id();

        DiaryRoutineResponseDTO moved = diaryRoutineService.updateDiaryRoutine(routineId,
                new DiaryRoutineRequestDTO("R", "", List.of(
                        emptyMorning(sectionId),
                        newEveningHolding(habitGroupId, null))),
                user.getId());

        assertEquals(0, section(moved, "Morning").habitGroup().size());
        assertEquals(1, section(moved, "Evening").habitGroup().size());
        assertEquals(habitId, section(moved, "Evening").habitGroup().get(0).habitId());

        transactionTemplate.executeWithoutResult(tx -> {
            DiaryRoutine routine = diaryRoutineRepository.findById(routineId).orElseThrow();
            var evening = routine.getRoutineSections().stream()
                    .filter(s -> s.getName().equals("Evening")).findFirst().orElseThrow();
            assertEquals(1, evening.getHabitGroups().size());
            UUID persistedId = evening.getHabitGroups().get(0).getId();
            System.out.println(">>> REPARENT: echoed entry id " + habitGroupId
                    + " persisted as " + persistedId);
        });

        cleanUp(routineId);
    }

    /** Check history on the moved entry: does it survive the reparent? */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void reparentingAnEntryWithCheckHistory() {
        DiaryRoutineResponseDTO created = created();
        UUID routineId = created.id();
        UUID sectionId = created.routineSections().get(0).id();
        UUID habitGroupId = created.routineSections().get(0).habitGroup().get(0).id();

        diaryRoutineService.checkAndUncheckGroup(new CheckGroupRequestDTO(
                routineId, null,
                new HabitGroupRequestDTO(habitGroupId, LocalTime.of(6, 0)),
                LocalDate.now()), user.getId());

        transactionTemplate.executeWithoutResult(tx -> {
            DiaryRoutine routine = diaryRoutineRepository.findById(routineId).orElseThrow();
            var group = routine.getRoutineSections().get(0).getHabitGroups().get(0);
            System.out.println(">>> BEFORE MOVE: checks=" + group.getHabitGroupChecks().size());
            assertEquals(1, group.getHabitGroupChecks().size(), "check-in recorded");
        });

        diaryRoutineService.updateDiaryRoutine(routineId,
                new DiaryRoutineRequestDTO("R", "", List.of(
                        emptyMorning(sectionId),
                        newEveningHolding(habitGroupId, null))),
                user.getId());

        transactionTemplate.executeWithoutResult(tx -> {
            DiaryRoutine routine = diaryRoutineRepository.findById(routineId).orElseThrow();
            var evening = routine.getRoutineSections().stream()
                    .filter(s -> s.getName().equals("Evening")).findFirst().orElseThrow();
            int checks = evening.getHabitGroups().get(0).getHabitGroupChecks().size();
            System.out.println(">>> AFTER MOVE INTO NEW SECTION: checks=" + checks);
        });

        cleanUp(routineId);
    }

    /** Client-supplied checks must not become rows: they would fabricate XP history. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void echoedChecksInNewSectionAreDropped() {
        DiaryRoutineResponseDTO created = created();
        UUID routineId = created.id();
        UUID sectionId = created.routineSections().get(0).id();

        HabitGroupCheck fabricated = new HabitGroupCheck();
        fabricated.setCheckDate(LocalDate.now());
        fabricated.setCheckTime(LocalTime.of(6, 10));
        fabricated.setChecked(true);
        fabricated.setXpGenerated(9999);

        DiaryRoutineResponseDTO updated = diaryRoutineService.updateDiaryRoutine(routineId,
                new DiaryRoutineRequestDTO("R", "", List.of(
                        emptyMorning(sectionId),
                        newEveningHolding(null, List.of(fabricated)))),
                user.getId());

        int returned = section(updated, "Evening").habitGroup().get(0).habitGroupChecks().size();
        System.out.println(">>> ECHOED CHECKS: returned=" + returned);
        assertEquals(0, returned, "client-supplied check history is not honoured");

        cleanUp(routineId);
    }

    /**
     * GlitchTip issue 37's other half, and the one the production events actually name:
     * the agent's createUserRoutine echoing back a routine it had just fetched. This
     * asserted the crash until the mapper stopped honouring client ids.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void createWithEchoedSectionAndGroupIds() {
        DiaryRoutineResponseDTO created = created();
        UUID sourceRoutineId = created.id();
        UUID sectionId = created.routineSections().get(0).id();
        UUID habitGroupId = created.routineSections().get(0).habitGroup().get(0).id();

        RoutineSectionRequestDTO echoed = new RoutineSectionRequestDTO(
                sectionId, "Morning", "ic", LocalTime.of(6, 0), LocalTime.of(9, 0),
                List.of(),
                List.of(new HabitGroupDTO(habitGroupId, habitId,
                        LocalTime.of(6, 0), LocalTime.of(6, 30), null)),
                false);

        DiaryRoutineResponseDTO copy = diaryRoutineService.createDiaryRoutine(
                new DiaryRoutineRequestDTO("Copy of R", "", List.of(echoed)), user.getId());

        assertNotEquals(sourceRoutineId, copy.id(), "a copy, not the original");
        assertNotEquals(sectionId, copy.routineSections().get(0).id(),
                "the echoed section id was ignored, not reused");
        assertNotEquals(habitGroupId, copy.routineSections().get(0).habitGroup().get(0).id(),
                "the echoed entry id was ignored, not reused");
        assertEquals(habitId, copy.routineSections().get(0).habitGroup().get(0).habitId(),
                "the habit it points at is still the one asked for");

        // The source routine is untouched: its entry keeps its own id.
        DiaryRoutineResponseDTO source = diaryRoutineService.getDiaryRoutineById(
                sourceRoutineId, user.getId());
        assertEquals(habitGroupId, source.routineSections().get(0).habitGroup().get(0).id(),
                "copying must not move the original's entry");

        cleanUp(copy.id());
        cleanUp(sourceRoutineId);
    }

    /**
     * The production signature from GlitchTip issue 37: the failure names HabitGroup,
     * not RoutineSection. Same hole, different half of the payload — a section with no
     * id is transient, so the cascade reaches an entry whose id already exists.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void createWithEchoedGroupIdButNoSectionId() {
        DiaryRoutineResponseDTO created = created();
        UUID routineId = created.id();
        UUID habitGroupId = created.routineSections().get(0).habitGroup().get(0).id();

        RoutineSectionRequestDTO echoed = new RoutineSectionRequestDTO(
                null, "Manha", "ic", LocalTime.of(6, 0), LocalTime.of(9, 0),
                List.of(),
                List.of(new HabitGroupDTO(habitGroupId, habitId,
                        LocalTime.of(6, 0), LocalTime.of(6, 30), null)),
                false);

        DiaryRoutineResponseDTO copy = diaryRoutineService.createDiaryRoutine(
                new DiaryRoutineRequestDTO("Copia", "", List.of(echoed)), user.getId());

        assertEquals(1, copy.routineSections().get(0).habitGroup().size());
        assertNotEquals(habitGroupId, copy.routineSections().get(0).habitGroup().get(0).id(),
                "the echoed entry id was ignored");

        cleanUp(copy.id());
        cleanUp(routineId);
    }

    /**
     * The mobile client, not the agent: SectionSheet stamps a client uuidv4 on a new
     * section and ItemPickerSheet stamps one on every entry it adds, and editRoutine
     * sends both (includeGroupIds: true). The id is not in the database, but Hibernate
     * decides transient-vs-detached from "is the id null", never from a lookup.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void mobileAddsNewSectionWithClientGeneratedEntryIds() {
        DiaryRoutineResponseDTO created = created();
        UUID routineId = created.id();
        UUID sectionId = created.routineSections().get(0).id();

        UUID clientSectionId = UUID.randomUUID();
        UUID clientGroupId = UUID.randomUUID();
        RoutineSectionRequestDTO clientNewSection = new RoutineSectionRequestDTO(
                clientSectionId, "Evening", "ic", LocalTime.of(20, 0), LocalTime.of(22, 0),
                List.of(),
                List.of(new HabitGroupDTO(clientGroupId, habitId,
                        LocalTime.of(20, 0), LocalTime.of(20, 30), null)),
                false);

        DiaryRoutineResponseDTO updated = diaryRoutineService.updateDiaryRoutine(routineId,
                new DiaryRoutineRequestDTO("R", "", List.of(
                        emptyMorning(sectionId), clientNewSection)),
                user.getId());

        assertEquals(1, section(updated, "Evening").habitGroup().size());
        System.out.println(">>> MOBILE client-uuid new section+entry: accepted");

        cleanUp(routineId);
    }

    /**
     * The cost of removing the crash: a payload whose section ids are garbled (the model
     * loses them, or invents them) is indistinguishable from "replace every section".
     * Today that payload 500s and nothing is lost. Measured, not assumed.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void garbledSectionIdsSilentlyRecreateTheWholeRoutine() {
        DiaryRoutineResponseDTO created = created();
        UUID routineId = created.id();
        UUID habitGroupId = created.routineSections().get(0).habitGroup().get(0).id();

        diaryRoutineService.checkAndUncheckGroup(new CheckGroupRequestDTO(
                routineId, null,
                new HabitGroupRequestDTO(habitGroupId, LocalTime.of(6, 0)),
                LocalDate.now()), user.getId());

        // Same routine, same entry, but every id replaced with a fresh one.
        RoutineSectionRequestDTO garbled = new RoutineSectionRequestDTO(
                UUID.randomUUID(), "Morning", "ic", LocalTime.of(6, 0), LocalTime.of(9, 0),
                List.of(),
                List.of(new HabitGroupDTO(UUID.randomUUID(), habitId,
                        LocalTime.of(6, 0), LocalTime.of(6, 30), null)),
                false);

        diaryRoutineService.updateDiaryRoutine(routineId,
                new DiaryRoutineRequestDTO("R", "", List.of(garbled)), user.getId());

        transactionTemplate.executeWithoutResult(tx -> {
            DiaryRoutine routine = diaryRoutineRepository.findById(routineId).orElseThrow();
            var group = routine.getRoutineSections().get(0).getHabitGroups().get(0);
            System.out.println(">>> GARBLED IDS: request succeeded, sections="
                    + routine.getRoutineSections().size()
                    + " checks=" + group.getHabitGroupChecks().size());
        });

        cleanUp(routineId);
    }

    private DiaryRoutineResponseDTO created() {
        return diaryRoutineService.createDiaryRoutine(
                new DiaryRoutineRequestDTO("R", "", List.of(new RoutineSectionRequestDTO(
                        null, "Morning", "ic", LocalTime.of(6, 0), LocalTime.of(9, 0),
                        List.of(),
                        List.of(new HabitGroupDTO(null, habitId,
                                LocalTime.of(6, 0), LocalTime.of(6, 30), null)),
                        false))),
                user);
    }

    private RoutineSectionRequestDTO emptyMorning(UUID sectionId) {
        return new RoutineSectionRequestDTO(sectionId, "Morning", "ic",
                LocalTime.of(6, 0), LocalTime.of(9, 0), List.of(), List.of(), false);
    }

    private RoutineSectionRequestDTO newEveningHolding(UUID groupId, List<HabitGroupCheck> checks) {
        return new RoutineSectionRequestDTO(null, "Evening", "ic",
                LocalTime.of(20, 0), LocalTime.of(22, 0), List.of(),
                List.of(new HabitGroupDTO(groupId, habitId,
                        LocalTime.of(20, 0), LocalTime.of(20, 30), checks)),
                false);
    }

    private DiaryRoutineResponseDTO.RoutineSectionResponseDTO section(
            DiaryRoutineResponseDTO routine, String name) {
        return routine.routineSections().stream()
                .filter(s -> s.name().equals(name)).findFirst().orElseThrow();
    }

    private void cleanUp(UUID routineId) {
        try {
            diaryRoutineService.deleteDiaryRoutine(routineId, user.getId());
        } catch (RuntimeException ignored) {
            // best effort — the assertions above are the point
        }
        try {
            habitService.deleteHabit(habitId, user.getId());
            userRepository.delete(user);
        } catch (RuntimeException ignored) {
            // ditto
        }
    }
}
