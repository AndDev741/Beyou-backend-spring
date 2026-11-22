package beyou.beyouapp.backend.domain.secondbrain;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.CategoryRepository;
import beyou.beyouapp.backend.domain.goal.Goal;
import beyou.beyouapp.backend.domain.goal.GoalRepository;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.habit.HabitRepository;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainMemoryGroupDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainMemorySubjectDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainNoteDTO;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import lombok.RequiredArgsConstructor;

/**
 * Every read and write on the memory notes, each in a short transaction, beside
 * {@link SecondBrainStore} and for the same reason: the service calls a model between a read
 * and a write and must not hold a transaction while it waits.
 *
 * <p>The rules that live here and nowhere else:
 * <ol>
 *   <li><b>A note is only reachable by its owner,</b> and a note is only written on a thing its
 *       owner has. Every lookup carries the owner's id.</li>
 *   <li><b>At most {@link SecondBrainNote#MAX_PER_SUBJECT} notes per thing.</b> A new one past
 *       that removes the oldest, counted under the same advisory lock as the profile.</li>
 *   <li><b>Grouping is decided here</b> ({@link #memory}): a habit or goal under its first
 *       category by name, a category under itself, routines and loose notes in a group of their
 *       own. The clients draw what they are given.</li>
 *   <li><b>A thing's name is its live name while it exists</b> and the stored one after.</li>
 * </ol>
 *
 * <p>Depends on repositories only, so the habit, goal, routine and category services can call
 * it through {@link SecondBrainHooks} without a cycle.
 */
@Component
@RequiredArgsConstructor
public class SecondBrainNotes {

    /** Habits, goals and routines an answer prompt lists for the model to file notes under, per type. */
    static final int THINGS_PER_TYPE = 20;
    /** Things whose notes the overview and follow-up prompts carry, and notes per thing. */
    static final int PROMPT_SUBJECTS = 12;
    static final int PROMPT_NOTES_PER_SUBJECT = 3;
    /** Things one readItemNotes call answers with. Each brings up to 30 notes. */
    static final int READ_SUBJECTS = 5;
    /** Names of things with notes the assistant's prompt lists, so it knows what it can read. */
    static final int CHAT_SUBJECTS = 30;

    private final SecondBrainNoteRepository noteRepository;
    private final SecondBrainQuestionRepository questionRepository;
    private final SecondBrainFactRepository factRepository;
    private final HabitRepository habitRepository;
    private final GoalRepository goalRepository;
    private final DiaryRoutineRepository routineRepository;
    private final CategoryRepository categoryRepository;
    private final SecondBrainMapper mapper;

    /** Not injected, like {@link SecondBrainStore}'s. Tests replace it to fix "now". */
    private Clock clock = Clock.systemDefaultZone();

    void setClock(Clock clock) {
        this.clock = clock;
    }

    // ---- the page ----

    /** The memory block: groups ordered by category name, the group with no category last. */
    @Transactional(readOnly = true)
    public List<SecondBrainMemoryGroupDTO> memory(UUID userId) {
        Map<UUID, List<Subject>> byGroup = new LinkedHashMap<>();
        Map<UUID, Category> groups = new HashMap<>();
        List<Subject> ungrouped = new ArrayList<>();
        for (Subject subject : subjects(userId)) {
            if (subject.group() == null) {
                ungrouped.add(subject);
            } else {
                groups.put(subject.group().getId(), subject.group());
                byGroup.computeIfAbsent(subject.group().getId(), id -> new ArrayList<>()).add(subject);
            }
        }

        List<SecondBrainMemoryGroupDTO> memory = new ArrayList<>();
        groups.values().stream()
                .sorted(Comparator.comparing((Category category) -> lower(category.getName()))
                        .thenComparing(category -> category.getId().toString()))
                .forEach(category -> memory.add(new SecondBrainMemoryGroupDTO(
                        category.getId(), category.getName(), category.getIconId(),
                        ordered(byGroup.get(category.getId())))));
        if (!ungrouped.isEmpty()) {
            memory.add(new SecondBrainMemoryGroupDTO(null, null, null, ordered(ungrouped)));
        }
        return memory;
    }

