package beyou.beyouapp.backend.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Date;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import beyou.beyouapp.backend.docs.architecture.ArchitectureTopicService;
import beyou.beyouapp.backend.docs.architecture.dto.ArchitectureTopicDetailDTO;
import beyou.beyouapp.backend.docs.architecture.dto.ArchitectureTopicListItemDTO;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ExtendWith(MockitoExtension.class)
@ActiveProfiles("test")
public class ArchitectureDocsControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ArchitectureTopicService topicService;

    @Test
    void shouldListArchitectureTopics() throws Exception {
        ArchitectureTopicListItemDTO listItem = new ArchitectureTopicListItemDTO(
            "overview",
            "Overview",
            "Main flow",
            1,
            Date.valueOf("2026-02-17")
        );

        when(topicService.getTopics("pt")).thenReturn(List.of(listItem));

        mockMvc.perform(get("/docs/architecture/topics").param("locale", "pt"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].key").value("overview"))
            .andExpect(jsonPath("$[0].title").value("Overview"));
    }

    @Test
    void shouldGetArchitectureTopicDetail() throws Exception {
        ArchitectureTopicDetailDTO detail = new ArchitectureTopicDetailDTO(
            "overview",
            "Overview",
            "flowchart LR",
            "## Context",
            Date.valueOf("2026-02-17")
        );

        when(topicService.getTopic("overview", "en")).thenReturn(detail);

        mockMvc.perform(get("/docs/architecture/topics/overview").param("locale", "en"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.key").value("overview"))
            .andExpect(jsonPath("$.diagramMermaid").value("flowchart LR"));
    }
}
