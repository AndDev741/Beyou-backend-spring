package beyou.beyouapp.backend.controllers;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import beyou.beyouapp.backend.domain.checkday.CheckDayOwnerType;
import beyou.beyouapp.backend.domain.checkday.CheckHistoryService;
import beyou.beyouapp.backend.domain.checkday.dto.CheckDayResponseDTO;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.User;
import lombok.RequiredArgsConstructor;

/**
 * KTD23 — one owner-parameterised route for the day history of every checkable thing:
 * habits, recurring tasks, routines and the account itself.
 *
 * <p>Sits at its own path rather than under {@code /habit} and {@code /routine} the way the
 * rest of the controllers are laid out. Four owner types write these rows, and a
 * per-resource layout would realistically have shipped the habit reader first and left the
 * other three written but unreadable — the dashboard's constance widget reads the account's
 * rows on day one.
 *
 * <p>Falls into {@code RateLimitFilter}'s generic authenticated-GET tier with no wiring,
 * and into {@code SecurityConfig}'s {@code anyRequest().authenticated()}.
 */
@RestController
@RequestMapping("/check-history")
@RequiredArgsConstructor
public class CheckHistoryController {

    private final CheckHistoryService checkHistoryService;
    private final AuthenticatedUser authenticatedUser;

    /**
     * One owner's history, one entry per day in the effective range.
     *
     * @param ownerType {@code HABIT}, {@code TASK}, {@code ROUTINE} or {@code USER}
     * @param ownerId   which one; omit for {@code USER} to get the caller's own
     * @param from      first day, inclusive; omit for {@code to} minus twenty-eight days
     * @param to        last day, inclusive; omit for the caller's today in their own zone
     */
    @GetMapping
    public ResponseEntity<CheckDayResponseDTO> getCheckHistory(
            // Not `required = true`: a missing required param is a
            // MissingServletRequestParameterException, which lands outside the errorKey
            // envelope for the same reason a misspelled enum does. Let it arrive null and
            // be refused below, where the answer has a key on it.
            @RequestParam(required = false) String ownerType,
            @RequestParam(required = false) UUID ownerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        User user = authenticatedUser.getAuthenticatedUser();
        return ResponseEntity.ok(checkHistoryService.history(
                user, parseOwnerType(ownerType), ownerId, from, to));
    }

    /**
     * Binds the owner type by hand instead of letting Spring convert the enum.
     *
     * <p>A misspelled enum in a query string surfaces as {@code MethodArgumentTypeMismatch},
     * which {@code GlobalExceptionHandler} does not handle, so Spring's default resolver
     * answers a bare 400 with an empty body — outside the {@code errorKey} envelope every
     * client parses, and with nothing in it to say which parameter was wrong. Parsing here
     * keeps the envelope and names the accepted values, without changing how every other
     * endpoint in the app reports a bad parameter.
     */
    private static CheckDayOwnerType parseOwnerType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorKey.INVALID_REQUEST, "ownerType is required");
        }
        try {
            return CheckDayOwnerType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorKey.INVALID_REQUEST,
                    "Unknown ownerType '" + raw + "'. Expected one of "
                            + Arrays.stream(CheckDayOwnerType.values())
                                    .map(Enum::name)
                                    .collect(Collectors.joining(", ")));
        }
    }
}
