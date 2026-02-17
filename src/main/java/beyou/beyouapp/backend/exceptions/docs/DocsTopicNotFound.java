package beyou.beyouapp.backend.exceptions.docs;

import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;

public class DocsTopicNotFound extends BusinessException {
    public DocsTopicNotFound(String message) {
        super(ErrorKey.DOCS_TOPIC_NOT_FOUND, message);
    }
}
