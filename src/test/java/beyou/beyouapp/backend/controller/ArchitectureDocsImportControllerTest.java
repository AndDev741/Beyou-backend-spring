package beyou.beyouapp.backend.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import beyou.beyouapp.backend.docs.architecture.dto.ArchitectureDocsImportResultDTO;
import beyou.beyouapp.backend.docs.architecture.imp.ArchitectureDocsImportService;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ExtendWith(MockitoExtension.class)
@ActiveProfiles("test")
public class ArchitectureDocsImportControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ArchitectureDocsImportService importService;

    @Test
    void shouldImportArchitectureDocs() throws Exception {
        when(importService.importFromGitHub(null)).thenReturn(new ArchitectureDocsImportResultDTO(1, 0));

        mockMvc.perform(post("/docs/admin/import/architecture"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.importedTopics").value(1))
            .andExpect(jsonPath("$.archivedTopics").value(0));
    }
}
