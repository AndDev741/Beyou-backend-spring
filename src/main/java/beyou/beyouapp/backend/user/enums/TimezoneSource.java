package beyou.beyouapp.backend.user.enums;

/**
 * Where {@code users.timezone} came from, which is what makes it safe to correct.
 *
 * <p>Before this existed, {@code "UTC"} was the entity default AND a legitimate answer, so
 * nothing could tell "nobody ever chose this" from "the user picked UTC on purpose". Every
 * account was therefore born on the UTC calendar and stayed there, and no automatic fix was
 * possible without also overwriting the people who meant it.
 *
 * <p>The three values carry different policy, enforced in {@code UserService.editUser}:
 * <ul>
 *   <li>{@link #DEFAULT} — nobody has ever answered. A client-detected zone adopts over this
 *       silently, exactly once.</li>
 *   <li>{@link #DETECTED} — a client reported the device's zone at signup or on the first boot
 *       after this shipped. Not re-adopted: a laptop opened in another country must not move a
 *       travelling user's day boundary under them. A mismatch surfaces as a suggestion instead.</li>
 *   <li>{@link #EXPLICIT} — a person picked it, in Configuration or through the agent. Never
 *       changed by anything but another explicit pick.</li>
 * </ul>
 *
 * <p>The policy lives on the server rather than in the two clients on purpose: a buggy or
 * hostile client must not be able to overwrite an explicit pick.
 */
public enum TimezoneSource {
    DEFAULT,
    DETECTED,
    EXPLICIT
}
