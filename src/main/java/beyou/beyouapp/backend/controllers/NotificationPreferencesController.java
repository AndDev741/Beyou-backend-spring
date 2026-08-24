package beyou.beyouapp.backend.controllers;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.notification.preferences.NotificationPreferences;
import beyou.beyouapp.backend.notification.preferences.NotificationPreferencesService;
import beyou.beyouapp.backend.notification.preferences.dto.NotificationPreferencesResponseDTO;
import beyou.beyouapp.backend.notification.preferences.dto.UnsubscribeRequestDTO;
import beyou.beyouapp.backend.notification.preferences.dto.UpdateNotificationPreferencesDTO;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * The engagement-mail switch: two authenticated endpoints for the settings screen, and
 * one public one for the link inside a message.
 *
 * <p>The public endpoint is the whole reason this controller is not part of
 * {@code UserController}. Unsubscribing has to work for somebody who cannot log in —
 * that is the point of an unsubscribe link — so it is listed in
 * {@code SecurityConfig}'s permitAll set and bounded per address by
 * {@code RateLimitFilter}, because an unauthenticated write is otherwise unthrottled.
 */
@RestController
@RequiredArgsConstructor
public class NotificationPreferencesController {

    private final NotificationPreferencesService preferencesService;
    private final AuthenticatedUser authenticatedUser;

    @GetMapping("/notification/preferences")
    public ResponseEntity<NotificationPreferencesResponseDTO> getPreferences() {
        User user = authenticatedUser.getAuthenticatedUser();
        NotificationPreferences preferences = preferencesService.getOrCreate(user);
        return ResponseEntity.ok(new NotificationPreferencesResponseDTO(preferences.isEngagementEmail()));
    }

    @PutMapping("/notification/preferences")
    public ResponseEntity<NotificationPreferencesResponseDTO> updatePreferences(
            @RequestBody @Valid UpdateNotificationPreferencesDTO request) {
        User user = authenticatedUser.getAuthenticatedUser();
        NotificationPreferences preferences =
                preferencesService.setEngagementEmail(user, request.engagementEmail());
        return ResponseEntity.ok(new NotificationPreferencesResponseDTO(preferences.isEngagementEmail()));
    }

    /**
     * Turns engagement mail off for the holder of a token, with no session.
     *
     * <p>POST rather than GET, and the link in the mail points at a page in the app
     * which posts this. A GET would be simpler and is what a mail client can follow
     * directly — which is exactly the problem: Gmail and Outlook prefetch links to
     * render previews and scan for malware, so a state-changing GET gets "clicked" by a
     * robot and unsubscribes people who only opened the message.
     *
     * <p>An unknown token is refused rather than answered with a cheerful success. There
     * is nothing to enumerate — the token is random, not derived from the address — and
     * a user whose link has been mangled by a mail client is better told it did not work
     * than shown a confirmation for something that never happened.
     */
    @PostMapping("/notification/unsubscribe")
    public ResponseEntity<Map<String, String>> unsubscribe(@RequestBody @Valid UnsubscribeRequestDTO request) {
        if (!preferencesService.unsubscribeByToken(request.token())) {
            throw new BusinessException(ErrorKey.INVALID_REQUEST, "Invalid unsubscribe token");
        }
        return ResponseEntity.ok(Map.of("success", "Engagement emails disabled"));
    }
}