    /** Within a group: the category's own notes first, then every other thing by name. */
    private List<SecondBrainMemorySubjectDTO> ordered(List<Subject> subjects) {
        return subjects.stream()
                .sorted(Comparator.comparing((Subject subject) -> !subject.isGroupItself())
                        .thenComparing(subject -> lower(subject.name()))
                        .thenComparing(Subject::type)
                        .thenComparing(subject -> String.valueOf(subject.formerType()))
                        .thenComparing(subject -> String.valueOf(subject.id())))
                .map(this::toDTO)
                .toList();
    }

    private SecondBrainMemorySubjectDTO toDTO(Subject subject) {
        return new SecondBrainMemorySubjectDTO(subject.type(), subject.id(), subject.name(), subject.iconId(),
                subject.formerType(), subject.notes().stream().map(mapper::toDTO).toList());
    }

    /**
     * The person's corrected words. The origin stays, as it does when a profile line is
     * edited: a note the assistant wrote and the person fixed was still the assistant's.
     *
     * @throws BusinessException {@link ErrorKey#SECOND_BRAIN_NOTE_NOT_FOUND} when it is not the caller's
     */
    @Transactional
    public SecondBrainNoteDTO edit(UUID userId, UUID noteId, String text) {
        SecondBrainNote note = owned(userId, noteId);
        note.setText(SecondBrainStore.truncate(text.strip(), SecondBrainNote.TEXT_MAX));
        note.setUpdatedAt(now());
        return mapper.toDTO(note);
    }

    /** @throws BusinessException {@link ErrorKey#SECOND_BRAIN_NOTE_NOT_FOUND} when it is not the caller's */
    @Transactional
    public void delete(UUID userId, UUID noteId) {
        noteRepository.delete(owned(userId, noteId));
    }

    // ---- writing notes ----

    /**
     * One note on one of the person's things, cut to {@link SecondBrainNote#TEXT_MAX}.
     *
     * @return empty when the thing is not this user's, or the text is blank
     */
    @Transactional
    public Optional<SecondBrainNoteDTO> add(UUID userId, SecondBrainSubjectType type, UUID thingId,
                                            String text, SecondBrainNoteOrigin origin) {
        String cut = cut(text);
        Optional<SecondBrainSubject> subject = live(userId, type, thingId);
        if (cut.isEmpty() || subject.isEmpty()) {
            return Optional.empty();
        }
        lockProfile(userId);
        return write(userId, subject.get(), cut, origin, now());
    }

    /**
     * What an answer left on things, in the order given. A thing that is not the person's is
     * skipped, which is the last check on an id a model returned, and so is a note the thing
     * already holds word for word.
     *
     * @return the notes that were saved, possibly none
     */
    @Transactional
    public List<SecondBrainNoteDTO> saveLearned(UUID userId, List<LearnedNote> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        lockProfile(userId);
        Instant now = now();
        List<SecondBrainNoteDTO> saved = new ArrayList<>();
        for (LearnedNote candidate : candidates) {
            String text = cut(candidate.text());
            if (text.isEmpty()) {
                continue;
            }
            Optional<SecondBrainSubject> subject = live(userId, candidate.type(), candidate.id());
            if (subject.isEmpty()) {
                continue;
            }
            // A microsecond apart, so "oldest first" keeps the order the answer gave them in.
            write(userId, subject.get(), text, SecondBrainNoteOrigin.ANSWER,
                    now.plus(saved.size(), ChronoUnit.MICROS)).ifPresent(saved::add);
        }
        return saved;
    }

