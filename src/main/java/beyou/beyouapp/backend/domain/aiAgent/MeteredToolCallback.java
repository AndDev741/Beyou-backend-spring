package beyou.beyouapp.backend.domain.aiAgent;

import java.util.function.Consumer;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import beyou.beyouapp.backend.domain.aiAgent.dto.AgentEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

/**
 * Times every agent tool execution as `beyou.ai.tool` (tags: tool, error).
 * Spring AI's own spring.ai.tool observation never reaches the meter registry
 * on the method-tools path, so we own the metric at the callback boundary —
 * the same hook a future streaming "agent is working" event will use.
 */
@Slf4j
public class MeteredToolCallback implements ToolCallback {

    public static final String METRIC_NAME = "beyou.ai.tool";
    public static final String EVENTS_KEY = "agentEvents";

    private final ToolCallback delegate;
    private final MeterRegistry meterRegistry;

    public MeteredToolCallback(ToolCallback delegate, MeterRegistry meterRegistry) {
        this.delegate = delegate;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return timed(() -> delegate.call(toolInput));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        Consumer<AgentEvent> events = eventsFrom(toolContext);
        String name = delegate.getToolDefinition().name();

        try{
            emit(events, AgentEvent.toolStarted(name));
            String timedResult = timed(() -> delegate.call(toolInput, toolContext));
            
            emit(events, AgentEvent.toolFinished(name, null, AgentToolDomains.domainsOf(name)));
            
            return timedResult;
        }catch(RuntimeException e){
            emit(events, AgentEvent.toolFinished(name, e.getClass().getSimpleName(), AgentToolDomains.domainsOf(name)));
            throw e;
        }
    }

    private void emit(Consumer<AgentEvent> events, AgentEvent event){
        if(events == null) return;

        try{
            events.accept(event);
        }catch(RuntimeException e){
            log.error("Error on send event {}", e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private Consumer<AgentEvent> eventsFrom(ToolContext toolContext){
        if(toolContext == null) return null;
        return (Consumer<AgentEvent>) toolContext.getContext().get(EVENTS_KEY);
    }

    private String timed(java.util.function.Supplier<String> execution) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String error = "none";
        try {
            return execution.get();
        } catch (RuntimeException e) {
            error = e.getClass().getSimpleName();
            throw e;
        } finally {
            sample.stop(Timer.builder(METRIC_NAME)
                    .description("AI agent tool executions")
                    .tag("tool", delegate.getToolDefinition().name())
                    .tag("error", error)
                    .register(meterRegistry));
        }
    }
}
