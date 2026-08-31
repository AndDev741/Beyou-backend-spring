package beyou.beyouapp.backend.user;

import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.security.TokenService;
import beyou.beyouapp.backend.security.RefreshToken.RefreshTokenService;
import beyou.beyouapp.backend.user.dto.GoogleUserDTO;
import beyou.beyouapp.backend.user.federation.FederatedIdentityService;
import beyou.beyouapp.backend.user.federation.FederatedPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserServiceGoogleOAuth {

    private final TokenService tokenService;
    private final RefreshTokenService refreshTokenService;
    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final GoogleIdTokenVerifierService googleIdTokenVerifierService;
    private final FederatedIdentityService federatedIdentityService;

    /**
     * Google's {@code iss}, as it appears in an ID token. Hard-coded rather than
     * configured: it is not a deployment choice, and a configurable value here would be a
     * way to make our own rows collide with a different provider's subjects.
     */
    private static final String GOOGLE_ISSUER = "https://accounts.google.com";

    @Value("${google.secrets.clientId}")
    String GOOGLE_CLIENT_ID;
    @Value("${google.secrets.clientSecret}")
    String GOOGLE_CLIENT_SECRET;
    @Value("${frontend.url}")
    String FRONTEND_URL;
    @Value("${google.url.oauth}")
    String OAUTH_GOOGLE_URL;
    @Value("${google.url.userInfo}")
    String USER_INFO_GOOGLE_URL;

    /**
     * @param claimedTimezone the IANA zone the browser detected, or null. Applied only on
     *                        the create branch below: an existing account already has an
     *                        answer, and whether a detected zone may replace it is a
     *                        decision that belongs to {@code UserService.editUser}, which
     *                        the client reaches through the boot reconcile.
     */
    public ResponseEntity<Map<String, Object>> googleAuth(String code, String claimedTimezone,
                                                          HttpServletResponse response){
        String googleAccessToken = getOAuthAccessTokenGoogle(code);
        Map<String, String> profileDetails = getProfileDetailsFromGoogle(googleAccessToken);
        String name = profileDetails.get("name");
        String email = profileDetails.get("email");
        String perfilPhoto = profileDetails.get("picture");

        // v2 userinfo calls it "id"; it is the same value the ID token calls "sub".
        String subject = profileDetails.get("id");
        GoogleUserDTO googleUser = new GoogleUserDTO(email, name, perfilPhoto, claimedTimezone, subject);
        Optional<User> optionalUser = userRepository.findByEmail(googleUser.email());

        if(optionalUser.isPresent()){
            User user =  optionalUser.get();

            if (isUnverifiedLocalAccount(user)) {
                return unverifiedRefusal();
            }

            recordGoogleIdentity(user, googleUser);

            String jwtToken = tokenService.generateJwtToken(user);
            String refreshToken = refreshTokenService.createRefreshToken(user);

            tokenService.addJwtTokenToResponse(response, jwtToken, refreshToken);

            return ResponseEntity.ok().body(Map.of("success",  userMapper.toResponseDTO(user)));
        }else{
            User newUser = new User(googleUser);
            User user = userRepository.save(newUser);

            recordGoogleIdentity(user, googleUser);

            String jwtToken = tokenService.generateJwtToken(user);
            String refreshToken = refreshTokenService.createRefreshToken(user);

            tokenService.addJwtTokenToResponse(response, jwtToken, refreshToken);

            return ResponseEntity.ok().body(Map.of("success",  userMapper.toResponseDTO(user)));
        }
    }

    /**
     * Mobile Google sign-in: verifies the ID token (obtained on-device via
     * expo-auth-session) server-side, then find-or-creates the user and issues the
     * JWT + refresh token using the mobile contract (X-Access-Token header +
     * refreshToken in the body, no cookie).
     */
    public ResponseEntity<Map<String, Object>> googleMobileAuth(String idToken, String claimedTimezone,
                                                                HttpServletResponse response) {
        // The zone does not come from Google: a verified ID token carries no such claim,
        // so the device sends it alongside and it is merged in here.
        GoogleUserDTO googleUser = googleIdTokenVerifierService.verify(idToken)
                .withTimezone(claimedTimezone);

        User user = userRepository.findByEmail(googleUser.email())
                .orElseGet(() -> userRepository.save(new User(googleUser)));

        if (isUnverifiedLocalAccount(user)) {
            return unverifiedRefusal();
        }

        recordGoogleIdentity(user, googleUser);

        String jwtToken = tokenService.generateJwtToken(user);
        String refreshToken = refreshTokenService.createRefreshToken(user);
        tokenService.addJwtTokenToResponse(response, jwtToken, refreshToken, true);

        return ResponseEntity.ok().body(Map.of(
                "success", userMapper.toResponseDTO(user),
                "refreshToken", refreshToken));
    }

    private String getOAuthAccessTokenGoogle(String code){
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("code", code);
        params.add("redirect_uri", FRONTEND_URL);
        params.add("client_id", GOOGLE_CLIENT_ID);
        params.add("client_secret", GOOGLE_CLIENT_SECRET);
        params.add("grant_type", "authorization_code");

        HttpEntity<MultiValueMap<String, String>> requestEntity = new HttpEntity<>(params, httpHeaders);
        try{
            String response = restTemplate.postForObject(OAUTH_GOOGLE_URL, requestEntity, String.class);
            ObjectMapper mapper = new ObjectMapper();
            Map<String, String> tokenResponse = mapper.readValue(response, new TypeReference<Map<String, String>>() {});

            return tokenResponse.get("access_token");

        } catch (HttpClientErrorException e) {
            System.err.println("Error tryng to get token OAuth: " + e.getStatusCode() + " " + e.getResponseBodyAsString());
            throw new BusinessException(ErrorKey.GOOGLE_OAUTH_FAILED, "Error trying login with Google, try again");
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private Map<String, String> getProfileDetailsFromGoogle(String accessToken) {
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setBearerAuth(accessToken);

        HttpEntity<String> requestEntity = new HttpEntity<>(httpHeaders);

        ResponseEntity<String> response = restTemplate.exchange(USER_INFO_GOOGLE_URL, HttpMethod.GET, requestEntity, String.class);
        
        try{
            ObjectMapper objectMapper = new ObjectMapper();

            return objectMapper.readValue(response.getBody(), new TypeReference<Map<String, String>>() {});
        }catch (Exception e) {
            throw new RuntimeException("Failed to parse Google profile response", e);
        }
    }

    /**
     * Whether this row is a password account that never proved it owns its address.
     *
     * <p>Both Google entry points find-or-create by email, and neither used to look at
     * {@code emailVerified} — {@code UserService.doLogin} was the only reader of that flag
     * in the whole backend. So an account the password door refuses could walk in through
     * this one, and two bad things followed from that.
     *
     * <p>The mild one: Google sign-in was an accidental workaround for a lost verification
     * mail, but only for addresses Google backs, and it left the row unverified forever —
     * password login kept refusing an account the user was plainly already using.
     *
     * <p>The one that matters: anyone can register {@code victim@example.com} with a
     * password of their choosing before its owner signs up. That row sits unverified and
     * the squatter cannot log in. The owner then signs in with Google, lands inside the
     * squatter's row, and starts filling it with their data — no click, no warning. If
     * they ever follow the verification link that arrived when the squatter registered,
     * and it looks legitimate because they did just sign in, the flag flips and the
     * squatter's password opens the account.
     *
     * <p>Refusing here closes both. One rule now holds at every door: an unverified
     * password account does not authenticate. It is recoverable rather than merely strict,
     * because {@code POST /auth/resend-verification} landed alongside this and the refusal
     * below is the same {@code EMAIL_NOT_VERIFIED} contract the login screen already
     * renders the resend button on.
     *
     * <p>Rows created through Google are verified by construction, and a password account
     * that has verified may still link Google freely; neither is touched.
     */
    private boolean isUnverifiedLocalAccount(User user) {
        return !user.isGoogleAccount() && !user.isEmailVerified();
    }

    /** Byte-for-byte what {@code UserService.doLogin} returns, so both clients need one branch, not two. */
    private ResponseEntity<Map<String, Object>> unverifiedRefusal() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "EMAIL_NOT_VERIFIED"));
    }

    /**
     * Writes the {@code (accounts.google.com, sub)} row for an account that just signed in
     * through the address path.
     *
     * <p>This is the backfill, spread over time. Google predates
     * {@code federated_identities} and we never stored its subject, so there is no batch
     * to run — each account records itself on its next sign-in and resolves by the pair
     * from then on.
     *
     * <p>Never fatal. A row that fails to write costs a future lookup, not this login: the
     * address path that has always worked is still there, and it is still safe for Google
     * specifically because Google proves the addresses it asserts. Failing the sign-in
     * over bookkeeping would be a worse trade.
     */
    private void recordGoogleIdentity(User user, GoogleUserDTO googleUser) {
        try {
            federatedIdentityService.recordSeenIdentity(user, new FederatedPrincipal(
                    GOOGLE_ISSUER, googleUser.subject(), googleUser.email(),
                    true, googleUser.name(), googleUser.perfilPhoto(), null));
        } catch (Exception e) {
            System.err.println("Could not record Google federated identity: " + e.getMessage());
        }
    }
}
