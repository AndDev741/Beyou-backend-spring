package beyou.beyouapp.backend.unit.task;

import beyou.beyouapp.backend.domain.task.Task;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Importance and difficulty are optional on a task and required by everything that scores or
 * stores a check. These two accessors are the single place that bridges the two, so this is
 * where the default is pinned — the snapshot serializer and the check-in service both read
 * through them and neither carries a copy of the rule.
 */
class TaskPriorityDefaultsUnitTest {

    @Test
    void unratedTaskReadsAsOne() {
        Task task = new Task();
        task.setDificulty(null);
        task.setImportance(null);

        assertEquals(1, task.effectiveDificulty());
        assertEquals(1, task.effectiveImportance());
    }

    @Test
    void ratedTaskKeepsItsOwnNumbers() {
        Task task = new Task();
        task.setDificulty(5);
        task.setImportance(3);

        assertEquals(5, task.effectiveDificulty());
        assertEquals(3, task.effectiveImportance());
    }

    @Test
    void eachFieldDefaultsOnItsOwn() {
        Task onlyDifficulty = new Task();
        onlyDifficulty.setDificulty(2);
        onlyDifficulty.setImportance(null);

        assertEquals(2, onlyDifficulty.effectiveDificulty());
        assertEquals(1, onlyDifficulty.effectiveImportance());

        Task onlyImportance = new Task();
        onlyImportance.setDificulty(null);
        onlyImportance.setImportance(4);

        assertEquals(1, onlyImportance.effectiveDificulty());
        assertEquals(4, onlyImportance.effectiveImportance());
    }
}
