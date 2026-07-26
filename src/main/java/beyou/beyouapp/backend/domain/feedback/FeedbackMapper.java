package beyou.beyouapp.backend.domain.feedback;

import beyou.beyouapp.backend.domain.feedback.dto.CreateFeedbackRequestDTO;
import beyou.beyouapp.backend.domain.feedback.dto.FeedbackContextDTO;
import beyou.beyouapp.backend.domain.feedback.dto.FeedbackResponseDTO;
import beyou.beyouapp.backend.user.User;
import org.springframework.stereotype.Component;

@Component
public class FeedbackMapper {

    public Feedback toEntity(CreateFeedbackRequestDTO dto, User user) {
        Feedback feedback = new Feedback();
        feedback.setUser(user);
        feedback.setCategory(dto.category());
        feedback.setBody(dto.body().trim());
        feedback.setStatus(FeedbackStatus.OPEN);
        feedback.setContext(toContext(dto.context()));
        return feedback;
    }

    public FeedbackResponseDTO toResponseDTO(Feedback feedback) {
        FeedbackContext context = feedback.getContext() != null ? feedback.getContext() : new FeedbackContext();
        return new FeedbackResponseDTO(
                feedback.getId(),
                feedback.getCategory(),
                feedback.getBody(),
                new FeedbackContextDTO(
                        context.getScreen(),
                        context.getAppVersion(),
                        context.getPlatform(),
                        context.getLanguage(),
                        context.getTheme()),
                feedback.getCreatedAt());
    }

    private FeedbackContext toContext(FeedbackContextDTO dto) {
        FeedbackContext context = new FeedbackContext();
        if (dto == null) {
            return context;
        }
        context.setScreen(clamp(dto.screen(), FeedbackContext.SCREEN_MAX));
        context.setAppVersion(clamp(dto.appVersion(), FeedbackContext.APP_VERSION_MAX));
        context.setPlatform(clamp(dto.platform(), FeedbackContext.PLATFORM_MAX));
        context.setLanguage(clamp(dto.language(), FeedbackContext.LANGUAGE_MAX));
        context.setTheme(clamp(dto.theme(), FeedbackContext.THEME_MAX));
        return context;
    }

    /**
     * Belt-and-braces on top of the DTO's {@code @Size}: captured context is
     * machine-supplied, so a value that outgrows its column must be truncated
     * rather than cost the user their submission.
     */
    private String clamp(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
