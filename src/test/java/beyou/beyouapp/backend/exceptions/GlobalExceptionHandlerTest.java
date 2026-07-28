package beyou.beyouapp.backend.exceptions;

import beyou.beyouapp.backend.exceptions.security.JwtNotFoundException;
import beyou.beyouapp.backend.exceptions.security.RefreshTokenDontMatchRaw;
import beyou.beyouapp.backend.exceptions.security.RefreshTokenExpiredException;
import beyou.beyouapp.backend.exceptions.security.RefreshTokenNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleJwtNotFound_returnsStructuredResponseWith401() {
        ResponseEntity<ApiErrorResponse> response = handler.handleJwtNotFoundException(
                new JwtNotFoundException("No JWT"));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ErrorKey.JWT_NOT_FOUND.name(), response.getBody().errorKey());
        assertEquals("No JWT", response.getBody().message());
        assertNull(response.getBody().details());
    }

    @Test
    void handleRefreshTokenExpired_returnsStructuredResponseWith401() {
        ResponseEntity<ApiErrorResponse> response = handler.handleRefreshTokenExpiredException(
                new RefreshTokenExpiredException("Expired"));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ErrorKey.REFRESH_TOKEN_EXPIRED.name(), response.getBody().errorKey());
        assertEquals("Expired", response.getBody().message());
        assertNull(response.getBody().details());
    }

    @Test
    void handleRefreshTokenNotFound_returnsStructuredResponseWith401() {
        ResponseEntity<ApiErrorResponse> response = handler.handleRefreshTokenNotFoundException(
                new RefreshTokenNotFoundException("Not found"));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ErrorKey.REFRESH_TOKEN_NOT_FOUND.name(), response.getBody().errorKey());
        assertEquals("Not found", response.getBody().message());
        assertNull(response.getBody().details());
    }

    @Test
    void handleRefreshTokenDontMatchRaw_returnsStructuredResponseWith401() {
        ResponseEntity<ApiErrorResponse> response = handler.handleRefreshTokenDontMatchRaw(
                new RefreshTokenDontMatchRaw("Mismatch"));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ErrorKey.REFRESH_TOKEN_INVALID.name(), response.getBody().errorKey());
        assertEquals("Mismatch", response.getBody().message());
        assertNull(response.getBody().details());
    }

    @Test
    void handleHttpClientErrorException_mapsUnwrappedUpstream4xxToGenericExternalServiceError() {
        ResponseEntity<ApiErrorResponse> response = handler.handleHttpClientErrorException(
                new HttpClientErrorException(HttpStatus.UNAUTHORIZED, "Bad credentials"));

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ErrorKey.EXTERNAL_SERVICE_ERROR.name(), response.getBody().errorKey());
        assertEquals("An upstream service request failed, try again later",
                response.getBody().message());
        assertNull(response.getBody().details());
    }

    @Test
    void handleBusinessException_docsImportFailed_returnsStructuredResponseWith400() {
        ResponseEntity<ApiErrorResponse> response = handler.handleBusinessException(
                new BusinessException(ErrorKey.DOCS_IMPORT_FAILED,
                        "Could not fetch architecture docs from the source repository"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ErrorKey.DOCS_IMPORT_FAILED.name(), response.getBody().errorKey());
        assertEquals("Could not fetch architecture docs from the source repository",
                response.getBody().message());
        assertNull(response.getBody().details());
    }

    /**
     * The container's multipart cap fires before any controller runs, so the
     * only thing left to tell a photo upload from a feedback attachment is the
     * request path. Both endpoints have their own documented error key and the
     * clients match on it for i18n, so a path-blind handler tells a user
     * uploading a screenshot that their "photo" was rejected.
     */
    @Test
    void handleMaxUploadSize_onFeedbackAttachment_reportsTheFeedbackKey() {
        ResponseEntity<ApiErrorResponse> response = handler.handleMaxUploadSizeExceededException(
                new MaxUploadSizeExceededException(6L * 1024 * 1024),
                requestTo("/feedback/" + UUID.randomUUID() + "/attachments"));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ErrorKey.FEEDBACK_ATTACHMENT_TOO_LARGE.name(), response.getBody().errorKey());
        assertEquals("Attachment must be under 5MB", response.getBody().message());
    }

    @Test
    void handleMaxUploadSize_onFeedbackAttachment_reportsTheFeedbackKeyBehindTheContextPath() {
        MockHttpServletRequest request = requestTo(
                "/api/v1/feedback/" + UUID.randomUUID() + "/attachments");
        request.setContextPath("/api/v1");

        ResponseEntity<ApiErrorResponse> response = handler.handleMaxUploadSizeExceededException(
                new MaxUploadSizeExceededException(6L * 1024 * 1024), request);

        assertNotNull(response.getBody());
        assertEquals(ErrorKey.FEEDBACK_ATTACHMENT_TOO_LARGE.name(), response.getBody().errorKey());
    }

    @Test
    void handleMaxUploadSize_onProfilePhoto_stillReportsThePhotoKey() {
        ResponseEntity<ApiErrorResponse> response = handler.handleMaxUploadSizeExceededException(
                new MaxUploadSizeExceededException(6L * 1024 * 1024), requestTo("/user/photo"));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ErrorKey.PHOTO_UPLOAD_TOO_LARGE.name(), response.getBody().errorKey());
        assertEquals("Photo must be under 5MB", response.getBody().message());
    }

    @Test
    void handleMaxUploadSize_onAnUnrelatedPath_fallsBackToThePhotoKey() {
        ResponseEntity<ApiErrorResponse> response = handler.handleMaxUploadSizeExceededException(
                new MaxUploadSizeExceededException(6L * 1024 * 1024), requestTo("/something/else"));

        assertNotNull(response.getBody());
        assertEquals(ErrorKey.PHOTO_UPLOAD_TOO_LARGE.name(), response.getBody().errorKey());
    }

    private static MockHttpServletRequest requestTo(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        return request;
    }

    @Test
    void handleBusinessException_googleOAuthFailed_returnsStructuredResponseWith400() {
        ResponseEntity<ApiErrorResponse> response = handler.handleBusinessException(
                new BusinessException(ErrorKey.GOOGLE_OAUTH_FAILED, "Error trying login with Google, try again"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ErrorKey.GOOGLE_OAUTH_FAILED.name(), response.getBody().errorKey());
        assertEquals("Error trying login with Google, try again", response.getBody().message());
        assertNull(response.getBody().details());
    }
}
