package beyou.beyouapp.backend.domain.notebook.source;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;

/**
 * The text of a PDF, page by page, read in memory.
 *
 * <p>Page by page because a citation names a page ("CLRS p. 296"), so every chunk has to know
 * where it came from. The bytes are never written anywhere; when this returns, the only copy of
 * the document left is its text.
 *
 * <p>A PDF that yields no text at all is refused as unreadable. That is nearly always a scan
 * with no text layer, and a source that answers every question with nothing is worse than an
 * error the person can act on.
 */
@Component
public class PdfTextExtractor {

    /** Pages read from one file. Past this, the rest of the book is left out. */
    static final int MAX_PAGES = 800;

    /**
     * @param onPage called with each page number read, for the progress bar
     */
    public Extracted extract(byte[] bytes, IntConsumer onPage) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            int pages = Math.min(document.getNumberOfPages(), MAX_PAGES);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            List<TextChunker.PageText> texts = new ArrayList<>();
            int chars = 0;
            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document);
                chars += text.strip().length();
                texts.add(new TextChunker.PageText(page, text));
                onPage.accept(page);
            }
            if (chars == 0) {
                throw new BusinessException(ErrorKey.NOTEBOOK_SOURCE_UNREADABLE, "The PDF has no text layer");
            }
            return new Extracted(texts, document.getNumberOfPages());
        } catch (InvalidPasswordException locked) {
            throw new BusinessException(ErrorKey.NOTEBOOK_SOURCE_UNREADABLE, "The PDF is password protected");
        } catch (IOException broken) {
            throw new BusinessException(ErrorKey.NOTEBOOK_SOURCE_UNREADABLE, "The PDF could not be read");
        }
    }

    public record Extracted(List<TextChunker.PageText> pages, int pageCount) {
    }
}
