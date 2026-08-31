package beyou.beyouapp.backend.user.federation;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The external OIDC providers this deployment accepts, keyed by the slug that appears
 * in the URL ({@code POST /auth/oidc/{provider}}).
 *
 * <p>A provider absent from configuration does not exist: the controller answers 404
 * rather than 500, and no code path can reach a verifier that was never configured.
 * That is the off switch — a provider is removed by deleting its block, with no deploy
 * of new code.
 */
@Component
@ConfigurationProperties(prefix = "beyou.oidc")
@Getter
@Setter
public class OidcProviderProperties {

    private Map<String, Provider> providers = new LinkedHashMap<>();

    @Getter
    @Setter
    public static class Provider {

        /** Expected {@code iss}, compared byte-for-byte against the token's claim. */
        private String issuer;

        /** Expected {@code aud}. Our client id at that provider. */
        private String clientId;

        /**
         * Whether this issuer's {@code email_verified: true} is worth anything.
         *
         * <p><b>Default false, and that default is the safe one.</b> With it false the
         * provider can only ever be LINKED to an account that already exists and has
         * already authenticated some other way — it can never create an account, and it
         * can never claim one by matching an address.
         *
         * <p>Turning it on is a statement about the operator of that issuer, not about
         * their code: it says you believe a {@code true} there means somebody proved they
         * control the address, and that nobody can flip the column by hand. Google earns
         * it. A provider that sets the flag from a registration form does not, and one
         * that back-filled it over existing rows has said {@code true} for people who
         * never proved anything.
         *
         * <p>Even when true, an address that already belongs to a beyou account does NOT
         * auto-merge — see {@link FederatedIdentityService}.
         */
        private boolean trustEmailVerified = false;

        /** Human-readable, for logs and the settings screen. */
        private String displayName;
    }
}
