package beyou.beyouapp.backend.domain.category;

import beyou.beyouapp.backend.domain.category.dto.CategoryEditRequestDTO;
import beyou.beyouapp.backend.domain.category.dto.CategoryRequestDTO;
import beyou.beyouapp.backend.domain.category.dto.CategoryResponseDTO;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevel;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevelRepository;
import beyou.beyouapp.backend.exceptions.category.CategoryNotFound;
import beyou.beyouapp.backend.exceptions.user.UserNotFound;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
public class CategoryServiceTest {
    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private XpByLevelRepository xpByLevelRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CategoryService categoryService;

    User user = new User();
    UUID userId = UUID.randomUUID();
    Category category = new Category();
    UUID categoryId = UUID.randomUUID();
    CategoryRequestDTO categoryRequestDTO = new CategoryRequestDTO("Life", "test", "My life in category", 0, 0);
    XpByLevel xpByLevel = new XpByLevel();
    XpByLevel xpByLevel2 = new XpByLevel();
    @BeforeEach
    void setup() {
        categoryRepository.deleteAll();
        xpByLevelRepository.deleteAll();
        userRepository.deleteAll();

        user.setId(userId);

        xpByLevel = new XpByLevel(0, 0);

        xpByLevel2 = new XpByLevel(1, 20);

        category = new Category(categoryRequestDTO, user);
        category.setId(categoryId);
        category.setNextLevelXp(xpByLevel2.getXp());
        category.setActualLevelXp(xpByLevel.getXp());
    }

    @Test
    @Transactional
    public void shouldReturnACategory(){
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category));

        Category assertCategory = categoryService.getCategory(categoryId);

        assertEquals(category, assertCategory);
        assertEquals(category.getName(), assertCategory.getName());
        assertEquals(category.getLevel(), assertCategory.getLevel());
        assertEquals(category.getUser(), assertCategory.getUser());
    }

    @Test
    public void shouldReturnAllTheCategoriesFromUser(){
        when(categoryRepository.findAllByUserId(userId)).thenReturn(Optional.of(new ArrayList<>()));

        ArrayList<CategoryResponseDTO> categories = categoryService.getAllCategories(userId);

        assertNotNull(categories);
        assertTrue(categories instanceof ArrayList);
    }

    @SuppressWarnings("null")
    @Test
    public void shouldCreateACategorySuccessfully(){
        when(userRepository.findById(userId)).thenReturn(Optional.of(new User()));
        when(xpByLevelRepository.findByLevel(0 + 1)).thenReturn(xpByLevel2);
        when(xpByLevelRepository.findByLevel(0)).thenReturn(xpByLevel);

        ResponseEntity<Map<String, Object>> response = categoryService.createCategory(categoryRequestDTO, userId);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Category created successfully", response.getBody().get("success"));
    }

    @Test
    public void shouldEditSuccessfullyACategory(){

        CategoryEditRequestDTO categoryEditRequestDTO = new CategoryEditRequestDTO(categoryId.toString(),
                "name", "icon", "description");

        when(categoryRepository.findByUserId(userId)).thenReturn(Optional.of(new Category()));
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category));

        ResponseEntity<Map<String, Object>> response = categoryService.editCategory(categoryEditRequestDTO, userId);
        @SuppressWarnings("null")
        Category editedCategory = (Category) response.getBody().get("success");

        assertEquals(HttpStatus.OK ,response.getStatusCode());
        assertEquals(categoryEditRequestDTO.name() ,editedCategory.getName());
        assertEquals(categoryEditRequestDTO.description() ,editedCategory.getDescription());
        assertEquals(categoryEditRequestDTO.icon() ,editedCategory.getIconId());
    }

    @SuppressWarnings("null")
    @Test
    public void shouldDeleteACategorySuccessfully(){
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category));

        ResponseEntity<Map<String, String>> response = categoryService.deleteCategory(categoryId.toString(), userId);

        assertEquals("Category deleted successfully", response.getBody().get("success"));
    }

    //Exceptions

    @Test
    public void shouldThrowAExceptionOfCategoryNotFoundWhenPassedAWrongCategoryId(){
        Exception exception = assertThrows(CategoryNotFound.class, () -> {
            categoryService.getCategory(UUID.randomUUID());
        });

        assertEquals("Category not found", exception.getMessage());
    }

    @Test
    public void shouldThrowAExceptionOfUserNotFoundWhenTryingToCreateACategoryWithWrongUserId(){
        Exception exception = assertThrows(UserNotFound.class, () -> {
            CategoryRequestDTO categoryRequestDTO = new CategoryRequestDTO("Life", "test",
                    "My life in category", 0, 0);
            categoryService.createCategory(categoryRequestDTO, UUID.randomUUID());
        });
        assertEquals("User not found", exception.getMessage());
    }

}