    /** Saves the note, then trims the thing back to the cap, oldest first. Empty for a duplicate. */
    private Optional<SecondBrainNoteDTO> write(UUID userId, SecondBrainSubject subject, String text,
                                               SecondBrainNoteOrigin origin, Instant at) {
        List<SecondBrainNote> existing = noteRepository
                .findByUserIdAndSubjectTypeAndSubjectIdAndFormerTypeIsNullOrderByCreatedAtAscIdAsc(
                        userId, subject.type(), subject.id());
        String key = text.toLowerCase(Locale.ROOT);
        if (existing.stream().anyMatch(note -> note.getText().toLowerCase(Locale.ROOT).equals(key))) {
            return Optional.empty();
        }
        SecondBrainNote note = new SecondBrainNote();
        note.setUserId(userId);
        note.setSubjectType(subject.type());
        note.setSubjectId(subject.id());
        note.setSubjectName(name(subject.name()));
        note.setText(text);
        note.setOrigin(origin);
        note.setCreatedAt(at);
        note.setUpdatedAt(at);
        SecondBrainNote saved = noteRepository.save(note);

        int over = existing.size() + 1 - SecondBrainNote.MAX_PER_SUBJECT;
        if (over > 0) {
            noteRepository.deleteAll(existing.subList(0, over));
        }
        return Optional.of(mapper.toDTO(saved));
    }

    // ---- NEW_THING questions ----

    /**
     * Asks about a habit, goal or routine the person just created, when the account has opened
     * the Second Brain page before (it has a question of any kind), fewer than
     * {@link SecondBrainQuestion#MAX_OPEN} are waiting, and this thing was never asked about.
     *
     * <p>Its own transaction, always: {@link SecondBrainHooks} calls it after the create has
     * committed, where joining what is left of that transaction would write nothing, and a
     * failure here must not reach the create.
     *
     * @param language what {@code UserLanguage.forPrompt} answered for the account
     * @return whether a question was asked
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean askAboutNewThing(UUID userId, String language, SecondBrainSubjectType type,
                                    UUID thingId, String name) {
        // The cheap check first and without the lock: most accounts never opened the page.
        if (!questionRepository.existsByUserId(userId)) {
            return false;
        }
        lockProfile(userId);
        if (questionRepository.countByUserIdAndStatus(userId, SecondBrainQuestionStatus.OPEN)
                >= SecondBrainQuestion.MAX_OPEN
                || questionRepository.existsByUserIdAndSubjectTypeAndSubjectId(userId, type, thingId)) {
            return false;
        }
        SecondBrainQuestion question = new SecondBrainQuestion();
        question.setUserId(userId);
        question.setKind(SecondBrainQuestionKind.NEW_THING);
        question.setText(SecondBrainQuestionKind.newThingText(type, name, language));
        question.setStatus(SecondBrainQuestionStatus.OPEN);
        question.setSubjectType(type);
        question.setSubjectId(thingId);
        question.setCreatedAt(now());
        questionRepository.save(question);
        return true;
    }

    // ---- when a thing is deleted ----

    /**
     * A habit or goal hands its notes to its first category by name, keeping its name and what
     * it was; with no category they turn LOOSE. A routine's always turn LOOSE. The open
     * NEW_THING question about it goes. Joins the caller's transaction, so the notes move only
     * if the delete commits.
     */
    @Transactional
    public void thingDeleted(UUID userId, SecondBrainSubjectType type, UUID thingId, String name,
                             Collection<Category> categories) {
        String kept = name(name);
        Category first = type == SecondBrainSubjectType.ROUTINE ? null : firstCategory(categories);
        if (first != null) {
            noteRepository.moveToCategory(userId, type, thingId, first.getId(), kept);
        } else {
            noteRepository.loosen(userId, type, thingId, kept);
        }
        questionRepository.deleteOpenNewThing(userId, type, thingId);
    }

    /**
     * A category's notes turn LOOSE: its own take its last name, and the ones moved there from a
     * deleted habit or goal keep theirs. Joins the caller's transaction.
     */
    @Transactional
    public void categoryDeleted(UUID userId, UUID categoryId, String name) {
        noteRepository.renameCategoryNotes(userId, categoryId, name(name));
        noteRepository.loosenCategory(userId, categoryId);
    }

