package beyou.beyouapp.backend.domain.secondbrain.dto;

import java.util.List;

/**
 * Whether the person keeps to their routines: the seven days ending yesterday against the seven
 * before. The same numbers the briefing's narrator reads ({@code WeekSignals}).
 *
 * @param thisWeekPercent     share of the items asked that were checked, skips out of both
 *                            sides. Null when nothing was asked, which is every first week
 * @param previousWeekPercent the same for the week before, null likewise
 * @param slipping            items left open on most of the days they came up this week
 * @param steady              items checked on every day they came up this week
 */
public record SecondBrainRoutineDTO(
    Integer thisWeekPercent,
    Integer previousWeekPercent,
    List<SecondBrainRoutineItemDTO> slipping,
    List<SecondBrainRoutineItemDTO> steady
) {}
