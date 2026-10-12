package beyou.beyouapp.backend.domain.notebook.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.template.st.StTemplateRenderer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

/**
 * The modes in {@code prompts/notebookTutor.st}, rendered the way {@link NotebookLlm} renders
 * them. The services only send a mode's name; what the name means lives in this file, so a mode
 * that loses its instruction here changes every answer that sends it while every service test
 * stays green behind a mocked model.
 */
class NotebookTutorPromptTest {

    private static String prompt() throws IOException {
        String template = StreamUtils.copyToString(
                new ClassPathResource("prompts/notebookTutor.st").getInputStream(), StandardCharsets.UTF_8);
        // The two values NotebookLlm.doCall passes. A brace in a mode's text would break this.
        return StTemplateRenderer.builder().build()
                .apply(template, Map.of("language", "en", "today", "2026-10-12"));
    }

    /** One mode's bullet, whitespace collapsed so a line break never splits a phrase. */
    private static String mode(String prompt, String name) {
        int start = prompt.indexOf("  - " + name + ":");
        assertThat(start).as("the prompt defines the mode " + name).isNotNegative();
        int end = prompt.length();
        for (String next : new String[] { "\n  - ", "\n- " }) {
            int at = prompt.indexOf(next, start + 1);
            if (at > 0) end = Math.min(end, at);
        }
        return prompt.substring(start, end).replaceAll("\\s+", " ");
    }

    /**
     * The study room's chat: notes and sources first, cited, then the model's own knowledge,
     * marked as going beyond them. Never a refusal because the notes stop.
     */
    @Test
    void tutorAnswersPastTheNotesAndSaysWhereItDoes() throws IOException {
        String tutor = mode(prompt(), "TUTOR");

        assertThat(tutor)
                .contains("Use the numbered passages first")
                .contains("answer from your own knowledge of the subject anyway")
                .contains("open that part with one short sentence")
                .contains("with no apology")
                .contains("saying it goes beyond their notes and sources")
                .contains("Never put a number on a claim the passages do not support")
                .contains("never invent a number that is not in the list")
                .contains("With no passages at all, answer from your own knowledge")
                .contains("run a level test")
                .doesNotContain("stop");
    }

    /** The studio's outputs still send GROUNDED, and GROUNDED still stops where the passages do. */
    @Test
    void groundedStillStopsWhereThePassagesDo() throws IOException {
        String grounded = mode(prompt(), "GROUNDED");

        assertThat(grounded)
                .contains("answer ONLY from the numbered passages")
                .contains("say so plainly in one sentence and stop")
                .contains("do not fill the gap from general knowledge");
    }
}
