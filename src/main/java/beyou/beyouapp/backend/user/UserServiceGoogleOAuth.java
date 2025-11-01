package beyou.beyouapp.backend.user;

import beyou.beyouapp.backend.security.TokenService;
import beyou.beyouapp.backend.user.dto.GoogleUserDTO;
import beyou.beyouapp.backend.user.dto.UserResponseDTO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.Optional;

@Service
public class UserServiceGoogleOAuth {
    @Autowired
    TokenService tokenService;
    @Autowired
    UserRepository userRepository;

    public ResponseEntity<Map<String, Object>> googleAuth(String code, HttpServletResponse response){
        String googleAccessToken = getOAuthAccessTokenGoogle(code);
        Map<String, String> profileDetails = getProfileDetailsFromGoogle(googleAccessToken);
        String name = profileDetails.get("name");
        String email = profileDetails.get("email");
        String perfilPhoto = profileDetails.get("picture");

        GoogleUserDTO googleUser = new GoogleUserDTO(email, name, perfilPhoto);
        Optional<User> optionalUser = userRepository.findByEmail(googleUser.email());

        if(optionalUser.isPresent()){
            User user =  optionalUser.get();
            String jwtToken = tokenService.generateToken(user);
            addJwtTokenToResponse(response, jwtToken);
            UserResponseDTO userResponseDTO = new UserResponseDTO(user.getName(),
                    user.getEmail(), user.getPerfilPhrase(), user.getPerfilPhraseAuthor(),
                    user.getConstance(), user.getPerfilPhoto(), user.isGoogleAccount(), user.getWidgetsIdInUse(), user.getThemeInUse());
            return ResponseEntity.ok().body(Map.of("success", userResponseDTO));
        }else{
            User newUser = new User(googleUser);
            userRepository.save(newUser);
            return ResponseEntity.ok().body(Map.of("successRegister", "User registered successfully"));
        }
    }

    private String getOAuthAccessTokenGoogle(String code){
        String clientId = "1036463666928-d3i3phfhglrl00l489tm3fccarm6p6nq.apps.googleusercontent.com";
        String clientSecret = "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";
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("code", code);
        params.add("redirect_uri", "http://localhost:3000/");
        params.add("client_id", clientId);
        params.add("client_secret", clientSecret);
        params.add("grant_type", "authorization_code");

        HttpEntity<MultiValueMap<String, String>> requestEntity = new HttpEntity<>(params, httpHeaders);
        String url = "https://oauth2.googleapis.com/token";
        try{
            String response = restTemplate.postForObject(url, requestEntity, String.class);
            ObjectMapper mapper = new ObjectMapper();
            Map<String, String> tokenResponse = mapper.readValue(response, new TypeReference<Map<String, String>>() {});

            return tokenResponse.get("access_token");

        } catch (HttpClientErrorException e) {
            System.err.println("Error tryng to get token OAuth: " + e.getStatusCode() + " " + e.getResponseBodyAsString());
            throw e;
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private Map<String, String> getProfileDetailsFromGoogle(String accessToken) {
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setBearerAuth(accessToken);

        HttpEntity<String> requestEntity = new HttpEntity<>(httpHeaders);

        String url = "https://www.googleapis.com/oauth2/v2/userinfo";
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, requestEntity, String.class);
        
        try{
            ObjectMapper objectMapper = new ObjectMapper();

            return objectMapper.readValue(response.getBody(), new TypeReference<Map<String, String>>() {});
        }catch (Exception e) {
            throw new RuntimeException("Failed to parse Google profile response", e);
        }
    }

    private void addJwtTokenToResponse(HttpServletResponse response, String token){
        Cookie cookie = new Cookie("jwt", token);
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setPath("/");
        cookie.setMaxAge(7 * 24 * 60 * 60);

        response.addCookie(cookie);

        response.setHeader(HttpHeaders.SET_COOKIE, "jwt=" + token + "; Max-Age=604800; Path=/; Secure; HttpOnly; SameSite=None");
    }
}

