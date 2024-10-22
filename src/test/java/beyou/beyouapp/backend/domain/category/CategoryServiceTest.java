package beyou.beyouapp.backend.domain.category;

import beyou.beyouapp.backend.domain.category.dto.CategoryEditRequestDTO;
import beyou.beyouapp.backend.domain.category.dto.CategoryRequestDTO;
import beyou.beyouapp.backend.exceptions.category.CategoryNotFound;
import beyou.beyouapp.backend.exceptions.user.UserNotFound;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class CategoryServiceTest {
    @Autowired
    CategoryService categoryService;

    @Test
    @Transactional
    public void shouldReturnACategory(){
        CategoryRequestDTO categoryRequestDTO = new CategoryRequestDTO("a4498905-9b3b-444e-af3f-092a60aff549", "Life", "test",
                "My life in category", 0, 0);
        ResponseEntity<Map<String, Object>> response = categoryService.createCategory(categoryRequestDTO);
        Category category = (Category) response.getBody().get("success");

        Category assertCategory = categoryService.getCategory(category.getId().toString());

        assertEquals(category, assertCategory);
        assertEquals(category.getName(), assertCategory.getName());
        assertEquals(category.getLevel(), assertCategory.getLevel());
        assertEquals(category.getUser(), assertCategory.getUser());
    }

    @Test
    public void shouldReturnAllTheCategoriesFromUser(){
        String userId = "a4498905-9b3b-444e-af3f-092a60aff549";

        ArrayList<Category> categories = categoryService.getAllCategories(userId);

        assertNotNull(categories);
        assertTrue(categories instanceof ArrayList);
    }

    @Test
    @Transactional
    public void shouldCreateACategorySuccessfully(){
        CategoryRequestDTO categoryRequestDTO = new CategoryRequestDTO("a4498905-9b3b-444e-af3f-092a60aff549", "Life", "test",
                "My life in category", 0, 0);

        ResponseEntity<Map<String, Object>> response = categoryService.createCategory(categoryRequestDTO);
        Category assertCategory = (Category) response.getBody().get("success");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(categoryRequestDTO.name(), assertCategory.getName());
        assertEquals(categoryRequestDTO.icon(), assertCategory.getIconId());
        assertEquals(categoryRequestDTO.description(), assertCategory.getDescription());
        assertEquals(50, assertCategory.getNextLevelXp());
    }

    @Test
    @Transactional
    public void shouldEditSuccessfullyACategory(){
        //Creating
        CategoryRequestDTO categoryRequestDTO = new CategoryRequestDTO("a4498905-9b3b-444e-af3f-092a60aff549", "Life", "test",
                "My life in category", 0, 0);
        ResponseEntity<Map<String, Object>> createCategoryResponse = categoryService.createCategory(categoryRequestDTO);
        Category categoryToEdit = (Category) createCategoryResponse.getBody().get("success");
        //Creating a class to edit
        CategoryEditRequestDTO categoryEditRequestDTO = new CategoryEditRequestDTO(categoryToEdit.getId().toString(),
                categoryToEdit.getName(), categoryToEdit.getIconId(), categoryToEdit.getDescription());

        ResponseEntity<Map<String, Object>> response = categoryService.editCategory(categoryEditRequestDTO);
        Category editedCategory = (Category) response.getBody().get("success");

        assertEquals(HttpStatus.OK ,response.getStatusCode());
        assertEquals(categoryEditRequestDTO.name() ,editedCategory.getName());
        assertEquals(categoryEditRequestDTO.description() ,editedCategory.getDescription());
        assertEquals(categoryEditRequestDTO.icon() ,editedCategory.getIconId());
    }

    @Test
    @Transactional
    public void shouldDeleteACategorySuccessfully(){
        CategoryRequestDTO categoryRequestDTO = new CategoryRequestDTO("a4498905-9b3b-444e-af3f-092a60aff549", "Life", "test",
                "My life in category", 0, 0);
        ResponseEntity<Map<String, Object>> createResponse = categoryService.createCategory(categoryRequestDTO);
        Category category = (Category) createResponse.getBody().get("success");

        ResponseEntity<Map<String, String>> response = categoryService.deleteCategory(category.getId().toString());

        assertEquals("Category deleted successfully", response.getBody().get("success"));

    }


    //Exceptions

    @Test
    public void shouldThrowAExceptionOfCategoryNotFoundWhenPassedAWrongCategoryId(){
        Exception exception = assertThrows(CategoryNotFound.class, () -> {
            categoryService.getCategory(UUID.randomUUID().toString());
        });

        assertEquals("Category not found", exception.getMessage());
    }

    @Test
    public void shouldThrowAExceptionOfUserNotFoundWhenTryingToCreateACategoryWithWrongUserId(){
        Exception exception = assertThrows(UserNotFound.class, () -> {
            CategoryRequestDTO categoryRequestDTO = new CategoryRequestDTO(UUID.randomUUID().toString(), "Life", "test",
                    "My life in category", 0, 0);
            categoryService.createCategory(categoryRequestDTO);
        });
        assertEquals("User not found", exception.getMessage());
    }


}
