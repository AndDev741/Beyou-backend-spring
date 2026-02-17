package beyou.beyouapp.backend.exceptions.docs;

import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;

public class DocsImportFailed extends BusinessException {
    public DocsImportFailed(String message) {
        super(ErrorKey.DOCS_IMPORT_FAILED, message);
    }
}
