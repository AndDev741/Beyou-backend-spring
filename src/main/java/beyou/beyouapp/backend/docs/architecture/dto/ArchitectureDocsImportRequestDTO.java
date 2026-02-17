package beyou.beyouapp.backend.docs.architecture.dto;

public record ArchitectureDocsImportRequestDTO(
    String repoOwner,
    String repoName,
    String branch,
    String path,
    String token
) {

}
