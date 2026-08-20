package beyou.beyouapp.backend.security;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.security.RefreshToken.RefreshTokenRepository;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.UserService;
import beyou.beyouapp.backend.user.dto.UserRegisterDTO;
import beyou.beyouapp.backend.user.enums.UserRole;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R20 — the persisted user role governs authorization, and the admin console
 * route is reachable only by an admin.
 *
 * Admin is granted exclusively by a manual database UPDATE (KD10): nothing here
 * seeds, promotes or exposes an endpoint that grants the role. The tests below
 * set the role directly on a persisted row, which is exactly what a DBA would do.
 */
@AutoConfigureMockMvc
public class AdminAuthorizationTest extends AbstractIntegrationTest {

    private static final String ADMIN_ROUTE = "/feedback/admin/items";
    private static final String EMAIL = "admin-authz-test@beyou.test";
    private static final String PASSWORD = "TestPassword1!";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    UserService userService;

    @Autowired
    RefreshTokenRepository refreshTokenRepository;

    @BeforeEach
    void setUp() {
        userRepository.findByEmail(EMAIL).ifPresent(existing -> {
            refreshTokenRepository.deleteAll(refreshTokenRepository.findAllByUserId(existing.getId()));
            userRepository.delete(existing);
        });
        userService.registerUser(new UserRegisterDTO("authz test", EMAIL, PASSWORD, null));

        User user = userRepository.findByEmail(EMAIL).orElseThrow();
        user.setEmailVerified(true);
        userRepository.save(user);
    }

    @Test
    @DisplayName("a user stored with the ordinary role receives only the ordinary authority")
    void ordinaryRoleYieldsOnlyUserAuthority() {
        User user = new User();
        user.setUserRole(UserRole.USER);

        assertThat(authorityNames(user)).containsExactly("ROLE_USER");
    }

    @Test
    @DisplayName("a user stored with the admin role receives the admin authority")
    void adminRoleYieldsAdminAuthority() {
        User user = new User();
        user.setUserRole(UserRole.ADMIN);

        assertThat(authorityNames(user)).containsExactly("ROLE_ADMIN");
    }

    @Test
    @DisplayName("a user row with a null role still authenticates as an ordinary user")
    void nullRoleFallsBackToUserAuthority() {
        User user = new User();
        user.setUserRole(null);

        assertThat(authorityNames(user)).containsExactly("ROLE_USER");
    }

    @Test
    @DisplayName("AE6: a signed-in non-admin is refused the admin route")
    void signedInNonAdminIsRefusedTheAdminRoute() throws Exception {
        mockMvc.perform(get(ADMIN_ROUTE)
                        .header("authorization", "Bearer " + login()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an unauthenticated request to the admin route is refused")
    void unauthenticatedRequestToAdminRouteIsRefused() throws Exception {
        mockMvc.perform(get(ADMIN_ROUTE))
                .andExpect(status().isUnauthorized());
    }

    private static List<String> authorityNames(User user) {
        return user.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
    }

    private String login() throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .content("{\"email\": \"" + EMAIL + "\", \"password\": \"" + PASSWORD + "\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Access-Token"))
                .andReturn();

        return result.getResponse().getHeader("X-Access-Token");
    }
}
