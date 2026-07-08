package beyou.beyouapp.backend.user;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class PhotoStorageService {

    private static final int MAX_DIMENSION = 512;
    private static final Set<String> ALLOWED_TYPES = Set.of(
        "image/jpeg", "image/png", "image/webp", "image/gif"
    );
    private static final long MAX_SIZE = 5 * 1024 * 1024; // 5MB

    private final Path uploadDir;

    public PhotoStorageService(@Value("${app.upload-dir}") String uploadDir) {
        this.uploadDir = Path.of(uploadDir).resolve("user-photos");
        try {
            Files.createDirectories(this.uploadDir);
        } catch (IOException e) {
            throw new RuntimeException("Could not create upload directory: " + this.uploadDir, e);
        }
    }

    /**
     * Validates, resizes, compresses, and saves a profile photo.
     * GIFs are flattened to their first frame (static JPEG output).
     */
    public void store(UUID userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorKey.PHOTO_UPLOAD_NO_FILE, "No file provided");
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            throw new BusinessException(ErrorKey.PHOTO_UPLOAD_INVALID_TYPE,
                "Photo must be JPEG, PNG, WebP, or GIF. Received: " + contentType);
        }

        if (file.getSize() > MAX_SIZE) {
            throw new BusinessException(ErrorKey.PHOTO_UPLOAD_TOO_LARGE,
                "Photo must be under 5MB. Received: " + (file.getSize() / (1024 * 1024)) + "MB");
        }

        Path dest = resolvePath(userId);
        try (InputStream in = file.getInputStream()) {
            BufferedImage original = ImageIO.read(in);
            if (original == null) {
                throw new BusinessException(ErrorKey.PHOTO_UPLOAD_CORRUPT,
                    "Could not read image data — file may be corrupt");
            }

            BufferedImage processed = resizeIfNeeded(original);
            Files.createDirectories(dest.getParent());
            ImageIO.write(processed, "jpg", dest.toFile());

            log.info("Photo stored for user {} at {} ({}x{} → {}x{})",
                userId, dest,
                original.getWidth(), original.getHeight(),
                processed.getWidth(), processed.getHeight());
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            log.error("Failed to store photo for user {}", userId, e);
            throw new BusinessException(ErrorKey.PHOTO_UPLOAD_CORRUPT,
                "Could not process image: " + e.getMessage());
        }
    }

    /** Returns the path on disk for a user's photo, or null if none exists. */
    public Path getPath(UUID userId) {
        Path path = resolvePath(userId);
        return Files.exists(path) ? path : null;
    }

    /** Returns the photo as a Spring Resource, or null if none exists. */
    public Resource serve(UUID userId) {
        Path path = resolvePath(userId);
        if (Files.exists(path)) {
            return new FileSystemResource(path);
        }
        return null;
    }

    /**
     * Returns the file's last-modified time in millis, or null if no local photo
     * exists. Used to version the photo URL so clients bust their image cache
     * exactly when the photo changes (the served URL is otherwise stable).
     */
    public Long getVersion(UUID userId) {
        Path path = resolvePath(userId);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            log.warn("Could not read last-modified time for user {} photo", userId, e);
            return null;
        }
    }

    // -- private helpers --

    private Path resolvePath(UUID userId) {
        return uploadDir.resolve(userId.toString() + ".jpg");
    }

    /** Cover-crop resize: fits into MAX_DIMENSION�MAX_DIMENSION, preserves aspect ratio. */
    private BufferedImage resizeIfNeeded(BufferedImage original) {
        int w = original.getWidth();
        int h = original.getHeight();
        if (w <= MAX_DIMENSION && h <= MAX_DIMENSION) {
            return original; // ponytail: no-op for already-small images
        }

        // Compute scaled dimensions covering the target square
        double scale = (double) MAX_DIMENSION / Math.max(w, h);
        int scaledW = (int) Math.round(w * scale);
        int scaledH = (int) Math.round(h * scale);

        BufferedImage scaled = new BufferedImage(scaledW, scaledH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(original, 0, 0, scaledW, scaledH, null);
        g.dispose();

        // Center crop to square
        int x = (scaledW - MAX_DIMENSION) / 2;
        int y = (scaledH - MAX_DIMENSION) / 2;
        if (x < 0) x = 0;
        if (y < 0) y = 0;
        int cropW = Math.min(MAX_DIMENSION, scaledW);
        int cropH = Math.min(MAX_DIMENSION, scaledH);

        return scaled.getSubimage(x, y, cropW, cropH);
    }
}
