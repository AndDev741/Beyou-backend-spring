package beyou.beyouapp.backend.controllers.docs;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import beyou.beyouapp.backend.docs.api.dto.ApiDocsImportRequestDTO;
import beyou.beyouapp.backend.docs.api.dto.ApiDocsImportResultDTO;
import beyou.beyouapp.backend.docs.api.imp.ApiDocsImportService;
import beyou.beyouapp.backend.docs.architecture.dto.ArchitectureDocsImportRequestDTO;
import beyou.beyouapp.backend.docs.architecture.dto.ArchitectureDocsImportResultDTO;
import beyou.beyouapp.backend.docs.architecture.imp.ArchitectureDocsImportService;
import beyou.beyouapp.backend.docs.blog.dto.BlogDocsImportRequestDTO;
import beyou.beyouapp.backend.docs.blog.dto.BlogDocsImportResultDTO;
import beyou.beyouapp.backend.docs.blog.imp.BlogDocsImportService;
import beyou.beyouapp.backend.docs.project.dto.ProjectDocsImportRequestDTO;
import beyou.beyouapp.backend.docs.project.dto.ProjectDocsImportResultDTO;
import beyou.beyouapp.backend.docs.project.imp.ProjectDocsImportService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping(value = "/docs/admin/import")
@RequiredArgsConstructor
public class DocsImportController {

    private final ArchitectureDocsImportService importService;
    private final BlogDocsImportService blogImportService;
    private final ApiDocsImportService apiDocsImportService;
    private final ProjectDocsImportService projectImportService;

    @PostMapping("/architecture")
    public ResponseEntity<Map<String, Object>> importArchitecture(
        @RequestBody(required = false) ArchitectureDocsImportRequestDTO request
    ) {
        ArchitectureDocsImportResultDTO result = importService.importFromGitHub(request);

        return ResponseEntity.ok(Map.of(
            "success", true,
            "importedTopics", result.importedTopics(),
            "archivedTopics", result.archivedTopics()
        ));
    }

    @PostMapping("/blog")
    public ResponseEntity<Map<String, Object>> importBlog(
        @RequestBody(required = false) BlogDocsImportRequestDTO request
    ) {
        BlogDocsImportResultDTO result = blogImportService.importFromGitHub(request);

        return ResponseEntity.ok(Map.of(
            "success", true,
            "importedTopics", result.importedTopics(),
            "archivedTopics", result.archivedTopics()
        ));
    }

    @PostMapping("/api")
    public ResponseEntity<Map<String, Object>> importApi(
        @RequestBody(required = false) ApiDocsImportRequestDTO request
    ) {
        ApiDocsImportResultDTO result = apiDocsImportService.importFromGitHub(request);

        return ResponseEntity.ok(Map.of(
            "success", true,
            "importedTopics", result.importedTopics(),
            "archivedTopics", result.archivedTopics()
        ));
    }

    @PostMapping("/projects")
    public ResponseEntity<Map<String, Object>> importProjects(
        @RequestBody(required = false) ProjectDocsImportRequestDTO request
    ) {
        ProjectDocsImportResultDTO result = projectImportService.importFromGitHub(request);

        return ResponseEntity.ok(Map.of(
            "success", true,
            "importedTopics", result.importedTopics(),
            "archivedTopics", result.archivedTopics()
        ));
    }
}
