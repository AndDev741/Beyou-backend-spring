package beyou.beyouapp.backend.controllers;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.transaction.UnexpectedRollbackException;

import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.security.RefreshToken.RefreshTokenService;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserService;
import beyou.beyouapp.backend.user.deletion.AccountDeletionService;
import beyou.beyouapp.backend.user.deletion.dto.ConfirmAccountDeletionDTO;
import beyou.beyouapp.backend.user.dto.UserEditDTO;
import beyou.beyouapp.backend.user.dto.UserResponseDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/user")
@lombok.extern.slf4j.Slf4j
public class UserController {
    private UserService userService;
    private AuthenticatedUser authenticatedUser;
    private AccountDeletionService accountDeletionService;
    private RefreshTokenService refreshTokenService;

    public UserController(UserService userService, AuthenticatedUser authenticatedUser,
            AccountDeletionService accountDeletionService, RefreshTokenService refreshTokenService){
        this.userService = userService;
        this.authenticatedUser = authenticatedUser;
        this.accountDeletionService = accountDeletionService;
        this.refreshTokenService = refreshTokenService;
    }

    @GetMapping()
    public UserResponseDTO getProfile(){
        User user = authenticatedUser.getAuthenticatedUser();
        return userService.getProfile(user.getId());
    }

    @PutMapping()
    public UserResponseDTO editUser(@Valid @RequestBody UserEditDTO userEdit){
        User user = authenticatedUser.getAuthenticatedUser();
        return userService.editUser(userEdit, user.getId());
    }

    /**
     * Step one of deleting an account: mail a six-digit code to the address on the
     * account. Nothing is destroyed here, and the response says nothing about
     * whether mail went out — that is the inbox's news to deliver.
     */
    @PostMapping("/deletion/code")
    public ResponseEntity<Map<String, Object>> requestDeletionCode(){
        User user = authenticatedUser.getAuthenticatedUser();
        String exposedCode = accountDeletionService.requestCode(user);
        // `code` is present only under the e2e profile, where there is no inbox to
        // read. See AccountDeletionService#exposeCode.
        return ResponseEntity.ok(exposedCode == null
                ? Map.of("success", true)
                : Map.of("success", true, "code", exposedCode));
    }

    /**
     * Step two: spend the code and delete the account.
     *
     * A POST rather than {@code DELETE /user} because the code travels in the body,
     * and a body on DELETE is what the shared frontend HttpClient deliberately does
     * not carry (its config is headers/params/timeout, so every adapter has to
     * support exactly the same three). A query parameter was the alternative, and
     * that writes the code into every access log between here and the browser.
     *
     * The cookie is cleared after the delete rather than before, so a refused code
     * leaves the session exactly as it was. The refresh token row is already gone
     * with the account by then; this call is only here to take the cookie off the
     * browser.
     */
    @PostMapping("/deletion/confirm")
    public ResponseEntity<Map<String, String>> deleteAccount(
            @Valid @RequestBody ConfirmAccountDeletionDTO confirmation,
            HttpServletRequest request,
            HttpServletResponse response){
        User user = authenticatedUser.getAuthenticatedUser();
        ResponseEntity<Map<String, String>> result;
        try {
            result = accountDeletionService.confirm(user, confirmation.code());
        } catch (UnexpectedRollbackException rolledBack) {
            // The net under the net. deleteUser rethrows its own keyed exception, and an
            // exception leaving a @Transactional method propagates as itself — so the
            // ordinary failure arrives as ACCOUNT_DELETE_FAILED and never lands here.
            // This catch is for the other way a transaction dies: something marks it
            // rollback-only while deleteUser still returns normally, and the proxy raises
            // this on the way out with nothing keyed inside it. GlobalExceptionHandler has
            // no handler for it, so without this the client would get a bare 500.
            log.error("Account deletion for {} rolled back", user.getId(), rolledBack);
            throw new BusinessException(ErrorKey.ACCOUNT_DELETE_FAILED, "Could not delete the account");
        }
        // Only after a delete that actually happened: a refused code must leave the
        // session exactly as it was.
        refreshTokenService.revokeRefreshToken(request, response);
        return result;
    }
}
