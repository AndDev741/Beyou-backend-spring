package beyou.beyouapp.backend.controller;

import beyou.beyouapp.backend.user.dto.UserRegisterDTO;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
public class AuthenticationControllerTest {
    @Autowired
    MockMvc mockMvc;

    @Test
    public void shouldPassSuccessfullyIfUserAreAuthenticated() throws Exception {
        Cookie jwt = simulateLogin().getResponse().getCookie("jwt");

        mockMvc.perform(get("/auth/verify")
                .cookie(jwt))
                .andExpect(status().isOk());
    }

    @Test
    public void shouldMakeLoginSuccessfully() throws Exception {
        mockMvc.perform(post("/auth/login")
                .content("{\"email\": \"testebeyou@gmail.com\", \"password\": \"123456\"}")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().string("{\"success\":{\"id\":\"a4498905-9b3b-444e-af3f-092a60aff549\",\"name\":\"fafas\",\"email\":\"testebeyou@gmail.com\",\"phrase\":null,\"phrase_author\":null,\"constance\":0,\"photo\":null,\"isGoogleAccount\":false}}"))
                .andExpect(cookie().exists("jwt"));
    }

    @Test
    @Transactional
    public void shouldRegisterAUserSuccessfully() throws Exception {
        mockMvc.perform(post("/auth/register")
                .content("{\"name\": \"test\", \"email\": \"newtestbeyou5@gmail.com\", \"password\": \"123456\", " +
                        "\"isGoogleAccount\": false}")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().string("{\"success\":\"User registered successfully\"}"));


    }

    //Error Messages

    @Test
    public void shouldNotPassIfUserAreNotAuthenticated() throws Exception {
        mockMvc.perform(get("/auth/verify")
                        .cookie(new Cookie("jwt", "invalid")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void shouldReturnIncorrectEmailOrPasswordWhenPassedAIncorrectEmail() throws Exception {
        mockMvc.perform(post("/auth/login")
                .content("{\"email\": \"Incorrect@gmail.com\", \"password\": \"123456\"}")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("{\"error\":\"Email or password incorrect\"}"));
    }

    @Test
    public void shouldReturnIncorrectEmailOrPasswordWhenPassedAIncorrectPassword() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .content("{\"email\": \"testebeyou@gmail.com\", \"password\": \"incorrect\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("{\"error\":\"Email or password incorrect\"}"));
    }

    @Test
    public void shouldReturnAErrorMessageOfEmailAlreadyInUseIfTryingToRegisterAEmailAlreadyRegistered() throws Exception {
        mockMvc.perform(post("/auth/register")
                .content("{\"name\": \"okok\", \"email\": \"email@gmail.com\", \"password\": \"123456\", " +
                        "\"isGoogleAccount\": false}")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is(HttpServletResponse.SC_BAD_REQUEST))
                .andExpect(content().string("{\"error\":\"Email already in use\"}"));
    }

    @Test
    public void shouldReturnAErrorMessageOfMissingNameIfTryingToRegisterWithoutAName() throws Exception {
        mockMvc.perform(post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"    \", \"email\": \"newtestbeyou4@gmail.com\", \"password\": \"123456\", " +
                        "\"isGoogleAccount\": false}"))
                .andExpect(status().is(HttpServletResponse.SC_BAD_REQUEST))
                .andExpect(content().string("{\"name\":\"Name is Required\"}"));
    }

    @Test
    public void shouldReturnAErrorMessageOfMinimumCharacterForNameIfTryingToRegisterANameSmallerThan2Characters() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"a\", \"email\": \"newtestbeyou4@gmail.com\", \"password\": \"123456\", " +
                                "\"isGoogleAccount\": false}"))
                .andExpect(status().is(HttpServletResponse.SC_BAD_REQUEST))
                .andExpect(content().string("{\"name\":\"Name require a minimum of 2 characters\"}"));
    }

    @Test
    public void shouldReturnAErrorMessageOfMissingNEmailIfTryingToRegisterWithoutAEmail() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"namename\", \"email\": \"\", \"password\": \"123456\", " +
                                "\"isGoogleAccount\": false}"))
                .andExpect(status().is(HttpServletResponse.SC_BAD_REQUEST))
                .andExpect(content().string("{\"email\":\"Email is Required\"}"));
    }

    @Test
    public void shouldReturnAErrorMessageOfIncorrectEmailIfTryingToRegisterWithAInvalidEmail() throws Exception {
        mockMvc.perform(post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"namename\", \"email\": \"email.com\", \"password\": \"123456\", " +
                        "\"isGoogleAccount\": false}"))
                .andExpect(status().is(HttpServletResponse.SC_BAD_REQUEST))
                .andExpect(content().string("{\"email\":\"Email is invalid\"}"));
    }

    @Test
    public void shouldReturnAErrorMessageOfMinimumCharactersIfTryingToRegisterAPasswordWithLessThan6Characters() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"namename\", \"email\": \"newtestbeyou4@gmail.com\", \"password\": \"1234\", " +
                                "\"isGoogleAccount\": false}"))
                .andExpect(status().is(HttpServletResponse.SC_BAD_REQUEST))
                .andExpect(content().string("{\"password\":\"Password require a minimum of 6 characters\"}"));
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
