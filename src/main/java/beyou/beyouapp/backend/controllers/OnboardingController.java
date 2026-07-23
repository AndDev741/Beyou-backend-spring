package beyou.beyouapp.backend.controllers;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import beyou.beyouapp.backend.domain.aiAgent.onboarding.OnboardingSuggestionService;
import beyou.beyouapp.backend.domain.aiAgent.onboarding.dto.OnboardingSuggestionRequest;
import beyou.beyouapp.backend.domain.aiAgent.onboarding.dto.OnboardingSuggestions;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

    private final OnboardingSuggestionService suggestionService;
    private final AuthenticatedUser authenticatedUser;

    @PostMapping("/suggestions")
    public OnboardingSuggestions suggest(@RequestBody @Valid OnboardingSuggestionRequest request) {
        return suggestionService.suggest(request, authenticatedUser.getAuthenticatedUser());
    }
}
