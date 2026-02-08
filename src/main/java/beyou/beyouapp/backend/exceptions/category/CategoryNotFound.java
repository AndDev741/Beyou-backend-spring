package beyou.beyouapp.backend.exceptions.category;

import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;

public class CategoryNotFound extends BusinessException {
    public CategoryNotFound(String message) {
        super(ErrorKey.CATEGORY_NOT_FOUND, message);
    }
}
