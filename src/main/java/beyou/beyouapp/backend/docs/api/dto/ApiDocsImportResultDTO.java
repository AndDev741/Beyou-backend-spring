package beyou.beyouapp.backend.docs.api.dto;

public record ApiDocsImportResultDTO(
    int importedTopics,
    int archivedTopics
) {
}