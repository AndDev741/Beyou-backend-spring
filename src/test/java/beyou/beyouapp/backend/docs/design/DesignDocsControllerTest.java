package beyou.beyouapp.backend.docs.design;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import beyou.beyouapp.backend.docs.design.dto.DesignTopicDetailDTO;
import beyou.beyouapp.backend.docs.design.dto.DesignTopicListItemDTO;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class)
@ActiveProfiles("test")
public class DesignDocsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DesignTopicService topicService;

    @Test
    void shouldReturnDesignTopics() throws Exception {
        Date updatedAt = Date.valueOf(LocalDate.of(2026, 2, 17));
        List<DesignTopicListItemDTO> topics = List.of(
            new DesignTopicListItemDTO("user-flow", "User Flow", "Main flow", 1, updatedAt)
        );

        when(topicService.getTopics("en")).thenReturn(topics);

        mockMvc.perform(get("/docs/design/topics?locale=en"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].key").value("user-flow"))
            .andExpect(jsonPath("$[0].title").value("User Flow"))
            .andExpect(jsonPath("$[0].summary").value("Main flow"));
    }

    @Test
    void shouldReturnDesignTopicDetail() throws Exception {
        Date updatedAt = Date.valueOf(LocalDate.of(2026, 2, 17));
        DesignTopicDetailDTO detail = new DesignTopicDetailDTO(
            "user-flow",
            "User Flow",
            "# Flow",
            updatedAt
        );

        when(topicService.getTopic("user-flow", "en")).thenReturn(detail);

        mockMvc.perform(get("/docs/design/topics/user-flow?locale=en"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.key").value("user-flow"))
            .andExpect(jsonPath("$.title").value("User Flow"))
            .andExpect(jsonPath("$.docMarkdown").value("# Flow"));
    }
}
