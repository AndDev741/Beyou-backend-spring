package beyou.beyouapp.backend.domain.mood;

import org.springframework.stereotype.Component;

import beyou.beyouapp.backend.domain.mood.dto.MoodEntryResponseDTO;

@Component
public class MoodMapper {

    public MoodEntryResponseDTO toResponseDTO(MoodEntry entry) {
        return new MoodEntryResponseDTO(
                entry.getId(),
                entry.getEntryDate(),
                entry.getMood(),
                entry.getNote(),
                entry.getUpdatedAt());
    }
}
