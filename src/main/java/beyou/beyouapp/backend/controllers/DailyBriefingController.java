package beyou.beyouapp.backend.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import beyou.beyouapp.backend.domain.briefing.DailyBriefingService;
import beyou.beyouapp.backend.domain.briefing.dto.DailyBriefingResponseDTO;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.User;
import lombok.RequiredArgsConstructor;

/**
 * The dialog a user meets on the first dashboard open of their new day.
 *
 * <p>Two routes and no parameters. The day is never a query parameter, deliberately: it is
 * always the caller's own today, resolved from the account's timezone by
 * {@code UserDateResolver} (R15). Letting a client name the date would mean a client's clock
 * deciding which day a briefing describes and which row {@code seen} marks, and the two
 * clients would disagree the moment one of them was in a different zone from the account.
 *
 * <p>{@code GET} is get-or-create and may spend up to
 * {@code DailyBriefingService.NARRATIVE_DEADLINE} waiting on the model, which is why it has
 * a rate-limit tier of its own rather than falling into the generic 60-a-minute read bucket.
 * It answers 200 whatever the chain does — the facts are computed from the database and the
 * generated prose is the optional half, so there is no failure here worth showing a user.
 */
@RestController
@RequestMapping("/daily-briefing")
@RequiredArgsConstructor
public class DailyBriefingController {

    private final DailyBriefingService dailyBriefingService;
    private final AuthenticatedUser authenticatedUser;

    @GetMapping
    public ResponseEntity<DailyBriefingResponseDTO> getBriefing() {
        User user = authenticatedUser.getAuthenticatedUser();
        return ResponseEntity.ok(dailyBriefingService.briefingFor(user));
    }

    /**
     * Records that the user has seen today's dialog.
     *
     * <p>Server-side rather than in each client's local storage, which is the whole reason
     * this route exists: closing yesterday's loose ends on a phone has to close the dialog
     * on the web too. 204 because there is nothing useful to return — the client already
     * knows what it just did, and a body would only invite it to re-render.
     */
    @PostMapping("/seen")
    public ResponseEntity<Void> markSeen() {
        User user = authenticatedUser.getAuthenticatedUser();
        dailyBriefingService.markSeen(user);
        return ResponseEntity.noContent().build();
    }
}
