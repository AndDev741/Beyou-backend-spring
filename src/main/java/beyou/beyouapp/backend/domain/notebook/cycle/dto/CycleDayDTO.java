package beyou.beyouapp.backend.domain.notebook.cycle.dto;

import java.time.DayOfWeek;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * One weekday of a cycle's week, in a request and in a response alike.
 *
 * @param minutes   0..720 in steps of 5; 0 is a rest day. The step is checked by the service,
 *                  which says so in the refusal
 * @param startTime "HH:mm", or null for a day with an order but no clock
 */
public record CycleDayDTO(
        @NotNull DayOfWeek weekday,
        @NotNull @Min(0) @Max(720) Integer minutes,
        @Pattern(regexp = CycleDayDTO.HH_MM, message = "must be HH:mm") String startTime) {

    public static final String HH_MM = "^([01]\\d|2[0-3]):[0-5]\\d$";
}
