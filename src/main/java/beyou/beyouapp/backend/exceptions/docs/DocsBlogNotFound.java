package beyou.beyouapp.backend.exceptions.docs;

import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;

public class DocsBlogNotFound extends BusinessException {
    public DocsBlogNotFound(String message) {
        super(ErrorKey.DOCS_BLOG_NOT_FOUND, message);
    }
}
