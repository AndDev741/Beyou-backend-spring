package beyou.beyouapp.backend.controllers;

import beyou.beyouapp.backend.user.UserExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class UserExportController {

    private final UserExportService userExportService;

    @GetMapping("/user/export")
    public ResponseEntity<Map<String, Object>> exportUserData() {
        return ResponseEntity.ok(userExportService.exportUserData());
    }
}
