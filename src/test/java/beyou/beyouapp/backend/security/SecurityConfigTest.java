package beyou.beyouapp.backend.security;

import jakarta.servlet.http.Cookie;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.UserService;
import beyou.beyouapp.backend.user.dto.UserRegisterDTO;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;


@AutoConfigureMockMvc
@Import({SecurityConfig.class})
@SpringBootTest
@ActiveProfiles("test")
public class SecurityConfigTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    UserRepository  userRepository;

    @Autowired
    UserService userService;

    @BeforeEach
    void setUp(){
        MockitoAnnotations.openMocks(this);

        userRepository.deleteAll(); // Clean before all tests
        UserRegisterDTO register = new UserRegisterDTO("test", "testebeyou@gmail.com", "123456", false);
        userService.registerUser(register);
    
    }


    @Test
    @Transactional
    public void shouldAllowAccessToLoginAndRegisterWithoutAuthentication() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .content("{\"email\": \"testebeyou@gmail.com\", \"password\": \"123456\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("jwt"));

        mockMvc.perform(post("/auth/register")
                        .content("{\"name\": \"test\", \"email\": \"newtestbeyou5@gmail.com\", \"password\": \"123456\", " +
                                "\"isGoogleAccount\": false}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().string("{\"success\":\"User registered successfully\"}"));
    }

    @Test
    public void shouldAllowAccessToProtectedEndpointIfAuthenticated() throws Exception {
        Cookie jwtCookie = simulateLogin().getResponse().getCookie("jwt");

        mockMvc.perform(get("/category")
                        .cookie(jwtCookie))
                .andExpect(status().isOk());
    }



    @Test
    @WithMockUser
    public void shouldAllowCorsFromConfiguredOrigin() throws Exception {
        mockMvc.perform(options("/")
                        .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"));
    }

    @Test
    public void shouldInvalidateSessionOnLogout() throws Exception {
        Cookie jwtCookie = simulateLogin().getResponse().getCookie("jwt");

        mockMvc.perform(post("/logout")
                        .cookie(jwtCookie))
                .andExpect(status().isOk())
                .andExpect(cookie().maxAge("jwt", 0));
    }

    @Test
    public void shouldEncodePassword(){
        String password = "123456789";
        String encodedPassword = passwordEncoder.encode(password);

        assertTrue(passwordEncoder.matches(password, encodedPassword));
    }

    //Exceptions

    @Test
    public void shouldReturnUnauthorizedWhenJwtCookieNotFound() throws Exception {
        mockMvc.perform(get("/user"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("JWT Cookie not Found"));
    }

    private MvcResult simulateLogin() throws Exception {
        return mockMvc.perform(post("/auth/login")
                        .content("{\"email\": \"testebeyou@gmail.com\", \"password\": \"123456\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("jwt"))
                .andReturn();
    }
}
