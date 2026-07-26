package beyou.beyouapp.backend.domain.feedback;

import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * U3 — attachment bytes on disk.
 *
 * The hardening sequence is carried over wholesale from
 * {@code user/PhotoStorageService}: MIME allowlist, byte ceiling, header-derived
 * pixel-bound check BEFORE decode (the decompression-bomb guard), decode,
 * re-encode to opaque RGB, temporary sibling, atomic move. What differs is the
 * geometry: a screenshot has to stay legible (R3/R9), so the dimension ceiling
 * is 1920 px on the longest edge rather than the profile photo's 512 px, and
 * files are keyed by submission + attachment so several can coexist.
 */
class FeedbackAttachmentStorageServiceTest {

    @TempDir
    Path uploadRoot;

    private static final UUID FEEDBACK_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ATTACHMENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private FeedbackAttachmentStorageService service() {
        return new FeedbackAttachmentStorageService(uploadRoot.toString());
    }

    @Test
    @DisplayName("a valid image is stored and served back")
    void validImageIsStoredAndServedBack() throws Exception {
        FeedbackAttachmentStorageService service = service();

        FeedbackAttachmentStorageService.EncodedAttachment encoded =
                service.validateAndEncode(png(800, 600));
        service.write(FEEDBACK_ID, ATTACHMENT_ID, encoded);

        Resource served = service.serve(FEEDBACK_ID, ATTACHMENT_ID);

        assertThat(served).isNotNull();
        assertThat(served.exists()).isTrue();

        BufferedImage roundTripped = ImageIO.read(served.getInputStream());
        assertThat(roundTripped).isNotNull();
        assertThat(roundTripped.getWidth()).isEqualTo(800);
        assertThat(roundTripped.getHeight()).isEqualTo(600);
    }

    @Test
    @DisplayName("several attachments coexist under one submission")
    void severalAttachmentsCoexistUnderOneSubmission() throws Exception {
        FeedbackAttachmentStorageService service = service();

        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        service.write(FEEDBACK_ID, first, service.validateAndEncode(png(120, 90)));
        service.write(FEEDBACK_ID, second, service.validateAndEncode(png(90, 120)));

        assertThat(service.serve(FEEDBACK_ID, first)).isNotNull();
        assertThat(service.serve(FEEDBACK_ID, second)).isNotNull();
    }

    @Test
    @DisplayName("a screenshot keeps enough resolution for interface text to stay readable")
    void screenshotKeepsEnoughResolutionForTextToStayReadable() throws Exception {
        FeedbackAttachmentStorageService service = service();

        // A 2x-DPR phone screenshot. Downscaled to 512px (the profile-photo
        // ceiling) its interface text would be unreadable; the attachment
        // ceiling has to keep the long edge at 1920.
        FeedbackAttachmentStorageService.EncodedAttachment encoded =
                service.validateAndEncode(png(1170, 2532));

        assertThat(encoded.height()).isEqualTo(1920);
        assertThat(encoded.width()).isEqualTo(887);
    }

    @Test
    @DisplayName("an image already inside the ceiling is never upscaled")
    void imageInsideTheCeilingIsNotUpscaled() throws Exception {
        FeedbackAttachmentStorageService.EncodedAttachment encoded =
                service().validateAndEncode(png(640, 480));

        assertThat(encoded.width()).isEqualTo(640);
        assertThat(encoded.height()).isEqualTo(480);
    }

