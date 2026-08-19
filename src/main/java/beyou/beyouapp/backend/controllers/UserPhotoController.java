package beyou.beyouapp.backend.controllers;

import java.util.Map;
import java.util.UUID;

import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.PhotoStorageService;
import beyou.beyouapp.backend.user.PhotoUrlSigner;
import beyou.beyouapp.backend.user.User;

@RestController
@RequestMapping("/user/photo")
public class UserPhotoController {

    private final PhotoStorageService photoStorageService;
    private final AuthenticatedUser authenticatedUser;
    private final PhotoUrlSigner photoUrlSigner;

    public UserPhotoController(PhotoStorageService photoStorageService,
                               AuthenticatedUser authenticatedUser,
                               PhotoUrlSigner photoUrlSigner) {
        this.photoStorageService = photoStorageService;
        this.authenticatedUser = authenticatedUser;
        this.photoUrlSigner = photoUrlSigner;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> upload(
            @RequestParam("file") MultipartFile file) {
        User user = authenticatedUser.getAuthenticatedUser();
        photoStorageService.store(user.getId(), file);
        return ResponseEntity.ok(Map.of("message", "Photo uploaded"));
    }

    /**
     * Serves a profile photo, and only to whoever was handed a signed link to it.
     *
     * <p>This used to answer any caller who could name a user id, which meant every
     * uploaded face was readable by anyone walking the UUID space. It stays outside
     * the JWT filter because the callers are an {@code <img src>} and an
     * {@code <Image uri>}, neither of which can send a header — the proof rides in
     * the query string instead, minted by {@code PhotoUrlSigner} into the one
     * response only the owner can read.
     *
     * <p>An unsigned or expired request is answered 403 rather than 404: the file's
     * existence is not the secret here, and a 404 would have the endpoint reporting
     * which accounts have a photo to callers holding nothing.
     */
    @GetMapping("/{userId}")
    public ResponseEntity<Resource> serve(@PathVariable UUID userId,
                                          @RequestParam(name = "exp", required = false) String exp,
                                          @RequestParam(name = "sig", required = false) String sig) {
        if (!photoUrlSigner.isValid(userId, exp, sig)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Resource resource = photoStorageService.serve(userId);
        if (resource == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
            .contentType(MediaType.IMAGE_JPEG)
            // Private, not public: the URL is now a capability, and a shared cache
            // holding the bytes would hand them out after the signature expired.
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
            .body(resource);
    }
}
