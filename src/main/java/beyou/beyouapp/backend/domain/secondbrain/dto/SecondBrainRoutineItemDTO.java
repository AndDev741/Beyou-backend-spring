package beyou.beyouapp.backend.domain.secondbrain.dto;

/**
 * One routine item's week, counted per time it came up: an item in two routines, or twice in
 * one, counts twice on the same day.
 *
 * @param asked   times it came up, skipped ones included
 * @param checked times it was checked
 * @param skipped times it was skipped. On a slipping item this says whether it was dropped on
 *                purpose or forgotten; a steady item always has zero
 */
public record SecondBrainRoutineItemDTO(String name, int asked, int checked, int skipped) {}
