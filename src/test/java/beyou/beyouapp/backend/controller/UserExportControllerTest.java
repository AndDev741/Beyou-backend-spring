package beyou.beyouapp.backend.controller;

import beyou.beyouapp.backend.user.UserExportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import beyou.beyouapp.backend.AbstractIntegrationTest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc(addFilters = false)
class UserExportControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserExportService userExportService;

    @Test
    void shouldReturnExportData() throws Exception {
        Map<String, Object> mockExport = new LinkedHashMap<>();
        mockExport.put("exportedAt", "2026-04-06T00:00:00Z");
        mockExport.put("profile", Map.of("name", "Test", "email", "test@test.com"));
        mockExport.put("categories", List.of());
        mockExport.put("habits", List.of());
        mockExport.put("goals", List.of());
        mockExport.put("tasks", List.of());

        when(userExportService.exportUserData()).thenReturn(mockExport);

        mockMvc.perform(get("/user/export"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exportedAt").value("2026-04-06T00:00:00Z"))
                .andExpect(jsonPath("$.profile.name").value("Test"))
                .andExpect(jsonPath("$.profile.email").value("test@test.com"))
                .andExpect(jsonPath("$.categories").isArray())
                .andExpect(jsonPath("$.habits").isArray())
                .andExpect(jsonPath("$.goals").isArray())
                .andExpect(jsonPath("$.tasks").isArray());
    }
}
