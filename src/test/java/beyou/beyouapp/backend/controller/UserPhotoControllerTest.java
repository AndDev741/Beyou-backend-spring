package beyou.beyouapp.backend.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.PhotoStorageService;
import beyou.beyouapp.backend.user.PhotoUrlSigner;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserService;

@AutoConfigureMockMvc(addFilters = false)
@ExtendWith(MockitoExtension.class)
class UserPhotoControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PhotoStorageService photoStorageService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private AuthenticatedUser authenticatedUser;

    /**
     * The real signer, not a mock: what these tests are about is whether a caller
     * without a signature gets the bytes, and a mock that answers true would test
     * nothing. Its secret comes from the test profile, same as production's.
     */
    @Autowired
    private PhotoUrlSigner photoUrlSigner;

    private User user;
    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
        user.setName("Test User");

        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities())
        );
        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
    }

    private byte[] createValidJpeg() throws Exception {
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", baos);
        return baos.toByteArray();
    }

    @Nested
    @DisplayName("POST /user/photo")
    class Upload {

        @Test
        @DisplayName("returns 200 on successful upload")
        void uploadsSuccessfully() throws Exception {
            byte[] jpeg = createValidJpeg();
            MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", jpeg);

            mockMvc.perform(multipart("/user/photo").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Photo uploaded"));

            verify(photoStorageService).store(eq(userId), any());
        }

        @Test
        @DisplayName("returns 400 when no file is attached")
        void noFileAttached() throws Exception {
            mockMvc.perform(multipart("/user/photo"))
                .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("DELETE /user/photo")
    class Remove {

        @Test
        @DisplayName("returns 204 and removes the authenticated caller's own photo")
        void removesOwnPhoto() throws Exception {
            mockMvc.perform(delete("/user/photo"))
                .andExpect(status().isNoContent());

            // The id comes from the token, never from the request, so there is no
            // other account this call could have reached.
            verify(userService).removePhoto(userId);
        }

        @Test
        @DisplayName("still returns 204 when the account has no photo to remove")
        void removingNothingIsStillSuccess() throws Exception {
            mockMvc.perform(delete("/user/photo"))
                .andExpect(status().isNoContent());

            verify(userService).removePhoto(userId);
        }
    }

    @Nested
    @DisplayName("GET /user/photo/{userId}")
    class Serve {

        @Test
        @DisplayName("returns 404 when a signed request finds no photo")
        void returns404WhenNoPhoto() throws Exception {
            when(photoStorageService.serve(userId)).thenReturn(null);

            mockMvc.perform(get("/user/photo/{userId}" + signedQuery(userId), userId))
                .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("serves the photo to a signed request")
        void servesSignedRequest() throws Exception {
            when(photoStorageService.serve(userId))
                .thenReturn(new ByteArrayResource(createValidJpeg()));

            mockMvc.perform(get("/user/photo/{userId}" + signedQuery(userId), userId))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, max-age=3600"));
        }

        /**
         * The finding this endpoint was fixed for: it used to answer anyone who could
         * name a user id, which made every uploaded face readable by walking UUIDs.
         */
        @Test
        @DisplayName("refuses an unsigned request")
        void refusesUnsignedRequest() throws Exception {
            mockMvc.perform(get("/user/photo/{userId}", userId))
                .andExpect(status().isForbidden());

            verify(photoStorageService, never()).serve(any());
        }

        @Test
        @DisplayName("refuses a signature minted for a different account")
        void refusesBorrowedSignature() throws Exception {
            UUID victim = UUID.randomUUID();

            mockMvc.perform(get("/user/photo/{userId}" + signedQuery(userId), victim))
                .andExpect(status().isForbidden());

            verify(photoStorageService, never()).serve(any());
        }

        @Test
        @DisplayName("refuses a forged signature")
        void refusesForgedSignature() throws Exception {
            mockMvc.perform(get("/user/photo/{userId}?v=1&exp=99999999999&sig=forged", userId))
                .andExpect(status().isForbidden());

            verify(photoStorageService, never()).serve(any());
        }

        private String signedQuery(UUID id) {
            return photoUrlSigner.signedQuery(id, 1234L);
        }
    }
}
