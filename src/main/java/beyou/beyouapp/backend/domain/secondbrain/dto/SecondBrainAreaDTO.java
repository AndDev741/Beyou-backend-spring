package beyou.beyouapp.backend.domain.secondbrain.dto;

import java.util.UUID;

/**
 * One life area (a category) over the last 14 days ending yesterday.
 *
 * @param asked   routine items of this category that came up and were not skipped, counted once
 *                per day they came up
 * @param checked how many of those were checked
 * @param percent checked over asked, rounded
 */
public record SecondBrainAreaDTO(UUID categoryId, String name, String iconId, int asked, int checked, Integer percent) {}
