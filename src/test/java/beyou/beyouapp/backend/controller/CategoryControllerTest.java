package beyou.beyouapp.backend.controller;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.CategoryRepository;
import beyou.beyouapp.backend.domain.category.CategoryService;
import beyou.beyouapp.backend.domain.category.dto.CategoryEditRequestDTO;
import beyou.beyouapp.backend.domain.category.dto.CategoryRequestDTO;
import beyou.beyouapp.backend.domain.category.dto.CategoryResponseDTO;
import beyou.beyouapp.backend.user.User;
import jakarta.transaction.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
@AutoConfigureMockMvc(addFilters = false)
@ExtendWith(MockitoExtension.class)
@ActiveProfiles("test")
public class CategoryControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CategoryService categoryService;

    @MockBean
    private CategoryRepository categoryRepository;

    User user = new User();
    UUID userID = UUID.randomUUID();
    Category category = new Category();
    @BeforeEach
    void setUp() {
        categoryRepository.deleteAll();
        user.setId(userID);

        CategoryRequestDTO categoryRequestDTO = new CategoryRequestDTO(
                "name", "icon", "desc",
                0, 0);
        category = new Category(categoryRequestDTO, user);
        categoryService.createCategory(categoryRequestDTO, userID);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    @Test
    void shouldGetCategoriesSuccessfully() throws Exception {
        List<CategoryResponseDTO> categories = List.of(new CategoryResponseDTO(category.getId(), category.getName(), "", "", null, 0, 0, 0, 0, null));

        when(categoryService.getAllCategories(userID)).thenReturn(new ArrayList<>(categories));

        mockMvc.perform(get("/category"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldCreateCategorySuccessfully() throws Exception {
        CategoryRequestDTO categoryRequestDTO = new CategoryRequestDTO(
                "name", "icon", "desc",
                0, 0);
        Category category = new Category(categoryRequestDTO, user);

        ResponseEntity<Map<String, Object>> successResponse = ResponseEntity.ok(Map.of("success", category));

        when(categoryService.createCategory(categoryRequestDTO, userID))
                .thenReturn(successResponse);

        mockMvc.perform(post("/category")
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(categoryRequestDTO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").exists());

    }

    @Test
    void shouldEditCategorySuccessfully() throws Exception {
        CategoryEditRequestDTO categoryEditRequestDTO = new CategoryEditRequestDTO("id",
                "name", "icon", "description");

        ResponseEntity<Map<String, Object>> successResponse = ResponseEntity.ok(Map.of("success", category));

        when(categoryService.editCategory(categoryEditRequestDTO, userID))
                .thenReturn(successResponse);

        mockMvc.perform(put("/category")
                .content(new ObjectMapper().writeValueAsString(categoryEditRequestDTO))
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").exists());
    }

    @Test
    void shouldDeleteCategorySuccessfully() throws Exception {
        String categoryId = "randomId";
        ResponseEntity<Map<String, String>> response = ResponseEntity.ok().body(Map.of("success", "Category deleted successfully"));

        when(categoryService.deleteCategory(categoryId, userID))
                .thenReturn(response);

        mockMvc.perform(delete("/category/{categoryId}", categoryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value("Category deleted successfully"));
    }

}

