package beyou.beyouapp.backend.unit.aiAgent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.core.io.ByteArrayResource;

import beyou.beyouapp.backend.domain.aiAgent.AiIconCatalog;
import beyou.beyouapp.backend.domain.aiAgent.onboarding.OnboardingStep;
import beyou.beyouapp.backend.domain.aiAgent.onboarding.OnboardingSuggestionService;
import beyou.beyouapp.backend.domain.aiAgent.onboarding.dto.OnboardingSuggestionRequest;
import beyou.beyouapp.backend.domain.aiAgent.onboarding.dto.OnboardingSuggestions;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;

public class OnboardingSuggestionServiceTest {

    private ChatModel chatModel;
    private OnboardingSuggestionService service;

    @BeforeEach
    public void setUp() {
        chatModel = mock(ChatModel.class);
        // Mock getOptions() to prevent NPE in ChatClient
        when(chatModel.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        service = new OnboardingSuggestionService(
                chatModel, new ByteArrayResource("Test system. Language {language}. Icons {iconCatalog}. Today {today}.".getBytes()));
    }

    private static ChatResponse ok(String json) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(json))));
    }

    private static User user() {
        User u = new User();
        u.setLanguageInUse("en");
        return u;
    }

    private static OnboardingSuggestionRequest categoriesRequest(List<String> names) {
        return new OnboardingSuggestionRequest(OnboardingStep.CATEGORIES, names, null, null);
    }

    @Test
    public void categoriesKeepNamesVerbatimAndSanitizeIcons() {
        // LLM renamed "Health" and invented an unknown icon — service must fix both
        when(chatModel.call(any(Prompt.class))).thenReturn(ok("""
                {"categories":[
                  {"name":"health & wellness","description":"Feel great","iconId":"lucide:not-a-real-icon"},
                  {"name":"Career","description":"Grow professionally","iconId":"%s"}
                ]}""".formatted(AiIconCatalog.ICONS.get(0).id())));

        OnboardingSuggestions result = service.suggest(categoriesRequest(List.of("Health", "Career")), user());

        assertThat(result.categories()).hasSize(2);
        assertThat(result.categories()).extracting("name").containsExactly("Health", "Career");
        assertThat(result.categories().get(0).iconId()).isEqualTo(AiIconCatalog.DEFAULT_ICON);
        assertThat(result.categories().get(1).description()).isEqualTo("Grow professionally");
        assertThat(result.habits()).isNull();
    }

    @Test
    public void habitsTasksClampsScoresAndSanitizesIcons() {
        when(chatModel.call(any(Prompt.class))).thenReturn(ok("""
                {"habits":[{"name":"Run","description":"d","motivationalPhrase":"m","iconId":"bogus","categoryName":"Health","importance":9,"difficulty":0}],
                 "tasks":[{"name":"Buy shoes","description":"d","iconId":"bogus","categoryName":"Health","importance":null,"difficulty":3}]}"""));

        OnboardingSuggestions result = service.suggest(new OnboardingSuggestionRequest(
                OnboardingStep.HABITS_TASKS, null,
                new OnboardingSuggestionRequest.OnboardingContext(List.of("Health"), null, null, null, null), null), user());

        assertThat(result.habits().get(0).importance()).isEqualTo(5);
        assertThat(result.habits().get(0).difficulty()).isEqualTo(1);
        assertThat(result.habits().get(0).iconId()).isEqualTo(AiIconCatalog.DEFAULT_ICON);
        assertThat(result.tasks().get(0).importance()).isEqualTo(3); // null -> middle default
    }

    @Test
    public void routineNormalizesScheduleDaysAndSanitizesItems() {
        // LLM returned uppercase days (old prompt style), an unknown day, a dup,
        // and an over-long item name — all must be normalized/sanitized.
        String longName = "x".repeat(300);
        when(chatModel.call(any(Prompt.class))).thenReturn(ok("""
                {"name":"Morning flow","iconId":"bogus",
                 "scheduleDays":["MONDAY","monday","Tuesday","Funday"],
                 "sections":[{"name":"Wake","iconId":"bogus","startTime":"07:00","endTime":"08:00",
                   "habits":[{"name":"%s","startTime":"07:00","endTime":"07:30"}],
                   "tasks":null}]}""".formatted(longName)));

        OnboardingSuggestions result = service.suggest(new OnboardingSuggestionRequest(
                OnboardingStep.ROUTINE, null,
                new OnboardingSuggestionRequest.OnboardingContext(List.of("Health"), null, null, null, null), null), user());

        assertThat(result.routine().scheduleDays()).containsExactly("Monday", "Tuesday");
        assertThat(result.routine().sections().get(0).habits().get(0).name()).hasSize(256);
        assertThat(result.routine().sections().get(0).tasks()).isEmpty();
        assertThat(result.routine().iconId()).isEqualTo(AiIconCatalog.DEFAULT_ICON);
    }

    @Test
    public void retriesOnceOnParseFailureThenSucceeds() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(ok("not json at all"))
                .thenReturn(ok("{\"categories\":[{\"name\":\"Health\",\"description\":\"d\",\"iconId\":\"bogus\"}]}"));

        OnboardingSuggestions result = service.suggest(categoriesRequest(List.of("Health")), user());

        assertThat(result.categories()).hasSize(1);
    }

    @Test
    public void throwsAiUnavailableWhenBothAttemptsFail() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("chain exhausted"));

        assertThatThrownBy(() -> service.suggest(categoriesRequest(List.of("Health")), user()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorKey()).isEqualTo(ErrorKey.AI_UNAVAILABLE));
    }
}
