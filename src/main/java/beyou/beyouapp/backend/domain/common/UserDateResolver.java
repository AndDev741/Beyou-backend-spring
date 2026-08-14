package beyou.beyouapp.backend.domain.common;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;

import beyou.beyouapp.backend.user.User;
import lombok.extern.slf4j.Slf4j;

/**
 * Resolves "what day is it" for a given user, in that user's own timezone.
 *
 * <p>Every date written into a check row, a streak day, or a cleanup marker is permanent
 * history. Resolving those dates against the server's zone stores an off-by-one day for
 * anyone who isn't sitting in it, and permanent rows keep the error forever — hence R15:
 * every date decision in the check and cleanup paths goes through here.
 *
 * <p>Identity travels as a parameter and is never read from the security context: agent
 * tools run on a boundedElastic thread with no {@code SecurityContext}, and the scheduler
 * has no request at all. Callers pull the {@link User} off the data they already hold
 * (the routine's owner, the task's owner) and pass it in.
 *
 * <p>A static utility rather than a bean, mirroring {@link CheckXpCalculator}: it keeps
 * the constructors of the four services that need it untouched.
 */
@Slf4j
public final class UserDateResolver {

    private UserDateResolver() {}

    /** Today's date in the owner's timezone, falling back to the server zone. */
    public static LocalDate today(User user) {
        return LocalDate.now(zoneOf(user));
    }

    /**
     * Today's date in the owner's timezone against a supplied clock. The clock's own zone is
     * ignored — only its instant matters. Exists so timezone behaviour is testable without
     * waiting for the wall clock to reach an interesting hour.
     */
    public static LocalDate today(User user, Clock clock) {
        return LocalDate.now(clock.withZone(zoneOf(user)));
    }

    /**
     * The owner's timezone, or the server's when the user is missing, has no timezone stored,
     * or has one the JVM can't parse. Falling back beats throwing: a bad timezone string must
     * not block a check-in.
     */
    public static ZoneId zoneOf(User user) {
        String timezone = user != null ? user.getTimezone() : null;
        if (timezone == null || timezone.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(timezone);
        } catch (DateTimeException e) {
            log.warn("Unparseable timezone '{}' on user {} — falling back to the server zone {}",
                    timezone, user.getId(), ZoneId.systemDefault());
            return ZoneId.systemDefault();
        }
    }
}
