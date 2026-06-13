package beyou.beyouapp.backend.domain.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import beyou.beyouapp.backend.domain.ai.dto.RoutineDraftDTO;
import beyou.beyouapp.backend.exceptions.ai.AiGenerationException;

/**
 * ChatClient-based generator. Provider-agnostic: which model answers is purely
 * a function of which spring-ai starter + properties are on the classpath.
 * Structured output via BeanOutputConverter ({format} placeholder in the
 * system template carries the JSON schema instructions).
 */
@Component
@Profile("!e2e")
public class SpringAiRoutineDraftGenerator implements RoutineDraftGenerator {

    // Local instance: Spring Boot 4 auto-configures Jackson 3 (tools.jackson),
    // so there is no com.fasterxml ObjectMapper bean to inject.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ChatClient chatClient;
    private final Resource systemTemplate;
    private final BeanOutputConverter<RoutineDraftDTO> outputConverter =
            new BeanOutputConverter<>(RoutineDraftDTO.class);

    public SpringAiRoutineDraftGenerator(ChatClient.Builder chatClientBuilder,
            @Value("classpath:/prompts/routine-generation.st") Resource systemTemplate) {
        this.chatClient = chatClientBuilder.build();
        this.systemTemplate = systemTemplate;
    }

    @Override
    public RoutineDraftDTO generate(GenerationContext ctx) {
        String content = chatClient.prompt()
                .system(s -> s.text(systemTemplate)
                        .param("language", ctx.language())
                        .param("iconCatalog", AiIconCatalog.promptCatalog())
                        .param("userContext", ctx.userContextJson())
                        .param("format", outputConverter.getFormat()))
                .user(buildUserMessage(ctx))
                .call()
                .content();

        RoutineDraftDTO draft = outputConverter.convert(content);
        if (draft == null) {
            throw new AiGenerationException("AI returned unparseable content");
        }
        return draft;
    }

    private String buildUserMessage(GenerationContext ctx) {
        StringBuilder message = new StringBuilder(ctx.description());
        if (ctx.previousDraft() != null) {
            try {
                message.append("\n\nPrevious draft (JSON): ")
                        .append(OBJECT_MAPPER.writeValueAsString(ctx.previousDraft()));
            } catch (JsonProcessingException e) {
                throw new AiGenerationException("Failed to serialize previous draft", e);
            }
        }
        if (ctx.feedback() != null && !ctx.feedback().isBlank()) {
            message.append("\nAdjustment request: ").append(ctx.feedback());
        }
        return message.toString();
    }
}
