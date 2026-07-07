package beyou.beyouapp.backend.unit.user;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import beyou.beyouapp.backend.user.PhotoStorageService;

class PhotoStorageServiceTest {

    static Path testUploadDir;

    private PhotoStorageService service;
    private UUID userId;

    @BeforeEach
    void setUp() throws IOException {
        testUploadDir = Files.createTempDirectory("beyou-test-photos");
        service = new PhotoStorageService(testUploadDir.toString());
        userId = UUID.randomUUID();
    }

    @AfterAll
    static void tearDownAll() throws IOException {
        if (Files.exists(testUploadDir)) {
            Files.walk(testUploadDir)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> p.toFile().delete());
        }
    }

    private byte[] createValidJpeg() throws IOException {
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", baos);
        return baos.toByteArray();
    }

    private byte[] createLargeImage() throws IOException {
        BufferedImage img = new BufferedImage(2048, 2048, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", baos);
        return baos.toByteArray();
    }

    // ponytail: MockMultipartFile from Spring test; no custom stub needed
    private org.springframework.mock.web.MockMultipartFile mockFile(String name, byte[] content, String contentType) {
        return new org.springframework.mock.web.MockMultipartFile(name, name, contentType, content);
    }

    @Nested
    @DisplayName("store")
    class Store {

        @Test
        @DisplayName("saves JPEG and creates file on disk")
        void savesJpeg() throws IOException {
            byte[] jpeg = createValidJpeg();
            var file = mockFile("photo.jpg", jpeg, "image/jpeg");

            service.store(userId, file);

            assertTrue(service.exists(userId));
            Path saved = service.getPath(userId);
            assertNotNull(saved);
            assertTrue(Files.exists(saved));
        }

        @Test
        @DisplayName("overwrites existing photo on re-upload")
        void overwritesExisting() throws IOException {
            byte[] jpeg1 = createValidJpeg();
            service.store(userId, mockFile("a.jpg", jpeg1, "image/jpeg"));

            byte[] jpeg2 = createValidJpeg();
            service.store(userId, mockFile("b.jpg", jpeg2, "image/jpeg"));

            assertTrue(service.exists(userId));
        }

        @Test
        @DisplayName("rejects null file")
        void rejectsNullFile() {
            var ex = assertThrows(RuntimeException.class, () -> service.store(userId, null));
            assertTrue(ex.getMessage().contains("No file"));
        }

        @Test
        @DisplayName("rejects empty file")
        void rejectsEmptyFile() {
            var file = mockFile("empty.jpg", new byte[0], "image/jpeg");
            var ex = assertThrows(RuntimeException.class, () -> service.store(userId, file));
            assertTrue(ex.getMessage().contains("No file") || ex.getMessage().contains("empty"));
        }

        @Test
        @DisplayName("rejects invalid MIME type")
        void rejectsInvalidMime() {
            var file = mockFile("doc.pdf", new byte[]{1,2,3}, "application/pdf");
            assertThrows(RuntimeException.class, () -> service.store(userId, file));
        }

        @Test
        @DisplayName("resizes large images to max 512px")
        void resizesLargeImages() throws IOException {
            byte[] largeJpeg = createLargeImage();
            var file = mockFile("large.jpg", largeJpeg, "image/jpeg");

            service.store(userId, file);

            Path saved = service.getPath(userId);
            BufferedImage result = ImageIO.read(saved.toFile());
            assertTrue(result.getWidth() <= 512);
            assertTrue(result.getHeight() <= 512);
        }

        @Test
        @DisplayName("does not upscale small images")
        void doesNotUpscale() throws IOException {
            byte[] smallJpeg = createValidJpeg(); // 100x100
            var file = mockFile("small.jpg", smallJpeg, "image/jpeg");

            service.store(userId, file);

            Path saved = service.getPath(userId);
            BufferedImage result = ImageIO.read(saved.toFile());
            assertEquals(100, result.getWidth());
            assertEquals(100, result.getHeight());
        }
    }

    @Nested
    @DisplayName("exists")
    class Exists {

        @Test
        @DisplayName("returns false when user has no photo")
        void returnsFalseWhenNoPhoto() {
            assertFalse(service.exists(UUID.randomUUID()));
        }

        @Test
        @DisplayName("returns true after store")
        void returnsTrueAfterStore() throws IOException {
            service.store(userId, mockFile("p.jpg", createValidJpeg(), "image/jpeg"));
            assertTrue(service.exists(userId));
        }
    }

    @Nested
    @DisplayName("serve")
    class Serve {

        @Test
        @DisplayName("returns Resource pointing to file")
        void returnsResource() throws IOException {
            service.store(userId, mockFile("p.jpg", createValidJpeg(), "image/jpeg"));
            var resource = service.serve(userId);
            assertNotNull(resource);
            assertTrue(resource.exists());
        }

        @Test
        @DisplayName("returns null when no file exists")
        void returnsNullWhenNoFile() {
            assertNull(service.serve(UUID.randomUUID()));
        }
    }
}
