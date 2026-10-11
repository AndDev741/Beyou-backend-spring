package beyou.beyouapp.backend.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;

/**
 * The preferences routes over HTTP, with the real service behind them: the JSON shape both
 * clients read, and the partial PUT the old engagement toggle and the new Reminders section
 * both send.
 */
@Transactional
@AutoConfigureMockMvc(addFilters = false)
class NotificationPreferencesControllerTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @MockitoBean private AuthenticatedUser authenticatedUser;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setName("Preferences Owner");
        user.setEmail("prefs-http-" + UUID.randomUUID() + "@test.com");
        user.setPassword("irrelevant-for-this-test");
        user = userRepository.saveAndFlush(user);
        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
    }

    @Test
    void readsEveryFieldWithTheDefaults() throws Exception {
        mockMvc.perform(get("/notification/preferences"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.engagementEmail").value(true))
                .andExpect(jsonPath("$.reminderEmail").value(true))
                .andExpect(jsonPath("$.reminderWeb").value(true))
                .andExpect(jsonPath("$.reminderDefaultTime").value("09:00"));
    }

    /** What every client that predates the calendar sends. */
    @Test
    void anEngagementOnlyBodyStillWorksAndLeavesRemindersOn() throws Exception {
        mockMvc.perform(put("/notification/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"engagementEmail\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.engagementEmail").value(false))
                .andExpect(jsonPath("$.reminderEmail").value(true))
                .andExpect(jsonPath("$.reminderWeb").value(true))
                .andExpect(jsonPath("$.reminderDefaultTime").value("09:00"));
    }

    @Test
    void theRemindersSectionSendsOnlyItsOwnFields() throws Exception {
        mockMvc.perform(put("/notification/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reminderWeb\":false,\"reminderDefaultTime\":\"07:30\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.engagementEmail").value(true))
                .andExpect(jsonPath("$.reminderEmail").value(true))
                .andExpect(jsonPath("$.reminderWeb").value(false))
                .andExpect(jsonPath("$.reminderDefaultTime").value("07:30"));

        mockMvc.perform(get("/notification/preferences"))
                .andExpect(jsonPath("$.reminderWeb").value(false))
                .andExpect(jsonPath("$.reminderDefaultTime").value("07:30"));
    }
}
