package beyou.beyouapp.backend.domain.aiAgent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.template.st.StTemplateRenderer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

/**
 * The system prompt against the parameters {@code AiAgentService.buildPrompt} actually supplies.
 *
 * <p>A placeholder added to the template without its {@code .param} is a runtime failure on EVERY
 * agent turn, and nothing else in the suite renders this file: the controller tests mock the
 * service, and the streaming integration tests are refused before a prompt is built. So this
 * renders it for real, with the renderer the ChatClient uses, and fails on the mismatch instead of
 * production doing it.
 */
public class AgentSystemPromptTest {

    /** Exactly what buildPrompt passes. Keep the two in step — that is the point of the test. */
    private static Map<String, Object> params() {
        Map<String, Object> params = new HashMap<>();
        params.put("language", "en");
        params.put("iconCatalog", "lucide:target - goal, focus");
        params.put("userContext", "(none yet)");
        params.put("userChatContext", "(none yet)");
        params.put("currentPage", "/focus");
        params.put("focusItem", "3f1a6f1e-0000-4000-8000-000000000001");
        params.put("today", "2026-08-30");
        return params;
    }

    private static String template() throws IOException {
        return StreamUtils.copyToString(
                new ClassPathResource("prompts/aiAgent.st").getInputStream(), StandardCharsets.UTF_8);
    }

    @Test
    void rendersWithEveryPlaceholderTheServiceSupplies() throws IOException {
        String rendered = StTemplateRenderer.builder().build().apply(template(), params());

        assertFalse(rendered.contains("{"), "an unrendered placeholder is left in: " + rendered);
        assertTrue(rendered.contains("3f1a6f1e-0000-4000-8000-000000000001"),
                "the entry open in Focus Mode is what lets the agent resolve \"add a step here\"");
        assertTrue(rendered.contains("/focus"), rendered);
    }

    /** The guard above only means something if a missing value really is refused. */
    @Test
    void refusesToRenderWhenAValueIsMissing() throws IOException {
        Map<String, Object> incomplete = params();
        incomplete.remove("focusItem");
        String template = template();
        StTemplateRenderer renderer = StTemplateRenderer.builder().build();

        assertThrows(IllegalStateException.class, () -> renderer.apply(template, incomplete));
    }
}