    @Test
    @DisplayName("a disallowed MIME type is rejected")
    void disallowedMimeTypeIsRejected() throws Exception {
        MultipartFile pdf = new MockMultipartFile(
                "file", "report.pdf", "application/pdf", "%PDF-1.7 not an image".getBytes());

        assertThatThrownBy(() -> service().validateAndEncode(pdf))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorKey())
                .isEqualTo(ErrorKey.FEEDBACK_ATTACHMENT_INVALID_TYPE);
    }

    @Test
    @DisplayName("an empty upload is rejected")
    void emptyUploadIsRejected() {
        MultipartFile empty = new MockMultipartFile("file", "shot.png", "image/png", new byte[0]);

        assertThatThrownBy(() -> service().validateAndEncode(empty))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorKey())
                .isEqualTo(ErrorKey.FEEDBACK_ATTACHMENT_NO_FILE);
    }

    @Test
    @DisplayName("a file beyond the byte ceiling is rejected")
    void fileBeyondTheByteCeilingIsRejected() {
        byte[] oversized = new byte[(int) FeedbackAttachmentStorageService.MAX_SIZE + 1];
        MultipartFile file = new MockMultipartFile("file", "huge.png", "image/png", oversized);

        assertThatThrownBy(() -> service().validateAndEncode(file))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorKey())
                .isEqualTo(ErrorKey.FEEDBACK_ATTACHMENT_TOO_LARGE);
    }

    @Test
    @DisplayName("an image whose header declares dimensions past the pixel ceiling is rejected before decoding")
    void headerDeclaringTooManyPixelsIsRejectedBeforeDecode() throws Exception {
        // A tiny PNG whose IHDR has been rewritten to claim 30000x30000 (900MP,
        // far past the 25MP ceiling) while its IDAT still holds 8x8 of pixels.
        // Decoding it would fail with a *corrupt* error — so getting TOO_LARGE
        // back is the proof the header check ran first and no raster was ever
        // allocated. That ordering is the decompression-bomb guard.
        MultipartFile bomb = new MockMultipartFile(
                "file", "bomb.png", "image/png", pngWithForgedDimensions(30_000, 30_000));

        assertThatThrownBy(() -> service().validateAndEncode(bomb))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorKey())
                .isEqualTo(ErrorKey.FEEDBACK_ATTACHMENT_TOO_LARGE);
    }

    @Test
    @DisplayName("bytes that are not a decodable image are rejected as corrupt")
    void undecodableBytesAreRejectedAsCorrupt() {
        MultipartFile notAnImage = new MockMultipartFile(
                "file", "shot.png", "image/png", "these bytes are not a PNG".getBytes());

        assertThatThrownBy(() -> service().validateAndEncode(notAnImage))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorKey())
                .isEqualTo(ErrorKey.FEEDBACK_ATTACHMENT_CORRUPT);
    }

    @Test
    @DisplayName("a failed encode leaves no partial file behind")
    void failedEncodeLeavesNoPartialFileBehind() throws Exception {
        // Writing the encoded JPEG is the only step that can leave debris on
        // disk. This subclass half-writes the temporary sibling and then fails,
        // exactly as a full disk would.
        FeedbackAttachmentStorageService failing = new FeedbackAttachmentStorageService(uploadRoot.toString()) {
            @Override
            protected void writeTemp(Path tmp, byte[] jpeg) throws IOException {
                Files.write(tmp, Arrays.copyOf(jpeg, jpeg.length / 2));
                throw new IOException("simulated disk failure mid-write");
            }
        };

        FeedbackAttachmentStorageService.EncodedAttachment encoded =
                failing.validateAndEncode(png(400, 300));

        assertThatThrownBy(() -> failing.write(FEEDBACK_ID, ATTACHMENT_ID, encoded))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorKey())
                .isEqualTo(ErrorKey.FEEDBACK_ATTACHMENT_STORE_FAILED);

        assertThat(failing.serve(FEEDBACK_ID, ATTACHMENT_ID)).isNull();
        assertThat(filesUnderSubmissionDirectory())
                .as("no final file and no leftover temporary sibling")
                .isEmpty();
    }

    @Test
    @DisplayName("serving an attachment that was never stored yields nothing")
    void servingAnUnknownAttachmentYieldsNothing() {
        assertThat(service().serve(FEEDBACK_ID, ATTACHMENT_ID)).isNull();
    }

    // -- helpers --

    private List<Path> filesUnderSubmissionDirectory() throws IOException {
        Path dir = uploadRoot.resolve("feedback-attachments").resolve(FEEDBACK_ID.toString());
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.toList();
        }
    }

    private static MultipartFile png(int width, int height) throws IOException {
        return new MockMultipartFile("file", "shot.png", "image/png", pngBytes(width, height));
    }

    private static byte[] pngBytes(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /**
     * Rewrites a real PNG's IHDR to claim dimensions it does not have, fixing up
     * the chunk CRC so the header still parses. Layout: 8-byte signature, then
     * length(4) + "IHDR"(4) + data(13) + CRC(4).
     */
    private static byte[] pngWithForgedDimensions(int declaredWidth, int declaredHeight) throws IOException {
        byte[] png = pngBytes(8, 8);
        ByteBuffer buffer = ByteBuffer.wrap(png);
        buffer.putInt(16, declaredWidth);
        buffer.putInt(20, declaredHeight);

        CRC32 crc = new CRC32();
        crc.update(png, 12, 4 + 13); // chunk type + chunk data
        buffer.putInt(29, (int) crc.getValue());

        // Sanity: the forged header must still be readable as a header.
        assertThat(ImageIO.createImageInputStream(new ByteArrayInputStream(png))).isNotNull();
        return buffer.array();
    }
}
