package beyou.beyouapp.backend.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.aiAgent.onboarding.OnboardingSuggestionService;
import beyou.beyouapp.backend.domain.aiAgent.onboarding.dto.OnboardingSuggestions;
import beyou.beyouapp.backend.domain.aiAgent.onboarding.dto.OnboardingSuggestions.CategorySuggestion;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.User;
import jakarta.transaction.Transactional;

@ExtendWith(MockitoExtension.class)
@Transactional
@AutoConfigureMockMvc(addFilters = false)
public class OnboardingControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OnboardingSuggestionService suggestionService;

    @MockitoBean
    private AuthenticatedUser authenticatedUser;

    private UUID userId;
    private User user;

    @BeforeEach
    void setup() {
        userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
    }

    @Test
    void returnsSuggestionsForValidRequest() throws Exception {
        when(suggestionService.suggest(any(), any())).thenReturn(new OnboardingSuggestions(
                List.of(new CategorySuggestion("Health", "desc", "lucide:star")), null, null, null, null));

        mockMvc.perform(post("/onboarding/suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"step\":\"CATEGORIES\",\"categoryNames\":[\"Health\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[0].name").value("Health"));
    }

    @Test
    void rejectsMissingStep() throws Exception {
        mockMvc.perform(post("/onboarding/suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryNames\":[\"Health\"]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsOversizedCategoryList() throws Exception {
        String names = "[" + "\"x\",".repeat(30) + "\"x\"]"; // 31 items > @Size(max=30)
        mockMvc.perform(post("/onboarding/suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"step\":\"CATEGORIES\",\"categoryNames\":" + names + "}"))
                .andExpect(status().isBadRequest());
    }
}
