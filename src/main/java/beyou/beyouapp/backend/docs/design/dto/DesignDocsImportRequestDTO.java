package beyou.beyouapp.backend.docs.design.dto;

public record DesignDocsImportRequestDTO(
    String repoOwner,
    String repoName,
    String branch,
    String path,
    String token
) {

}
