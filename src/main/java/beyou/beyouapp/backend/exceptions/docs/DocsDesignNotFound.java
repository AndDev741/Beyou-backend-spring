package beyou.beyouapp.backend.exceptions.docs;

import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;

public class DocsDesignNotFound extends BusinessException {
    public DocsDesignNotFound(String message) {
        super(ErrorKey.DOCS_DESIGN_NOT_FOUND, message);
    }
}