    // ---- what the prompts and the assistant read ----

    /**
     * The person's habits, goals and routines an answer may leave notes on, at most
     * {@link #THINGS_PER_TYPE} of each, by name. Archived goals are left out: the person put
     * them away.
     */
    @Transactional(readOnly = true)
    public List<SecondBrainSubject> things(UUID userId) {
        List<SecondBrainSubject> things = new ArrayList<>();
        routineRepository.findAllByUserId(userId).stream()
                .map(routine -> new SecondBrainSubject(SecondBrainSubjectType.ROUTINE, routine.getId(), routine.getName()))
                .sorted(Comparator.comparing(thing -> lower(thing.name())))
                .limit(THINGS_PER_TYPE).forEach(things::add);
        habitRepository.findAllByUserId(userId).stream()
                .map(habit -> new SecondBrainSubject(SecondBrainSubjectType.HABIT, habit.getId(), habit.getName()))
                .sorted(Comparator.comparing(thing -> lower(thing.name())))
                .limit(THINGS_PER_TYPE).forEach(things::add);
        goalRepository.findAllByUserId(userId).orElseGet(List::of).stream()
                .filter(goal -> goal.getArchivedAt() == null)
                .map(goal -> new SecondBrainSubject(SecondBrainSubjectType.GOAL, goal.getId(), goal.getName()))
                .sorted(Comparator.comparing(thing -> lower(thing.name())))
                .limit(THINGS_PER_TYPE).forEach(things::add);
        return things;
    }

    /** The thing, if it exists and is this user's. Read-only; joins the caller's transaction. */
    @Transactional(readOnly = true)
    public Optional<SecondBrainSubject> live(UUID userId, SecondBrainSubjectType type, UUID thingId) {
        if (type == null || thingId == null) {
            return Optional.empty();
        }
        return switch (type) {
            case HABIT -> habitRepository.findById(thingId)
                    .filter(habit -> ownedBy(habit.getUser(), userId))
                    .map(habit -> new SecondBrainSubject(type, habit.getId(), habit.getName()));
            case GOAL -> goalRepository.findById(thingId)
                    .filter(goal -> ownedBy(goal.getUser(), userId))
                    .map(goal -> new SecondBrainSubject(type, goal.getId(), goal.getName()));
            case ROUTINE -> routineRepository.findById(thingId)
                    .filter(routine -> ownedBy(routine.getUser(), userId))
                    .map(routine -> new SecondBrainSubject(type, routine.getId(), routine.getName()));
            case CATEGORY -> categoryRepository.findById(thingId)
                    .filter(category -> ownedBy(category.getUser(), userId))
                    .map(category -> new SecondBrainSubject(type, category.getId(), category.getName()));
            case LOOSE -> Optional.empty();
        };
    }

    /**
     * The things whose notes the overview and follow-up prompts carry: those named in
     * {@code preferred} first (the routine items that slipped or held, the goals ahead), then
     * the most recently noted, at most {@link #PROMPT_SUBJECTS}, each with its newest
     * {@link #PROMPT_NOTES_PER_SUBJECT} notes, oldest first.
     */
    @Transactional(readOnly = true)
    List<Subject> forPrompt(UUID userId, Collection<String> preferred) {
        Set<String> wanted = new LinkedHashSet<>();
        preferred.forEach(name -> wanted.add(lower(name)));
        return subjects(userId).stream()
                .sorted(Comparator.comparing((Subject subject) -> !wanted.contains(lower(subject.name())))
                        .thenComparing(Subject::lastUpdate, Comparator.reverseOrder()))
                .limit(PROMPT_SUBJECTS)
                .map(subject -> subject.newest(PROMPT_NOTES_PER_SUBJECT))
                .toList();
    }

