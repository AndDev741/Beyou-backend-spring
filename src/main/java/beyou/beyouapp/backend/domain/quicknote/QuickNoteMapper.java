package beyou.beyouapp.backend.domain.quicknote;

import org.springframework.stereotype.Component;

import beyou.beyouapp.backend.domain.quicknote.dto.QuickNoteResponseDTO;

@Component
public class QuickNoteMapper {

    public QuickNoteResponseDTO toResponseDTO(QuickNote note) {
        return new QuickNoteResponseDTO(
                note.getId(),
                note.getText(),
                note.getColor(),
                note.getX(),
                note.getY(),
                note.getZ(),
                note.getArchivedAt(),
                note.getConvertedTaskId(),
                note.getCreatedAt(),
                note.getUpdatedAt());
    }
}
