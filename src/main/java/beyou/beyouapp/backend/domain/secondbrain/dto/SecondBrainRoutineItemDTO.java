package beyou.beyouapp.backend.domain.secondbrain.dto;

/**
 * One routine item's week.
 *
 * @param asked   days it came up and was not skipped
 * @param checked days it was checked
 */
public record SecondBrainRoutineItemDTO(String name, int asked, int checked) {}
