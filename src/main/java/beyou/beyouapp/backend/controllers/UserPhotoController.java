package beyou.beyouapp.backend.controllers;

import java.util.Map;
import java.util.UUID;

import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.PhotoStorageService;
import beyou.beyouapp.backend.user.User;

@RestController
@RequestMapping("/user/photo")
public class UserPhotoController {

    private final PhotoStorageService photoStorageService;
    private final AuthenticatedUser authenticatedUser;

    public UserPhotoController(PhotoStorageService photoStorageService,
                               AuthenticatedUser authenticatedUser) {
        this.photoStorageService = photoStorageService;
        this.authenticatedUser = authenticatedUser;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> upload(
            @RequestParam("file") MultipartFile file) {
        User user = authenticatedUser.getAuthenticatedUser();
        photoStorageService.store(user.getId(), file);
        return ResponseEntity.ok(Map.of("message", "Photo uploaded"));
    }

    @GetMapping
    public ResponseEntity<Resource> serve() {
        User user = authenticatedUser.getAuthenticatedUser();
        Resource resource = photoStorageService.serve(user.getId());
        if (resource == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
            .contentType(MediaType.IMAGE_JPEG)
            .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
            .body(resource);
    }
}
