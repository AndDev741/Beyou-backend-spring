package beyou.beyouapp.backend.integration.notebook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.notebook.NotebookPageService;
import beyou.beyouapp.backend.domain.notebook.board.NotebookBoardService;
import beyou.beyouapp.backend.domain.notebook.board.dto.CreateNodeRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.CreateTopicRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.SetStatusRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.StatusChoice;
import beyou.beyouapp.backend.domain.routine.snapshot.XpDecayStrategy;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import tools.jackson.databind.ObjectMapper;

/**
 * The study cycle's JSON, as the clients read it, through the real controller and services.
 *
 * <p>The web and mobile clients were written against the shapes in the study cycle plan (section
 * 4) at the same time as this backend, so these tests compare the key set of each object with
 * that contract, nulls included: a field renamed here, or one that silently disappears when it is
 * null, breaks a screen without failing anything else.
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
class StudyCycleApiIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private NotebookPageService pageService;
    @Autowired private NotebookBoardService boardService;
    @Autowired private UserRepository userRepository;
    @MockitoBean private AuthenticatedUser authenticatedUser;

    private final ObjectMapper json = new ObjectMapper();
    private User user;
    private UUID topic;
    private UUID concept;

    @BeforeEach
    void setUp() {
        User u = new User();
        u.setName("api");
        u.setEmail("cycle-api-" + UUID.randomUUID() + "@test.com");
        u.setPassword("password123");
        u.setTimezone("UTC");
        u.setCompletedDays(new HashSet<>());
        u.setXpDecayStrategy(XpDecayStrategy.GRADUAL);
        u.setXpProgress(new XpProgress(0D, 0, 0D, 50D));
        user = userRepository.saveAndFlush(u);
        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
        topic = pageService.createTopic(user, new CreateTopicRequestDTO("Dir. Constitucional", null, null, null, null, null)).id();
        concept = boardService.addNode(user, topic,
                new CreateNodeRequestDTO(null, "Controle de constitucionalidade", null, null, 0d, 0d, null, null, null))
                .node().pageId();
    }

    @Test
    void planAnswersInTheContractShape() throws Exception {
        Map<String, Object> plan = call(post("/notebook/cycles/plan").content("""
                {"days": %s, "maxBlockMinutes": 90,
                 "topics": [{"topicId": "%s", "importance": 5, "mastery": 2, "minutesOverride": null}]}
                """.formatted(week(60, "19:00"), topic)), 200);

        assertThat(plan).containsOnlyKeys("weekMinutes", "plannedMinutes", "lapDays", "topics", "blocks");
        assertThat(first(plan, "topics")).containsOnlyKeys("topicId", "weight", "share", "suggestedMinutes", "minutes", "blocks");
        assertThat(first(plan, "blocks")).containsOnlyKeys("position", "topicId", "plannedMinutes");
        assertThat(plan.get("weekMinutes")).isEqualTo(420);
    }

    @Test
    void aCycleTodayAndASessionAnswerInTheContractShape() throws Exception {
        Map<String, Object> cycle = call(post("/notebook/cycles").content(config("Concurso TRT")), 201);

        assertThat(cycle).containsOnlyKeys("id", "name", "defaultMode", "maxBlockMinutes", "position", "days", "topics",
                "lap", "blocks", "previousLaps");
        assertThat(first(cycle, "days")).containsOnlyKeys("weekday", "minutes", "startTime")
                .containsEntry("weekday", "MONDAY").containsEntry("startTime", "19:00");
        Map<String, Object> cycleTopic = first(cycle, "topics");
        assertThat(cycleTopic).containsOnlyKeys("topicId", "importance", "mastery", "minutesOverride", "topic");
        assertThat(map(cycleTopic, "topic")).containsOnlyKeys("id", "title", "icon", "rootTitle");
        assertThat(map(cycle, "lap")).containsOnlyKeys("number", "startedAt", "closedAt", "minutes", "plannedMinutes",
                "blocksClosed", "blocks", "understood", "turnsUsed");
        Map<String, Object> block = first(cycle, "blocks");
        assertThat(block).containsOnlyKeys("id", "position", "topic", "mode", "plannedMinutes", "goalSource", "page",
                "requireQuiz", "note", "progress");
        assertThat(map(block, "topic")).containsOnlyKeys("id", "title", "icon", "rootTitle");
        Map<String, Object> progress = map(block, "progress");
        assertThat(progress).containsOnlyKeys("lapNumber", "minutes", "state", "closedAt", "target", "targetPreview",
                "quizPassed");
        assertThat(map(progress, "target")).containsOnlyKeys("id", "title", "icon", "status", "progress");
        assertThat(map(map(progress, "target"), "progress")).containsOnlyKeys("done", "total");
        assertThat(map(progress, "target")).containsEntry("id", concept.toString());

        List<Map<String, Object>> list = callList(get("/notebook/cycles"));
        assertThat(list.get(0)).containsOnlyKeys("id", "name", "position", "lap", "today");
        assertThat(map(list.get(0), "today")).containsOnlyKeys("minutes", "startTime");

        Map<String, Object> today = call(get("/notebook/cycles/today"), 200);
        assertThat(today).containsOnlyKeys("date", "weekday", "plannedMinutes", "studiedMinutes", "blocksDone",
                "blocksTotal", "cycles", "restingCycles");
        Map<String, Object> todayCycle = first(today, "cycles");
        assertThat(todayCycle).containsOnlyKeys("cycleId", "name", "startTime", "dayMinutes", "studiedMinutes",
                "lapNumber", "position", "blocksInLap", "entries");
        Map<String, Object> entry = first(todayCycle, "entries");
        assertThat(entry).containsOnlyKeys("blockId", "position", "topic", "mode", "goalSource", "page", "note",
                "requireQuiz", "plannedMinutes", "credited", "minutesToday", "state", "doneToday", "nextLap", "start",
                "end", "target", "targetPreview", "quizPassed", "cardsDue");
        assertThat(entry).containsEntry("start", "19:00");
        assertThat(map(entry, "topic")).containsOnlyKeys("id", "title", "icon", "rootTitle");
        // Dates as YYYY-MM-DD and instants as ISO strings, never arrays or epoch numbers.
        assertThat(today.get("date")).isEqualTo(LocalDate.now(ZoneOffset.UTC).toString());
        assertThat((String) map(cycle, "lap").get("startedAt")).matches("\\d{4}-\\d{2}-\\d{2}T.*Z");

        Map<String, Object> session = call(post("/notebook/sessions").content("""
                {"topicId": "%s", "pageId": "%s", "minutes": 45, "studiedOn": "%s", "note": "livro"}
                """.formatted(topic, concept, LocalDate.now(ZoneOffset.UTC))), 201);
        assertThat(session).containsOnlyKeys("id", "credited");
        assertThat(map(session, "credited")).containsOnlyKeys("cycleId", "cycleName", "blockId", "position",
                "minutesBefore", "minutesAfter", "plannedMinutes", "state")
                .containsEntry("minutesAfter", 45);

        String id = (String) cycle.get("id");
        LocalDate day = LocalDate.now(ZoneOffset.UTC);
        Map<String, Object> history = call(get("/notebook/cycles/" + id + "/history")
                .param("from", day.minusDays(6).toString()).param("to", day.toString()), 200);
        assertThat(history).containsOnlyKeys("cycleId", "from", "to", "topics", "days");
        assertThat(first(history, "topics")).containsOnlyKeys("id", "title", "icon", "rootTitle");
        List<Map<String, Object>> days = list(history, "days");
        Map<String, Object> lastDay = days.get(days.size() - 1);
        assertThat(lastDay).containsOnlyKeys("date", "plannedMinutes", "minutes", "byTopic", "entries");
        assertThat(first(lastDay, "byTopic")).containsOnlyKeys("topicId", "minutes");
        assertThat(first(lastDay, "entries")).containsOnlyKeys("kind", "at", "sessionId", "topic", "page", "minutes", "note")
                .containsEntry("kind", "MANUAL");
        assertThat(map(first(lastDay, "entries"), "topic")).containsOnlyKeys("id", "title", "icon", "rootTitle");

        mockMvc.perform(delete("/notebook/sessions/" + session.get("id"))).andExpect(status().isNoContent());
        mockMvc.perform(post("/notebook/cycles/" + id + "/archive")).andExpect(status().isNoContent());
        mockMvc.perform(delete("/notebook/cycles/" + id)).andExpect(status().isNoContent());
    }

    /**
     * The refusal for a taken subject names the cycle that holds it and the subject it holds, so
     * the client can say which: here a page inside the topic another cycle holds whole.
     */
    @Test
    void aTakenTopicIsRefusedWithTheCycleInDetails() throws Exception {
        call(post("/notebook/cycles").content(config("Concurso TRT")), 201);

        mockMvc.perform(json(post("/notebook/cycles").content(config("Faculdade", concept))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorKey").value("STUDY_CYCLE_TOPIC_TAKEN"))
                .andExpect(jsonPath("$.details.cycleName").value("Concurso TRT"))
                .andExpect(jsonPath("$.details.topicId").value(concept.toString()))
                .andExpect(jsonPath("$.details.takenTopicId").value(topic.toString()))
                .andExpect(jsonPath("$.details.takenTopicTitle").value("Dir. Constitucional"));
    }

    @Test
    void overlappingSubjectsInOneRequestAreRefusedWithBothIds() throws Exception {
        mockMvc.perform(json(post("/notebook/cycles").content(config("Mixed", topic, concept))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorKey").value("STUDY_CYCLE_TOPIC_OVERLAP"))
                .andExpect(jsonPath("$.details.ancestorId").value(topic.toString()))
                .andExpect(jsonPath("$.details.descendantId").value(concept.toString()));
    }

    /**
     * Every subject ref says which topic it lives in: {@code rootTitle} is the topic's title for a
     * page inside one, and null (present, not missing) for a topic, in the cycle, today and the
     * history alike.
     */
    @Test
    void everySubjectRefCarriesItsRootTitle() throws Exception {
        UUID portugues = pageService.createTopic(user, new CreateTopicRequestDTO("Português", null, null, null, null, null)).id();
        Map<String, Object> cycle = call(post("/notebook/cycles").content(config("Concurso", portugues, concept)), 201);
        String id = (String) cycle.get("id");
        LocalDate day = LocalDate.now(ZoneOffset.UTC);
        for (UUID subject : List.of(portugues, concept)) {
            call(post("/notebook/sessions").content("""
                    {"topicId": "%s", "pageId": null, "minutes": 10, "studiedOn": "%s", "note": null}
                    """.formatted(subject, day)), 201);
        }
        pageService.setStatus(user, concept, new SetStatusRequestDTO(StatusChoice.DONE));

        cycle = call(get("/notebook/cycles/" + id), 200);
        List<Map<String, Object>> refs = new ArrayList<>();
        list(cycle, "topics").forEach(t -> refs.add(map(t, "topic")));
        list(cycle, "blocks").forEach(b -> refs.add(map(b, "topic")));
        list(first(call(get("/notebook/cycles/today"), 200), "cycles"), "entries").forEach(e -> refs.add(map(e, "topic")));
        Map<String, Object> history = call(get("/notebook/cycles/" + id + "/history")
                .param("from", day.toString()).param("to", day.toString()), 200);
        refs.addAll(list(history, "topics"));
        List<Map<String, Object>> entries = list(first(history, "days"), "entries");
        assertThat(entries).extracting(e -> e.get("kind")).contains("MANUAL", "UNDERSTOOD");
        entries.stream().filter(e -> e.containsKey("topic")).forEach(e -> refs.add(map(e, "topic")));

        assertThat(refs).allSatisfy(ref -> {
            assertThat(ref).containsOnlyKeys("id", "title", "icon", "rootTitle");
            if (ref.get("id").equals(concept.toString())) {
                assertThat(ref).containsEntry("rootTitle", "Dir. Constitucional");
            } else {
                assertThat(ref).containsEntry("id", portugues.toString()).containsEntry("rootTitle", null);
            }
        });
        assertThat(refs).extracting(ref -> ref.get("id")).contains(concept.toString(), portugues.toString());
    }

    /** In a PATCH, a field left out stays and a field sent as null is cleared. */
    @Test
    void aPatchTellsAbsentFromNull() throws Exception {
        Map<String, Object> cycle = call(post("/notebook/cycles").content(config("Patch")), 201);
        String path = "/notebook/cycles/" + cycle.get("id") + "/blocks/" + first(cycle, "blocks").get("id");

        Map<String, Object> noted = first(call(patch(path).content("{\"note\": \"questões FCC\"}"), 200), "blocks");
        assertThat(noted).containsEntry("note", "questões FCC").containsEntry("plannedMinutes", 60);

        Map<String, Object> kept = first(call(patch(path).content("{\"plannedMinutes\": 45}"), 200), "blocks");
        assertThat(kept).containsEntry("note", "questões FCC").containsEntry("plannedMinutes", 45);

        Map<String, Object> cleared = first(call(patch(path).content("{\"note\": null}"), 200), "blocks");
        assertThat(cleared.get("note")).isNull();
    }

    @Test
    void aWeekIsSevenDaysInStepsOfFive() throws Exception {
        mockMvc.perform(json(post("/notebook/cycles/plan").content("""
                {"days": [{"weekday": "MONDAY", "minutes": 60, "startTime": null}], "maxBlockMinutes": 90, "topics": []}
                """))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorKey").value("INVALID_REQUEST"));
        mockMvc.perform(json(post("/notebook/cycles/plan").content("""
                {"days": %s, "maxBlockMinutes": 90, "topics": []}
                """.formatted(week(62, null))))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorKey").value("INVALID_REQUEST"));
        mockMvc.perform(json(post("/notebook/cycles/plan").content("""
                {"days": %s, "maxBlockMinutes": 90, "topics": []}
                """.formatted(week(60, "7pm"))))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorKey").value("INVALID_REQUEST"));
    }

    // --------------------------------------------------------------- helpers

    private String config(String name) {
        return config(name, topic);
    }

    private static String config(String name, UUID... subjects) {
        StringBuilder topics = new StringBuilder();
        for (UUID subject : subjects) {
            if (topics.length() > 0) topics.append(',');
            topics.append("{\"topicId\": \"").append(subject)
                    .append("\", \"importance\": 5, \"mastery\": 2, \"minutesOverride\": 60}");
        }
        return """
                {"name": "%s", "defaultMode": "GOAL", "maxBlockMinutes": 90, "days": %s, "topics": [%s]}
                """.formatted(name, week(120, "19:00"), topics);
    }

    private static String week(int minutes, String start) {
        StringBuilder sb = new StringBuilder("[");
        String[] days = {"MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"};
        for (int i = 0; i < days.length; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"weekday\":\"").append(days[i]).append("\",\"minutes\":").append(minutes)
                    .append(",\"startTime\":").append(start == null ? "null" : "\"" + start + "\"").append('}');
        }
        return sb.append(']').toString();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request) {
        return request.contentType(MediaType.APPLICATION_JSON);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(MockHttpServletRequestBuilder request, int status) throws Exception {
        String body = mockMvc.perform(json(request)).andExpect(status().is(status))
                .andReturn().getResponse().getContentAsString();
        return json.readValue(body, Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> callList(MockHttpServletRequestBuilder request) throws Exception {
        String body = mockMvc.perform(json(request)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readValue(body, List.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> parent, String key) {
        assertThat(parent).containsKey(key);
        return (Map<String, Object>) parent.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> parent, String key) {
        return (List<Map<String, Object>>) parent.get(key);
    }

    private static Map<String, Object> first(Map<String, Object> parent, String key) {
        List<Map<String, Object>> items = list(parent, key);
        assertThat(items).as(key).isNotEmpty();
        return items.get(0);
    }
}
