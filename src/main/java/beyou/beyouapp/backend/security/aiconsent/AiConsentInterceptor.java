package beyou.beyouapp.backend.security.aiconsent;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import com.fasterxml.jackson.databind.ObjectMapper;

import beyou.beyouapp.backend.exceptions.ApiErrorResponse;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Turns away every {@link SendsToAiProvider} route unless the caller said yes to the AI
 * provider ({@code AiConsent.GRANTED}). Never asked counts as no: App Store guideline 5.1.2(i)
 * wants the permission before the data leaves, and both clients now ask first.
 *
 * <p>Keyed on the resolved handler, not the URL, so a route cannot slip past by being renamed.
 *
 * <p>The answer is written here rather than thrown: the agent's stream route produces
 * {@code text/event-stream}, and a thrown error would go through content negotiation and come
 * back as a 406 instead of the error key. 400 like any business error, because the clients
 * read a 403 as a session problem.
 *
 * <p>The daily briefing is not refused: its facts are Beyou's own, and
 * {@code DailyBriefingService} leaves the prose out on its own.
 */
@Component
public class AiConsentInterceptor implements HandlerInterceptor {

    // Its own instance, like SecurityFilter's: the body is one flat record.
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!(handler instanceof HandlerMethod method) || !method.hasMethodAnnotation(SendsToAiProvider.class)) {
            return true;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
            // Not signed in: SecurityFilter has already answered, or the route is a test
            // harness without one. Nothing here reaches a provider without a user.
            return true;
        }
        if (AiConsentPolicy.allows(user.getAiConsent())) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiErrorResponse body = new ApiErrorResponse(ErrorKey.AI_CONSENT_DECLINED.name(),
                "AI features are off for this account until it allows them in Configuration.", null);
        response.getWriter().write(objectMapper.writeValueAsString(body));
        response.getWriter().flush();
        return false;
    }
}
