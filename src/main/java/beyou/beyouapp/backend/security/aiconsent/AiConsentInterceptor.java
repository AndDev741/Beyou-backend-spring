package beyou.beyouapp.backend.security.aiconsent;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.fasterxml.jackson.databind.ObjectMapper;

import beyou.beyouapp.backend.exceptions.ApiErrorResponse;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.enums.AiConsent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Turns away every request that would send the caller's data to the AI provider, when the
 * caller said no to that ({@link AiConsent#DECLINED}).
 *
 * <p>The phone hides the AI features after a no, so this is the server keeping the promise
 * for every client: an older app, the web (which does not ask yet), or a screen nobody
 * thought of. One list of routes in one place, the same routes {@code RateLimitFilter} already
 * treats as model calls, rather than a check in each service.
 *
 * <p>Only DECLINED is refused. UNASKED passes, so accounts nobody has asked yet (every web
 * account) keep working as before.
 *
 * <p>The answer is written here rather than thrown: the agent's stream route produces
 * {@code text/event-stream}, and a thrown error would go through content negotiation and
 * come back as a 406 instead of the error key. The status is 400 like any business error,
 * because the clients read a 403 as a session problem.
 *
 * <p>The daily briefing is not a route here: its facts come from Beyou's own database and
 * keep working, and {@code DailyBriefingService} skips the prose on its own.
 */
@Component
public class AiConsentInterceptor implements HandlerInterceptor {

    // Its own instance, like SecurityFilter's: the body is one flat record.
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** True for a route whose work goes to the AI provider (model, embeddings or search). */
    static boolean sendsToAiProvider(String method, String path) {
        if (!"POST".equals(method)) {
            return false;
        }
        return path.startsWith("/ai/agent/chats/")
                || path.equals("/onboarding/suggestions")
                || path.startsWith("/notebook/ai/")
                // Adding a study source sends its text to the embedding model.
                || (path.startsWith("/notebook/pages/") && path.contains("/sources/"))
                || path.equals("/second-brain/questions/refill")
                || path.equals("/second-brain/insight/refresh")
                || (path.startsWith("/second-brain/questions/") && path.endsWith("/answer"));
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!sendsToAiProvider(request.getMethod(), stripContextPath(request))) {
            return true;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof User user)
                || user.getAiConsent() != AiConsent.DECLINED) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiErrorResponse body = new ApiErrorResponse(ErrorKey.AI_CONSENT_DECLINED.name(),
                "AI features are off for this account. They can be turned on in Configuration.", null);
        response.getWriter().write(objectMapper.writeValueAsString(body));
        response.getWriter().flush();
        return false;
    }

    // getServletPath() is empty under MockMvc, so the context path is stripped by hand, the
    // same way RateLimitFilter and SecurityFilter do it.
    private static String stripContextPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }
}
