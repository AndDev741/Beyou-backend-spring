package beyou.beyouapp.backend.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserMapper;
import beyou.beyouapp.backend.user.UserService;
import beyou.beyouapp.backend.user.dto.UserEditDTO;
import beyou.beyouapp.backend.user.dto.UserResponseDTO;
import beyou.beyouapp.backend.user.enums.ConstanceConfiguration;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ExtendWith(MockitoExtension.class)
@ActiveProfiles("test")
public class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;

    @MockBean
    private AuthenticatedUser authenticatedUser;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final UserMapper userMapper = new UserMapper();

    private User user;
    private UserResponseDTO userResponseDTO;
    private UUID userId;

    @BeforeEach
    void setup() {
        userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
        user.setName("Andree");

        userResponseDTO = userMapper.toResponseDTO(user);

        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities())
        );
        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
    }

    @Test
    void shouldEditUserSuccessfully() throws Exception {
        UserEditDTO dto = new UserEditDTO(
            "Andree", 
            "photo", 
            "phrase", 
            "author", 
            List.of("widget1"), 
            "dark",
            ConstanceConfiguration.ANY,
            "en"
        );
        when(userService.editUser(dto, userId)).thenReturn(userResponseDTO);

        mockMvc.perform(put("/user")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Andree"));

        verify(userService).editUser(dto, userId);
    }

    @Test
    void shouldEditWidgetsSuccessfully() throws Exception {
        UserEditDTO dto = new UserEditDTO(
            null, 
            null, 
            null, 
            null, 
            List.of("widgetA", "widgetB"), 
            null,
            ConstanceConfiguration.ANY,
            "en"
        );
       
        when(userService.editUser(dto, userId)).thenReturn(userResponseDTO);

        mockMvc.perform(put("/user")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
            .andExpect(status().isOk());
            // .andExpect(jsonPath("$.widgetsId").value("Widgets edited successfully"));

        verify(userService).editUser(dto, userId);
    }

    //Exceptions

        // @Test
    // void shouldReturnBadRequestWhenEditUserFails() throws Exception {
    //     UserEditDTO dto = new UserEditDTO(
    //         "name", 
    //         null, 
    //         null, 
    //         null, 
    //         List.of(), 
    //         "light",
    //         ConstanceConfiguration.ANY
    //     );
    //     when(userService.editUser(dto, userId)).thenThrow(new RuntimeException("fail"));

    //     mockMvc.perform(put("/user/edit")
    //             .contentType(MediaType.APPLICATION_JSON)
    //             .content(objectMapper.writeValueAsString(dto)))
    //         .andExpect(status().isBadRequest())
    //         .andExpect(jsonPath("$.error").value("Error trying to edit user"));

    //     verify(userService).editUser(dto, userId);
    // }

    // @Test
    // void shouldReturnBadRequestWhenEditWidgetsFails() throws Exception {
    //     List<String> widgets = List.of("widgetX");
    //     UserEditDTO dto = new UserEditDTO(
    //         null, 
    //         null, 
    //         null, 
    //         null, 
    //         widgets, 
    //         null,
    //         null
    //     );
    //     when(userService.editUser(eq(dto), eq(userId))).thenThrow(new RuntimeException("fail"));

    //     mockMvc.perform(put("/user")
    //             .contentType(MediaType.APPLICATION_JSON)
    //             .content(objectMapper.writeValueAsString(dto)))
    //         .andExpect(status().isBadRequest())
    //         .andExpect(jsonPath("$.error").value("Error trying to edit widgets"));

    //     verify(userService).editUser(eq(dto), eq(userId));
    // }
}
