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
    void handleHttpClientErrorException_returnsStructuredResponseWith400() {
        ResponseEntity<ApiErrorResponse> response = handler.handleHttpClientErrorException(
                new HttpClientErrorException(HttpStatus.BAD_REQUEST, "Bad Request"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ErrorKey.GOOGLE_OAUTH_FAILED.name(), response.getBody().errorKey());
        assertEquals("Error trying login with Google, try again", response.getBody().message());
        assertNull(response.getBody().details());
    }
}
