package beyou.beyouapp.backend.security.aiconsent;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers {@link AiConsentInterceptor} on every route; it picks its own routes. */
@Configuration
public class AiConsentWebConfig implements WebMvcConfigurer {

    private final AiConsentInterceptor aiConsentInterceptor;

    public AiConsentWebConfig(AiConsentInterceptor aiConsentInterceptor) {
        this.aiConsentInterceptor = aiConsentInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(aiConsentInterceptor);
    }
}
