package beyou.beyouapp.backend.security.aiconsent;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method whose work goes to the AI provider: the model, the embedding model
 * or the source search. {@link AiConsentInterceptor} refuses it unless the caller said yes.
 *
 * <p>On the handler rather than in a list of paths, so a route cannot slip past by being
 * renamed or moved. A new route that calls a provider carries it from the start;
 * {@code SendsToAiProviderCoverageTest} lists the ones that must.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SendsToAiProvider {
}
