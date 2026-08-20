package beyou.beyouapp.backend.domain.feedback;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import lombok.extern.slf4j.Slf4j;

/**
 * Bytes on disk for feedback attachments (R3, R9).
 *
 * <p>KTD2 — deliberately its own service rather than a parameterised
 * {@code PhotoStorageService}: that one downscales to 512 px and names files
 * {@code {userId}.jpg}, so it can hold neither a legible screenshot nor more
 * than one image per owner. What IS carried over, in the same order, is the
 * hardening sequence:
 *
 * <ol>
 *   <li>MIME allowlist</li>
 *   <li>byte ceiling</li>
 *   <li>header-derived pixel-bound check <em>before</em> decode — the
 *       decompression-bomb guard</li>
 *   <li>decode</li>
 *   <li>re-encode to opaque RGB (strips alpha, animation, and any embedded
 *       metadata or payload the original carried)</li>
 *   <li>write to a temporary sibling</li>
 *   <li>atomic move into place</li>
 * </ol>
 *
 * <p>Steps 1-5 live in {@link #validateAndEncode(MultipartFile)} and touch no
 * disk at all; steps 6-7 live in {@link #write}. Splitting them lets the caller
 * persist a row carrying the real dimensions <em>before</em> anything is
 * written, so a failed write rolls the row back and a failed save leaves no
 * orphan file.
 *
 * <p>Paths are derived exclusively from UUIDs supplied by the caller — no part
 * of an uploaded filename ever reaches the filesystem.
 */
@Service
@Slf4j
public class FeedbackAttachmentStorageService {

    /**
     * Longest-edge ceiling, in pixels.
     *
     * <p>1920 rather than the profile photo's 512: an attachment is usually a
     * screenshot, and its value is the interface text inside it. A 2x-DPR phone
     * capture (1170x2532) or a 1080p desktop capture lands here at or near its
     * native CSS-pixel size, so text stays legible; a 4K capture halves to
     * 1920 wide, which is still one CSS pixel per stored pixel on a 2x display.
     * At 512 px every one of those is an unreadable smudge, which would make
     * the attachment worthless for triage.
     */
    public static final int MAX_DIMENSION = 1920;

    /** Byte ceiling on the wire. Matches the profile photo's, and sits under the 6MB multipart cap. */
    public static final long MAX_SIZE = 5L * 1024 * 1024;

    /**
     * Decoded-pixel ceiling checked from the image header BEFORE
     * {@code ImageIO.read} allocates the full raster — a highly compressible
     * image can stay under {@link #MAX_SIZE} on the wire yet decode to
     * gigabytes ("decompression bomb").
     */
    public static final long MAX_PIXELS = 25_000_000L; // ~25MP

    /** JPEG quality. Higher than the encoder default because text is what has to survive. */
    private static final float JPEG_QUALITY = 0.92f;

    private static final Set<String> ALLOWED_TYPES = Set.of(
        "image/jpeg", "image/png", "image/webp", "image/gif"
    );

    /** Content type every stored attachment is served as — everything is re-encoded to JPEG. */
    public static final String STORED_CONTENT_TYPE = "image/jpeg";

    private final Path uploadDir;

    public FeedbackAttachmentStorageService(@Value("${app.upload-dir}") String uploadDir) {
        this.uploadDir = Path.of(uploadDir).resolve("feedback-attachments");
        try {
            Files.createDirectories(this.uploadDir);
        } catch (IOException e) {
            // Best-effort at startup, same rationale as PhotoStorageService: a
            // non-writable directory must not take the application context down.
            // write() recreates the directory and surfaces a proper error.
            log.warn("Could not pre-create feedback attachment directory {} at startup; will retry on first upload",
                this.uploadDir, e);
        }
    }

    /**
     * A validated, downscaled, re-encoded JPEG held in memory, ready to write.
     *
     * <p>The array is not defensively copied — it is handed straight from
     * {@link #validateAndEncode} to {@link #write} and never shared.
     */
    public record EncodedAttachment(byte[] jpeg, int width, int height) {
        public long sizeBytes() {
            return jpeg.length;
        }
    }

