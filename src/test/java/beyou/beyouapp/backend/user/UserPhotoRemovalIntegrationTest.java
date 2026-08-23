package beyou.beyouapp.backend.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.sql.Date;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;

/**
 * Removing a profile photo, against the real file on disk and the real column.
 *
 * <p>A photo lives in two unrelated places and the app reads them in priority order:
 * an uploaded JPEG wins, {@code perfilPhoto} (a Google CDN URL) is the fallback. That
 * priority is why there was no way to remove a photo at all before this. The only
 * removal-shaped thing a client could reach was {@code PUT /user} with an empty
 * {@code photo}, which clears the column that the mapper does not even consult while
 * a file exists, so the photo came straight back on the next profile fetch.
 *
 * <p>So these tests assert through {@code getProfile()} rather than against the
 * pieces. Checking that the file is gone, or that the column is null, is checking one
 * half of a two-half bug — either assertion passes while the user still sees a face.
 * What has to be true is that the profile the client reads stops carrying a photo.
 */
class UserPhotoRemovalIntegrationTest extends AbstractIntegrationTest {

    private static final String EMAIL = "photo-removal@beyou.test";
    private static final String GOOGLE_AVATAR = "https://lh3.googleusercontent.com/a/seeded-avatar";

    @Autowired UserRepository userRepository;
    @Autowired UserService userService;

    /**
     * Spied, not mocked: the point of this class is that a real file leaves a real
     * disk. One test overrides {@code delete} to fail on purpose.
     */
    @MockitoSpyBean PhotoStorageService photoStorageService;

    private User user;
    private UUID userId;

    @BeforeEach
    void setUp() {
        userRepository.findByEmail(EMAIL).ifPresent(existing -> userService.deleteUser(existing));

        User fresh = new User();
        fresh.setName("someone changing their mind");
        fresh.setEmail(EMAIL);
        fresh.setPassword("placeholder");
        fresh.setCreatedAt(Date.valueOf(Instant.now().atZone(ZoneOffset.UTC).toLocalDate()));
        user = userRepository.saveAndFlush(fresh);
        userId = user.getId();
    }

    @AfterEach
    void tearDown() {
        photoStorageService.delete(userId);
        userRepository.findByEmail(EMAIL).ifPresent(existing -> userService.deleteUser(existing));
    }

    private void uploadRealPhoto() throws Exception {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        photoStorageService.store(userId,
                new MockMultipartFile("file", "face.jpg", "image/jpeg", out.toByteArray()));

        assertThat(photoStorageService.getPath(userId))
                .as("the fixture only means anything if there are real bytes to remove")
                .isNotNull();
    }

    @Test
    @DisplayName("removing an uploaded photo stops the profile serving it, and takes the file with it")
    void removingAnUploadedPhotoStopsTheProfileServingIt() throws Exception {
        uploadRealPhoto();

        assertThat(userService.getProfile(userId).photo())
                .as("before removal the profile serves the uploaded file")
                .contains("/user/photo/" + userId);

        userService.removePhoto(userId);

        assertThat(userService.getProfile(userId).photo())
                .as("the client still being handed a photo URL is the whole complaint")
                .isNull();
        assertThat(photoStorageService.getPath(userId))
                .as("the JPEG has to leave the disk too — a removed photo that is still "
                        + "stored is a face kept after the user asked us not to")
                .isNull();
    }

    @Test
    @DisplayName("removing clears the Google avatar too, so the old face does not come back instead")
    void removingAlsoClearsTheGoogleAvatar() throws Exception {
        // The account that makes the two-place storage visible: signed in with Google,
        // then uploaded a photo of their own over the top.
        user.setGoogleAccount(true);
        user.setPerfilPhoto(GOOGLE_AVATAR);
        userRepository.saveAndFlush(user);
        uploadRealPhoto();

        userService.removePhoto(userId);

        // Deleting only the file would pass the previous test and fail this one: the
        // mapper falls through to perfilPhoto the moment the file is gone, and the user
        // who asked for no photo gets their Google avatar back instead.
        assertThat(userService.getProfile(userId).photo())
                .as("falling back to the Google avatar is the same photo arriving by another route")
                .isNull();
        assertThat(userRepository.findById(userId).orElseThrow().getPerfilPhoto()).isNull();
    }

    @Test
    @DisplayName("removing a photo that is not there succeeds — the end state is already what was asked for")
    void removingNothingIsSuccess() {
        userService.removePhoto(userId);

        assertThat(userService.getProfile(userId).photo()).isNull();
    }

    @Test
    @DisplayName("a failed file delete refuses the request rather than reporting a photo that is still served")
    void aFailedDeleteDoesNotClaimSuccess() throws Exception {
        user.setPerfilPhoto(GOOGLE_AVATAR);
        userRepository.saveAndFlush(user);
        uploadRealPhoto();

        // An unlink that fails on the filesystem. delete() answers false for this and
        // for "there was nothing there", which is why removePhoto asks getPath first;
        // without that, this case would answer 204 while the photo stayed readable.
        doReturn(false).when(photoStorageService).delete(userId);

        assertThatThrownBy(() -> userService.removePhoto(userId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorKey())
                .isEqualTo(ErrorKey.PHOTO_DELETE_FAILED);

        assertThat(userRepository.findById(userId).orElseThrow().getPerfilPhoto())
                .as("the column must not be cleared while the file is still on disk — that "
                        + "combination is an account that reports no photo and serves one")
                .isEqualTo(GOOGLE_AVATAR);
    }
}
