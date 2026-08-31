package beyou.beyouapp.backend.user.federation;

import beyou.beyouapp.backend.user.User;

/**
 * What {@link FederatedIdentityService} decided about a verified external identity.
 *
 * <p>Sealed so the controller cannot forget a case: adding a third outcome breaks the
 * switch rather than falling through to whatever the default happened to be.
 */
public sealed interface FederationOutcome {

    /** The identity is known and belongs to this account. Issue tokens. */
    record LoggedIn(User user) implements FederationOutcome {}

    /**
     * The identity verified, but it may not enter on its own.
     *
     * <p>Two situations produce this, and the client renders the same screen for both:
     * sign in the way you already do, then link this provider from your settings.
     * The reason is carried for the message, never for a branch that lets one through.
     */
    record LinkRequired(Reason reason, String claimedEmail) implements FederationOutcome {

        public enum Reason {
            /**
             * The issuer's word on the address is not trusted here
             * ({@code trustEmailVerified: false}, or the token said {@code false}).
             * Nothing can be created from an address nobody proved.
             */
            EMAIL_NOT_TRUSTED,

            /**
             * The address is trusted and already belongs to a beyou account.
             *
             * <p>Not an error and not a merge. Linking a second door to a house is a
             * decision for whoever is already inside, so it is taken from a session that
             * proved itself the ordinary way. Doing it here instead would mean a new
             * issuer could reach every existing account by asserting its address, which
             * is exactly the property this whole table gives up email to avoid.
             */
            ACCOUNT_EXISTS
        }
    }
}
