package beyou.beyouapp.backend.user;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

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
    private static final long MAX_SIZE = 5 * 1024 * 1024; // 5MB compressed
    // Decoded-pixel ceiling checked from the image header BEFORE ImageIO.read
    // allocates the full raster — a highly compressible image can stay under
    // MAX_SIZE on the wire yet decode to gigabytes ("decompression bomb").
    private static final long MAX_PIXELS = 25_000_000L; // ~25MP (e.g. 6000x4166)

    private final Path uploadDir;

    public PhotoStorageService(@Value("${app.upload-dir}") String uploadDir) {
        this.uploadDir = Path.of(uploadDir).resolve("user-photos");
        try {
            Files.createDirectories(this.uploadDir);
        } catch (IOException e) {
            // Best-effort at startup. Photo upload is an optional feature — a
            // non-writable dir must NOT take down the whole application context
            // (auth, routines, etc.). store() recreates the directory on the write
            // path and surfaces a proper PHOTO_UPLOAD error if it genuinely can't.
            log.warn("Could not pre-create upload directory {} at startup; will retry on first upload",
                this.uploadDir, e);
        }
    }

    /**
     * Validates, resizes, flattens, and saves a profile photo as a static JPEG.
     * All inputs are re-encoded to opaque RGB, so transparent PNG/WebP/GIF and
     * animated GIF (first frame) are supported; alpha flattens to white.
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

        ensureWithinPixelBounds(file);

        Path dest = resolvePath(userId);
        Path tmp = null;
        try (InputStream in = file.getInputStream()) {
            BufferedImage original = ImageIO.read(in);
            if (original == null) {
                throw new BusinessException(ErrorKey.PHOTO_UPLOAD_CORRUPT,
                    "Could not read image data — file may be corrupt or an unsupported format");
            }

            BufferedImage processed = toOpaqueRgb(original);
            Files.createDirectories(dest.getParent());

            // Write to a sibling temp file then atomically swap in, so a concurrent
            // GET never observes a half-written file and a failed encode never
            // clobbers the existing photo.
            tmp = Files.createTempFile(dest.getParent(), userId.toString() + "-", ".jpg.tmp");
            if (!ImageIO.write(processed, "jpg", tmp.toFile())) {
                throw new BusinessException(ErrorKey.PHOTO_UPLOAD_CORRUPT,
                    "No JPEG encoder available for this image");
            }
            Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            tmp = null;

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
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // best-effort cleanup of the temp file on the failure path
                }
            }
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

    /**
     * Rejects images whose header reports more than MAX_PIXELS before the full
     * raster is allocated. Reads only the dimensions, not the pixels.
     */
    private void ensureWithinPixelBounds(MultipartFile file) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(file.getInputStream())) {
            if (iis == null) {
                return; // let ImageIO.read fail later with PHOTO_UPLOAD_CORRUPT
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                return; // unknown format — decode path will throw PHOTO_UPLOAD_CORRUPT
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis);
                long w = reader.getWidth(0);
                long h = reader.getHeight(0);
                if (w * h > MAX_PIXELS) {
                    throw new BusinessException(ErrorKey.PHOTO_UPLOAD_TOO_LARGE,
                        "Image dimensions too large: " + w + "x" + h);
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            // Header unreadable — defer to ImageIO.read, which throws PHOTO_UPLOAD_CORRUPT.
            log.debug("Could not read image header for pixel-bound check", e);
        }
    }

    /**
     * Downscales to fit within MAX_DIMENSION x MAX_DIMENSION (aspect preserved,
     * never upscaled) and always draws onto an opaque TYPE_INT_RGB canvas.
     * The RGB flatten is mandatory: the JDK JPEG writer silently fails on
     * alpha-bearing images (returns false, produces a 0-byte file), so every
     * image — small or large, transparent or not — is flattened here. Transparent
     * regions composite onto white. The display layer crops to a circle/square.
     */
    private BufferedImage toOpaqueRgb(BufferedImage original) {
        int w = original.getWidth();
        int h = original.getHeight();
        int targetW = w;
        int targetH = h;
        if (w > MAX_DIMENSION || h > MAX_DIMENSION) {
            double scale = (double) MAX_DIMENSION / Math.max(w, h);
            targetW = Math.max(1, (int) Math.round(w * scale));
            targetH = Math.max(1, (int) Math.round(h * scale));
        }

        BufferedImage rgb = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, targetW, targetH);
        g.drawImage(original, 0, 0, targetW, targetH, null);
        g.dispose();
        return rgb;
    }
}