    /**
     * The things with notes, for the assistant's system prompt: at most {@link #CHAT_SUBJECTS}
     * names in quotes, most recently noted first, or an empty string with none. Names only: the
     * notes themselves reach a chat when it asks for them (readItemNotes), so the cost every
     * message pays is the names. Each one goes through {@link SecondBrainData#inline}, as it is
     * something the person typed.
     */
    @Transactional(readOnly = true)
    public String subjectsForChat(UUID userId) {
        return String.join(", ", subjectNames(userId, CHAT_SUBJECTS).stream()
                .map(name -> "\"" + SecondBrainData.inline(name) + "\"")
                .toList());
    }

    /**
     * The names of the things with notes, most recently noted first, at most {@code max} and
     * each once.
     */
    @Transactional(readOnly = true)
    List<String> subjectNames(UUID userId, int max) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> names = new ArrayList<>();
        subjects(userId).stream()
                .sorted(Comparator.comparing(Subject::lastUpdate, Comparator.reverseOrder()))
                .forEach(subject -> {
                    if (names.size() < max && seen.add(lower(subject.name()))) {
                        names.add(subject.name());
                    }
                });
        return names;
    }

    /**
     * The things whose name contains {@code query}, any letter case, with all their notes:
     * the live ones and the ones whose thing is gone. Most recently noted first, at most
     * {@link #READ_SUBJECTS}.
     */
    @Transactional(readOnly = true)
    public List<SecondBrainMemorySubjectDTO> readByName(UUID userId, String query) {
        String wanted = lower(query == null ? "" : query.strip());
        return subjects(userId).stream()
                .filter(subject -> lower(subject.name()).contains(wanted))
                .sorted(Comparator.comparing(Subject::lastUpdate, Comparator.reverseOrder()))
                .limit(READ_SUBJECTS)
                .map(this::toDTO)
                .toList();
    }

    // ---- turning rows into subjects ----

    /**
     * One thing with its notes, resolved against what exists now.
     *
     * @param group the category it shows under, or null for the group with none
     */
    record Subject(SecondBrainSubjectType type, UUID id, String name, String iconId,
                   SecondBrainSubjectType formerType, Category group, List<SecondBrainNote> notes) {

        Instant lastUpdate() {
            return notes.stream().map(SecondBrainNote::getUpdatedAt).max(Comparator.naturalOrder())
                    .orElse(Instant.EPOCH);
        }

        /** The category's own notes, which head its group. */
        boolean isGroupItself() {
            return type == SecondBrainSubjectType.CATEGORY && formerType == null && group != null
                    && group.getId().equals(id);
        }

        Subject newest(int count) {
            int from = Math.max(0, notes.size() - count);
            return new Subject(type, id, name, iconId, formerType, group, notes.subList(from, notes.size()));
        }
    }

    /**
     * Which notes are one subject. A live thing is its type and id, whatever name each note
     * kept. A thing that is gone is also its name: two deleted habits whose notes went to the
     * same category are two timelines, not one.
     */
    private record Key(SecondBrainSubjectType type, UUID id, SecondBrainSubjectType formerType, String goneName) {

        static Key of(SecondBrainNote note) {
            boolean gone = note.getFormerType() != null || note.getSubjectType() == SecondBrainSubjectType.LOOSE;
            return new Key(note.getSubjectType(), note.getSubjectId(), note.getFormerType(),
                    gone ? lower(note.getSubjectName()) : null);
        }
    }

    private record Live(String name, String iconId, Category group) {}

    /** Every note of the account as subjects, in the order their first note was written. */
    private List<Subject> subjects(UUID userId) {
        Map<Key, List<SecondBrainNote>> byKey = new LinkedHashMap<>();
        for (SecondBrainNote note : noteRepository.findByUserIdOrderByCreatedAtAscIdAsc(userId)) {
            byKey.computeIfAbsent(Key.of(note), key -> new ArrayList<>()).add(note);
        }
        if (byKey.isEmpty()) {
            return List.of();
        }
        Set<SecondBrainSubjectType> types = EnumSet.noneOf(SecondBrainSubjectType.class);
        byKey.keySet().forEach(key -> types.add(key.type()));

        // Only the kinds of thing that have notes are read, and each in one query.
        Map<UUID, Category> categories = new HashMap<>();
        if (types.contains(SecondBrainSubjectType.CATEGORY)) {
            categoryRepository.findAllByUserId(userId).orElseGet(ArrayList::new)
                    .forEach(category -> categories.put(category.getId(), category));
        }
        Map<UUID, Live> habits = new HashMap<>();
        if (types.contains(SecondBrainSubjectType.HABIT)) {
            for (Habit habit : habitRepository.findAllByUserId(userId)) {
                habits.put(habit.getId(), new Live(habit.getName(), habit.getIconId(), firstCategory(habit.getCategories())));
            }
        }
        Map<UUID, Live> goals = new HashMap<>();
        if (types.contains(SecondBrainSubjectType.GOAL)) {
            for (Goal goal : goalRepository.findAllByUserId(userId).orElseGet(List::of)) {
                goals.put(goal.getId(), new Live(goal.getName(), goal.getIconId(), firstCategory(goal.getCategories())));
            }
        }
        Map<UUID, Live> routines = new HashMap<>();
        if (types.contains(SecondBrainSubjectType.ROUTINE)) {
            for (DiaryRoutine routine : routineRepository.findAllByUserId(userId)) {
                routines.put(routine.getId(), new Live(routine.getName(), routine.getIconId(), null));
            }
        }

        List<Subject> subjects = new ArrayList<>();
        byKey.forEach((key, notes) -> {
            String kept = notes.get(notes.size() - 1).getSubjectName();
            Live live = switch (key.type()) {
                case HABIT -> habits.get(key.id());
                case GOAL -> goals.get(key.id());
                case ROUTINE -> routines.get(key.id());
                case CATEGORY -> {
                    Category category = categories.get(key.id());
                    if (category == null) {
                        yield null;
                    }
                    // Notes moved from a deleted thing show under the category with the thing's
                    // own name, and no icon: the icon was the thing's, and it is gone.
                    yield key.formerType() == null
                            ? new Live(category.getName(), category.getIconId(), category)
                            : new Live(kept, null, category);
                }
                case LOOSE -> null;
            };
            subjects.add(live != null
                    ? new Subject(key.type(), key.id(), live.name(), live.iconId(), key.formerType(), live.group(), notes)
                    : new Subject(key.type(), key.id(), kept, null, key.formerType(), null, notes));
        });
        return subjects;
    }

    /** The category a habit or goal shows under: the first by name, in any letter case. */
    static Category firstCategory(Collection<Category> categories) {
        if (categories == null) {
            return null;
        }
        return categories.stream()
                .filter(Objects::nonNull)
                .min(Comparator.comparing((Category category) -> lower(category.getName()))
                        .thenComparing(category -> String.valueOf(category.getId())))
                .orElse(null);
    }

    // ---- shared ----

    private SecondBrainNote owned(UUID userId, UUID noteId) {
        return noteRepository.findByIdAndUserId(noteId, userId).orElseThrow(() ->
                new BusinessException(ErrorKey.SECOND_BRAIN_NOTE_NOT_FOUND, "Note not found"));
    }

    private void lockProfile(UUID userId) {
        factRepository.lockProfile("second-brain:" + userId);
    }

    private static boolean ownedBy(User owner, UUID userId) {
        return owner != null && userId.equals(owner.getId());
    }

    private static String cut(String text) {
        return text == null ? "" : SecondBrainStore.truncate(text.strip(), SecondBrainNote.TEXT_MAX);
    }

    private static String name(String name) {
        return name == null ? "" : SecondBrainStore.truncate(name.strip(), SecondBrainNote.SUBJECT_NAME_MAX);
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    /** Microseconds, which is what a timestamptz keeps, so the value in memory is the value stored. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
