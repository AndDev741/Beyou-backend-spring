package beyou.beyouapp.backend.docs.api.dto;

public record ApiDocsImportRequestDTO(
    String repoOwner,
    String repoName,
    String branch,
    String path,
    String token
) {
}