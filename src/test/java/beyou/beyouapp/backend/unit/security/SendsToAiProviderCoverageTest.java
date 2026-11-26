package beyou.beyouapp.backend.unit.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import beyou.beyouapp.backend.controllers.AiAgentController;
import beyou.beyouapp.backend.controllers.NotebookAiController;
import beyou.beyouapp.backend.controllers.NotebookSourceController;
import beyou.beyouapp.backend.controllers.OnboardingController;
import beyou.beyouapp.backend.controllers.SecondBrainController;
import beyou.beyouapp.backend.security.aiconsent.SendsToAiProvider;

/**
 * The routes that hand a person's data to the AI provider, written out. A route added to one
 * of these controllers that calls the model fails here until someone decides whether it needs
 * the person's yes, which is the decision {@code @SendsToAiProvider} records.
 */
class SendsToAiProviderCoverageTest {

    private static Set<String> marked(Class<?>... controllers) {
        return Arrays.stream(controllers)
                .flatMap(c -> Arrays.stream(c.getDeclaredMethods()))
                .filter(m -> m.isAnnotationPresent(SendsToAiProvider.class))
                .map(Method::getName)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void everyRouteThatReachesTheProviderIsMarked() {
        assertThat(marked(AiAgentController.class)).containsExactly("streamMessage");
        assertThat(marked(OnboardingController.class)).containsExactly("suggest");
        assertThat(marked(NotebookAiController.class)).containsExactlyInAnyOrder(
                "startDraft", "redraft", "suggestNodes", "explain", "cards", "diagram", "chat",
                "discoverSources", "generate");
        assertThat(marked(NotebookSourceController.class)).containsExactlyInAnyOrder(
                "addPdf", "addLink", "addText");
        assertThat(marked(SecondBrainController.class)).containsExactlyInAnyOrder(
                "refill", "answer", "refreshInsight");
    }
}
