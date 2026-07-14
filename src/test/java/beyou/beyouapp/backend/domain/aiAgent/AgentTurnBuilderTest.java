package beyou.beyouapp.backend.domain.aiAgent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import beyou.beyouapp.backend.domain.aiAgent.chat.dto.AgentSegment;
import beyou.beyouapp.backend.domain.aiAgent.dto.AgentEvent;

/**
 * The transcript core: turns the ordered event stream into the segments the
 * done event carries and the DB persists. Same package so the package-private
 * builder is reachable.
 */
class AgentTurnBuilderTest {

    @Test
    void consecutiveTokensCollapseIntoOneTextSegment() {
        AgentTurnBuilder turn = new AgentTurnBuilder();
        turn.observe(AgentEvent.token("Hello"));
        turn.observe(AgentEvent.token(", "));
        turn.observe(AgentEvent.token("world"));

        List<AgentSegment> segments = turn.build();

        assertEquals(1, segments.size());
        assertEquals("text", segments.get(0).type());
        assertEquals("Hello, world", segments.get(0).text());
    }

    @Test
    void onlyFinishedToolsAreRecorded_startedIsLiveOnly() {
        AgentTurnBuilder turn = new AgentTurnBuilder();
        turn.observe(AgentEvent.toolStarted("createUserHabit"));
        turn.observe(AgentEvent.toolFinished("createUserHabit", null, List.of("habits")));

        List<AgentSegment> segments = turn.build();

        assertEquals(1, segments.size());
        AgentSegment tool = segments.get(0);
        assertEquals("tool", tool.type());
        assertEquals("createUserHabit", tool.tool());
        assertNull(tool.error());
        assertEquals(List.of("habits"), tool.domains());
    }

    @Test
    void textAndToolsInterleaveInCausalOrder() {
        AgentTurnBuilder turn = new AgentTurnBuilder();
        turn.observe(AgentEvent.token("Let me check. "));
        turn.observe(AgentEvent.toolStarted("getUserCategories"));
        turn.observe(AgentEvent.toolFinished("getUserCategories", null, List.of()));
        turn.observe(AgentEvent.token("Done."));

        List<AgentSegment> segments = turn.build();

        assertEquals(3, segments.size());
        assertEquals("text", segments.get(0).type());
        assertEquals("Let me check. ", segments.get(0).text());
        assertEquals("tool", segments.get(1).type());
        assertEquals("getUserCategories", segments.get(1).tool());
        assertEquals("text", segments.get(2).type());
        assertEquals("Done.", segments.get(2).text());
    }

    @Test
    void failedToolCarriesItsError() {
        AgentTurnBuilder turn = new AgentTurnBuilder();
        turn.observe(AgentEvent.toolFinished("deleteUserHabit", "HabitInRoutineException", List.of("habits")));

        AgentSegment tool = turn.build().get(0);
        assertEquals("HabitInRoutineException", tool.error());
    }

    @Test
    void doneAndErrorEventsAreNotPartOfTheTranscript() {
        AgentTurnBuilder turn = new AgentTurnBuilder();
        turn.observe(AgentEvent.done(List.of(AgentSegment.text("ignored"))));
        turn.observe(AgentEvent.error("SOME_KEY"));

        assertTrue(turn.build().isEmpty());
    }
}
