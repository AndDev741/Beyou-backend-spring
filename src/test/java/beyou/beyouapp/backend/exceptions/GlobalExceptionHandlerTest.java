package beyou.beyouapp.backend.exceptions;

import beyou.beyouapp.backend.exceptions.security.JwtNotFoundException;
import beyou.beyouapp.backend.exceptions.security.RefreshTokenDontMatchRaw;
import beyou.beyouapp.backend.exceptions.security.RefreshTokenExpiredException;
import beyou.beyouapp.backend.exceptions.security.RefreshTokenNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;

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
