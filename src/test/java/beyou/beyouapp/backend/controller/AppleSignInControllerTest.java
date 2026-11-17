package beyou.beyouapp.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.notification.EmailService;
import beyou.beyouapp.backend.security.RefreshToken.RefreshTokenRepository;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.UserService;
import beyou.beyouapp.backend.user.apple.AppleIdTokenVerifier;
import beyou.beyouapp.backend.user.apple.AppleIdentity;
import beyou.beyouapp.backend.user.apple.AppleTokenClient;
import beyou.beyouapp.backend.user.dto.UserRegisterDTO;
import beyou.beyouapp.backend.user.federation.FederatedIdentity;
import beyou.beyouapp.backend.user.federation.FederatedIdentityRepository;
import jakarta.transaction.Transactional;

/**
 * POST /auth/apple/mobile against the real schema. The Apple token check is mocked (its own
 * tests attack it claim by claim); what is real here is the route being public, the request
 * validation, the account and link rows, and the mobile response contract.
 */
@Transactional
@AutoConfigureMockMvc
public class AppleSignInControllerTest extends AbstractIntegrationTest {

    private static final String SUB = "001234.5f2e9a.0742";
    private static final String BODY = """
            {"identityToken": "apple-id-token", "nonce": "raw-nonce", "authorizationCode": "code-1",
             "givenName": "Ana", "familyName": "Silva", "timezone": "Europe/Lisbon", "language": "pt-PT"}
            """;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private UserService userService;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private FederatedIdentityRepository federatedIdentityRepository;

    @MockitoBean private AppleIdTokenVerifier appleIdTokenVerifier;
    @MockitoBean private AppleTokenClient appleTokenClient;
    @MockitoBean private EmailService emailService;

    @BeforeEach
    void setup() {
        refreshTokenRepository.deleteAll();
        federatedIdentityRepository.deleteAll();
    }

    private void appleSays(String email, boolean relay) {
        when(appleIdTokenVerifier.verify("apple-id-token", "raw-nonce"))
                .thenReturn(new AppleIdentity(SUB, email, email != null, relay, "com.beyou.mobile"));
    }

    @Test
    public void firstSignInCreatesTheAccountAndAnswersOnTheMobileContract() throws Exception {
        appleSays("ana.apple@example.test", false);

        mockMvc.perform(post("/auth/apple/mobile").header("X-Client", "mobile")
                        .content(BODY).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Access-Token"))
                .andExpect(jsonPath("$.success.email").value("ana.apple@example.test"))
                .andExpect(jsonPath("$.success.name").value("Ana Silva"))
                .andExpect(jsonPath("$.refreshToken").exists())
                .andExpect(cookie().doesNotExist("refreshToken"));

        User created = userRepository.findByEmail("ana.apple@example.test").orElseThrow();
        assertThat(created.isEmailVerified()).isTrue();
        assertThat(created.getTimezone()).isEqualTo("Europe/Lisbon");
        Optional<FederatedIdentity> link =
                federatedIdentityRepository.findByIssuerAndSubject("https://appleid.apple.com", SUB);
        assertThat(link).isPresent();
        assertThat(link.get().getUser().getId()).isEqualTo(created.getId());
        // The key is not configured in tests, so no refresh token was kept.
        assertThat(link.get().getProviderRefreshToken()).isNull();
    }

    @Test
    public void laterSignInsResolveByTheAppleSubjectAlone() throws Exception {
        appleSays("ana.apple@example.test", false);
        mockMvc.perform(post("/auth/apple/mobile").content(BODY).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        long users = userRepository.count();

        // Apple may leave the address out of a later token; the pair is what counts.
        appleSays(null, false);
        mockMvc.perform(post("/auth/apple/mobile").content(BODY).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success.email").value("ana.apple@example.test"));

        assertThat(userRepository.count()).isEqualTo(users);
    }

    @Test
    public void aVerifiedAddressOfAnExistingAccountLinksAppleToIt() throws Exception {
        userService.registerUser(new UserRegisterDTO("Existing", "existing.apple@example.test",
                "TestPassword1!", null, null));
        User existing = userRepository.findByEmail("existing.apple@example.test").orElseThrow();
        existing.setEmailVerified(true);
        userRepository.save(existing);
        appleSays("existing.apple@example.test", false);

        mockMvc.perform(post("/auth/apple/mobile").content(BODY).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success.name").value("Existing"));

        List<FederatedIdentity> links = federatedIdentityRepository.findAllByUserId(existing.getId());
        assertThat(links).extracting(FederatedIdentity::getIssuer).containsExactly("https://appleid.apple.com");
    }

    @Test
    public void anUnverifiedPasswordAccountIsRefusedLikeTheOtherDoors() throws Exception {
        userService.registerUser(new UserRegisterDTO("Squatter", "squatted.apple@example.test",
                "TestPassword1!", null, null));
        appleSays("squatted.apple@example.test", false);

        mockMvc.perform(post("/auth/apple/mobile").content(BODY).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("EMAIL_NOT_VERIFIED"));

        User squatted = userRepository.findByEmail("squatted.apple@example.test").orElseThrow();
        assertThat(federatedIdentityRepository.findAllByUserId(squatted.getId())).isEmpty();
    }

    @Test
    public void withTheKeyConfiguredTheRefreshTokenIsKeptForRevocation() throws Exception {
        appleSays("kept.apple@example.test", true);
        when(appleTokenClient.isConfigured()).thenReturn(true);
        when(appleTokenClient.exchangeAuthorizationCode(eq("code-1"), eq("com.beyou.mobile")))
                .thenReturn(Optional.of("apple-refresh-token"));

        mockMvc.perform(post("/auth/apple/mobile").content(BODY).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        FederatedIdentity link = federatedIdentityRepository
                .findByIssuerAndSubject("https://appleid.apple.com", SUB).orElseThrow();
        assertThat(link.getProviderRefreshToken()).isEqualTo("apple-refresh-token");
        assertThat(link.getProviderClientId()).isEqualTo("com.beyou.mobile");
    }

    @Test
    public void anInvalidIdentityTokenIsRefused() throws Exception {
        when(appleIdTokenVerifier.verify(anyString(), anyString()))
                .thenThrow(new BusinessException(ErrorKey.OIDC_TOKEN_INVALID, "Invalid identity token"));

        mockMvc.perform(post("/auth/apple/mobile").content(BODY).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorKey").value("OIDC_TOKEN_INVALID"));
    }

    @Test
    public void aRequestWithoutTheNonceIsRefusedBeforeAnyCheck() throws Exception {
        mockMvc.perform(post("/auth/apple/mobile")
                        .content("{\"identityToken\": \"apple-id-token\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorKey").value("INVALID_REQUEST"));
    }
}
