package beyou.beyouapp.backend.security;

import jakarta.servlet.http.HttpServletRequest;

public final class ClientType {
    private ClientType() {}

    public static boolean isMobile(HttpServletRequest request) {
        return request != null && "mobile".equalsIgnoreCase(request.getHeader("X-Client"));
    }
}
