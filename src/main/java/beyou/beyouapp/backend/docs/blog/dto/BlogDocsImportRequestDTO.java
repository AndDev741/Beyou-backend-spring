package beyou.beyouapp.backend.docs.blog.dto;

public record BlogDocsImportRequestDTO(
    String repoOwner,
    String repoName,
    String branch,
    String path,
    String token
) {

}
