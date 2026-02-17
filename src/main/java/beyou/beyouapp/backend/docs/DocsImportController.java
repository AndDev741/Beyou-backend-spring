package beyou.beyouapp.backend.docs;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import beyou.beyouapp.backend.docs.architecture.dto.ArchitectureDocsImportRequestDTO;
import beyou.beyouapp.backend.docs.architecture.dto.ArchitectureDocsImportResultDTO;
import beyou.beyouapp.backend.docs.architecture.imp.ArchitectureDocsImportService;

@RestController
@RequestMapping(value = "/docs/admin/import")
public class DocsImportController {
    @Autowired
    private ArchitectureDocsImportService importService;

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
}