    /**
     * Steps 1-5 of the hardening sequence. Nothing here touches the filesystem,
     * so a rejected upload can leave no debris by construction.
     */
    public EncodedAttachment validateAndEncode(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorKey.FEEDBACK_ATTACHMENT_NO_FILE, "No file provided");
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            throw new BusinessException(ErrorKey.FEEDBACK_ATTACHMENT_INVALID_TYPE,
                "Attachment must be JPEG, PNG, WebP, or GIF. Received: " + contentType);
        }

        if (file.getSize() > MAX_SIZE) {
            throw new BusinessException(ErrorKey.FEEDBACK_ATTACHMENT_TOO_LARGE,
                "Attachment must be under 5MB. Received: " + (file.getSize() / (1024 * 1024)) + "MB");
        }

        ensureWithinPixelBounds(file);

        try (InputStream in = file.getInputStream()) {
            BufferedImage original = ImageIO.read(in);
            if (original == null) {
                throw new BusinessException(ErrorKey.FEEDBACK_ATTACHMENT_CORRUPT,
                    "Could not read image data — file may be corrupt or an unsupported format");
            }

            BufferedImage processed = toOpaqueRgb(original);
            byte[] jpeg = encodeJpeg(processed);

            log.debug("Attachment encoded ({}x{} -> {}x{}, {} bytes)",
                original.getWidth(), original.getHeight(),
                processed.getWidth(), processed.getHeight(), jpeg.length);

            return new EncodedAttachment(jpeg, processed.getWidth(), processed.getHeight());
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            // Detail to the log, not to the caller — an IOException's message here
            // is usually a server path. Same reason as PhotoStorageService.store.
            log.warn("Could not decode or re-encode a feedback attachment", e);
            throw new BusinessException(ErrorKey.FEEDBACK_ATTACHMENT_CORRUPT,
                "Could not process image");
        }
    }

    /**
     * Steps 6-7: write to a temporary sibling, then atomically move into place,
     * so a concurrent GET never observes a half-written file and a failure
     * leaves nothing behind.
     */
    public void write(UUID feedbackId, UUID attachmentId, EncodedAttachment encoded) {
        Path dest = resolvePath(feedbackId, attachmentId);
        Path tmp = null;
        try {
            Files.createDirectories(dest.getParent());
            tmp = Files.createTempFile(dest.getParent(), attachmentId + "-", ".jpg.tmp");
            writeTemp(tmp, encoded.jpeg());
            Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            tmp = null;

            log.info("Attachment {} stored for feedback {} ({}x{}, {} bytes)",
                attachmentId, feedbackId, encoded.width(), encoded.height(), encoded.sizeBytes());
        } catch (IOException e) {
            log.error("Failed to store attachment {} for feedback {}", attachmentId, feedbackId, e);
            throw new BusinessException(ErrorKey.FEEDBACK_ATTACHMENT_STORE_FAILED,
                "Could not store the attachment");
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

    /**
     * R21 — removes every byte stored for one submission, directory included.
     *
     * The database cascades attachment ROWS away with their submission (V10),
     * but nothing in the database reaches the files, so this is the only thing
     * standing between a deleted account and images that live forever. Called
     * from the account-deletion path.
     *
     * Deliberately best-effort: it never throws. A filesystem that refuses a
     * delete must not be able to block someone from deleting their account, and
     * the caller has no better recovery than the loud log left here.
     *
     * @return true when nothing of the submission remains on disk
     */
    public boolean deleteAllForFeedback(UUID feedbackId) {
        Path dir = uploadDir.resolve(feedbackId.toString());
        if (!Files.exists(dir)) {
            return true;
        }

        try (var paths = Files.walk(dir)) {
            // Deepest first — a directory only goes once it is empty.
            List<Path> ordered = paths.sorted(Comparator.reverseOrder()).toList();
            boolean complete = true;
            for (Path path : ordered) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    complete = false;
                    log.error("Could not delete feedback attachment file {} — it will outlive its submission {}",
                        path, feedbackId, e);
                }
            }
            if (complete) {
                log.info("Attachment files removed for feedback {}", feedbackId);
            }
            return complete;
        } catch (IOException e) {
            log.error("Could not walk the attachment directory for feedback {} — files may be left behind",
                feedbackId, e);
            return false;
        }
    }

    /** Returns the stored attachment as a Spring Resource, or null if none exists. */
    public Resource serve(UUID feedbackId, UUID attachmentId) {
        Path path = resolvePath(feedbackId, attachmentId);
        return Files.exists(path) ? new FileSystemResource(path) : null;
    }

    /**
     * Seam for the single step that can leave debris on disk, so a mid-write
     * failure is testable. Overridden only in tests.
     */
    protected void writeTemp(Path tmp, byte[] jpeg) throws IOException {
        Files.write(tmp, jpeg);
    }

    // -- private helpers --

    private Path resolvePath(UUID feedbackId, UUID attachmentId) {
        // Per-submission directory so several attachments coexist, unlike the
        // profile photo's single-file-per-user scheme.
        return uploadDir.resolve(feedbackId.toString()).resolve(attachmentId + ".jpg");
    }

    /**
     * Rejects images whose header reports more than {@link #MAX_PIXELS} before
     * the full raster is allocated. Reads only the dimensions, not the pixels.
     */
    private void ensureWithinPixelBounds(MultipartFile file) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(file.getInputStream())) {
            if (iis == null) {
                return; // let the decode path fail later with FEEDBACK_ATTACHMENT_CORRUPT
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                return; // unknown format — decode path throws FEEDBACK_ATTACHMENT_CORRUPT
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis);
                long w = reader.getWidth(0);
                long h = reader.getHeight(0);
                if (w * h > MAX_PIXELS) {
                    throw new BusinessException(ErrorKey.FEEDBACK_ATTACHMENT_TOO_LARGE,
                        "Image dimensions too large: " + w + "x" + h);
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            // Header unreadable — defer to the decode path, which reports corrupt.
            log.debug("Could not read image header for pixel-bound check", e);
        }
    }

    /**
     * Downscales to fit within {@link #MAX_DIMENSION} (aspect preserved, never
     * upscaled) and always draws onto an opaque TYPE_INT_RGB canvas. The RGB
     * flatten is mandatory: the JDK JPEG writer silently fails on alpha-bearing
     * images. Transparent regions composite onto white.
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

    /**
     * Encodes to JPEG at an explicit quality. The encoder default (~0.75)
     * smears small interface text badly enough to defeat the point of a
     * screenshot, so quality is raised here rather than left implicit.
     */
    private byte[] encodeJpeg(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new BusinessException(ErrorKey.FEEDBACK_ATTACHMENT_CORRUPT,
                "No JPEG encoder available for this image");
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam params = writer.getDefaultWriteParam();
            if (params.canWriteCompressed()) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(JPEG_QUALITY);
            }
            writer.write(null, new IIOImage(image, null, null), params);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
