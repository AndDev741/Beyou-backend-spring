package beyou.beyouapp.backend.domain.notebook.cycle.dto;

import java.util.UUID;

import beyou.beyouapp.backend.domain.notebook.NotebookStatus;
import beyou.beyouapp.backend.domain.notebook.dto.ProgressDTO;

/** The concept a GOAL block aims at: the page, where it stands, and its leaves done of total. */
public record TargetDTO(UUID id, String title, String icon, NotebookStatus status, ProgressDTO progress) {
}
