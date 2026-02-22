package beyou.beyouapp.backend.docs.project.dto;

public record ProjectDocsImportRequestDTO(
    String repoOwner,
    String repoName,
    String branch,
    String path,
    String token
) {
}