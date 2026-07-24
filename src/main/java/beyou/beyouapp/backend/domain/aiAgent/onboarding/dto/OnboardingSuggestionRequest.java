package beyou.beyouapp.backend.domain.aiAgent.onboarding.dto;

import java.util.List;

import beyou.beyouapp.backend.domain.aiAgent.onboarding.OnboardingStep;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record OnboardingSuggestionRequest(
        @NotNull OnboardingStep step,
        // CATEGORIES step: names to enrich VERBATIM
        @Size(max = 30) List<@Size(max = 100) String> categoryNames,
        @Valid OnboardingContext context,
        // free-text ask for MORE items on HABITS_TASKS / GOALS
        @Size(max = 300) String newRequest) {

    public record OnboardingContext(
            @Size(max = 30) List<@Size(max = 100) String> categories,
            @Size(max = 60) List<@Valid ItemRef> habits,
            @Size(max = 60) List<@Valid ItemRef> tasks,
            @Size(max = 10) List<@Size(max = 300) String> freeTexts,
            // ROUTINE step: adapt feedback
            @Size(max = 500) String feedback) {}

    public record ItemRef(@Size(max = 256) String name, @Size(max = 256) String categoryName) {}
}
