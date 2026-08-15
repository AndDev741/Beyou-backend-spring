package beyou.beyouapp.backend.controllers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import beyou.beyouapp.backend.domain.xpday.XpHistoryService;
import beyou.beyouapp.backend.domain.xpday.dto.XpHistoryResponseDTO;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.User;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * XP over time, for every entity that has any.
 *
 * <p>Its own route rather than one under {@code /category}, because the series are not
 * a category's: four kinds of entity carry XP and the same window answers for all of
 * them. A dashboard asking for the week gets the best category, the worst category, the
 * habits and the user in one request instead of four.
 */
@RestController
// @Validated, or the bounds on the query param below are decoration: constraints on a
// @RequestParam are enforced by the method validation post-processor, which only looks
// at annotated classes. Same reason SearchDocsController carries it.
@Validated
@RequestMapping(value = "/xp")
public class XpHistoryController {

    @Autowired
    XpHistoryService xpHistoryService;

    @Autowired
    AuthenticatedUser authenticatedUser;

    /**
     * @param days window size, today inclusive. Bounded because the response is built in
     *             memory at one entry per day per entity.
     */
    @GetMapping("/history")
    public ResponseEntity<XpHistoryResponseDTO> history(
            @RequestParam(required = false)
            @Min(1) @Max(XpHistoryService.MAX_RANGE_DAYS) Integer days) {
        User user = authenticatedUser.getAuthenticatedUser();
        return ResponseEntity.ok(xpHistoryService.history(user, days));
    }
}
