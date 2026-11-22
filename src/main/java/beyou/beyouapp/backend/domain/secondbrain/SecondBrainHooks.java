package beyou.beyouapp.backend.domain.secondbrain;

import java.util.Collection;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserLanguage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * What the rest of Beyou tells Second Brain when a thing a note can be about is created or
 * deleted. Called from the one create and the one delete method of the habit, goal, routine and
 * category services, so the REST routes and the assistant's tools both pass through it, and a
 * new caller of those methods cannot forget it.
 *
 * <p>The two directions have opposite rules. A create must never be broken or slowed by its
 * question: the question is written after the create commits, in a transaction of its own,
 * with no model call, and a failure is logged and dropped. A delete must take its notes with
 * it: they move in the delete's own transaction, so a delete that rolls back leaves them where
 * they were, and one that commits never leaves notes pointing at a thing that is gone.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SecondBrainHooks {

    private final SecondBrainNotes notes;

    /**
     * A habit, goal or routine was created: ask about it on the Second Brain page, when the
     * account uses the page (see {@link SecondBrainNotes#askAboutNewThing}).
     *
     * <p>Inside a transaction, the question waits for the commit, so a create that rolls back
     * never leaves a question about a thing that does not exist. Outside one, the create has
     * already committed and the question is asked at once.
     */
    public void created(User user, SecondBrainSubjectType type, UUID thingId, String name) {
        if (user == null || user.getId() == null || thingId == null) {
            return;
        }
        UUID userId = user.getId();
        String language = UserLanguage.forPrompt(user);
        Runnable ask = () -> {
            try {
                notes.askAboutNewThing(userId, language, type, thingId, name);
            } catch (RuntimeException e) {
                log.warn("Second Brain could not ask about new {} {} for user {}: {}",
                        type, thingId, userId, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    ask.run();
                }
            });
        } else {
            ask.run();
        }
    }

    /** A habit or goal is being deleted: its notes go to its first category, or turn loose. */
    public void habitOrGoalDeleted(UUID userId, SecondBrainSubjectType type, UUID thingId, String name,
                                   Collection<Category> categories) {
        notes.thingDeleted(userId, type, thingId, name, categories);
    }

    /** A routine is being deleted: its notes turn loose, keeping its name. */
    public void routineDeleted(UUID userId, UUID routineId, String name) {
        notes.thingDeleted(userId, SecondBrainSubjectType.ROUTINE, routineId, name, null);
    }

    /** A category is being deleted: its notes, and the ones moved there, turn loose. */
    public void categoryDeleted(UUID userId, UUID categoryId, String name) {
        notes.categoryDeleted(userId, categoryId, name);
    }
}
