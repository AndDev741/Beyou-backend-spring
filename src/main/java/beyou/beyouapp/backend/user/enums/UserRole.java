package beyou.beyouapp.backend.user.enums;

/**
 * Persisted authorization role for a {@link beyou.beyouapp.backend.user.User}.
 *
 * ADMIN is granted exclusively by a manual database UPDATE — there is no
 * seeding, no environment-variable promotion, no migration and no endpoint
 * that assigns it. If no code path can grant admin, no code path can be
 * abused into granting it.
 */
public enum UserRole {
    USER,
    ADMIN
}
