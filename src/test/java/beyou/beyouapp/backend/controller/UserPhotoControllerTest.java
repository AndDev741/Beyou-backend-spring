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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.PhotoStorageService;
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
    @DisplayName("GET /user/photo/{userId}")
    class Serve {

        @Test
        @DisplayName("returns 404 when user has no photo")
        void returns404WhenNoPhoto() throws Exception {
            when(photoStorageService.serve(userId)).thenReturn(null);

            mockMvc.perform(get("/user/photo/{userId}", userId))
                .andExpect(status().isNotFound());
        }
    }
}
