package beyou.beyouapp.backend.controller.category;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.CategoryService;
import beyou.beyouapp.backend.domain.category.dto.CategoryEditRequestDTO;
import beyou.beyouapp.backend.domain.category.dto.CategoryRequestDTO;
import beyou.beyouapp.backend.user.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ExtendWith(MockitoExtension.class)
public class CategoryControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CategoryService categoryService;

    @Test
    void shouldGetCategoriesSuccessfully() throws Exception {
        String userId = "some-user-id";

        List<Category> categories = List.of(new Category());

        when(categoryService.getAllCategories(userId)).thenReturn(new ArrayList<>(categories));

        mockMvc.perform(get("/category/{userId}", userId))
                .andExpect(status().isOk());
    }

    @Test
    void shouldCreateCategorySuccessfully() throws Exception {
        UUID userID = UUID.randomUUID();
        User user = new User();
        user.setId(userID);

        CategoryRequestDTO categoryRequestDTO = new CategoryRequestDTO(
                userID.toString(), "name", "icon", "desc",
                0, 0);
        Category category = new Category(categoryRequestDTO, user);

        ResponseEntity<Map<String, Object>> successResponse = ResponseEntity.ok(Map.of("success", category));

        when(categoryService.createCategory(any(CategoryRequestDTO.class)))
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
        Category category = new Category();

        ResponseEntity<Map<String, Object>> successResponse = ResponseEntity.ok(Map.of("success", category));

        when(categoryService.editCategory(any(CategoryEditRequestDTO.class)))
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

        when(categoryService.deleteCategory(anyString()))
                .thenReturn(response);

        mockMvc.perform(delete("/category/{categoryId}", categoryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value("Category deleted successfully"));
    }

}

