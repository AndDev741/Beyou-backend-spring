package beyou.beyouapp.backend.domain.notebook.diagram;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import beyou.beyouapp.backend.domain.notebook.NotebookOwnership;
import beyou.beyouapp.backend.domain.notebook.NotebookPage;
import beyou.beyouapp.backend.domain.notebook.ai.NotebookLlm;
import beyou.beyouapp.backend.domain.notebook.ai.NotebookTransactions;
import beyou.beyouapp.backend.domain.notebook.ai.StudyContextBuilder;
import beyou.beyouapp.backend.domain.notebook.diagram.dto.DiagramRequestDTO;
import beyou.beyouapp.backend.domain.notebook.diagram.dto.DiagramResultDTO;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;

/**
 * "Diagram" on a page: the model fills a {@link DiagramSpec}, {@link DiagramSanitizer} cleans
 * it, and the client draws it. Nothing is stored here; the client inserts the result into the
 * page, or appends it through {@code POST /notebook/pages/{id}/append}.
 *
 * <p>Grounded (the default) hands the model the page's passages through
 * {@link StudyContextBuilder}, ranked by the request, and the DIAGRAM mode of
 * {@code prompts/notebookTutor.st} builds the picture from them without citing any. A page with
 * nothing to read is not refused: the model then draws from its own knowledge, and
 * {@code fromNotes} says so, so the client can label where the diagram came from.
 *
 * <p>With {@code base}, the request is an adjustment: the current diagram goes in the message and
 * the model is told to keep everything the instruction does not touch. A kind the person picked
 * is enforced here rather than trusted to the model.
 *
 * <p>Same transaction shape as every notebook model call: read in one short transaction, call
 * the model with none open (see {@link NotebookTransactions}).
 */
@Service
public class DiagramService {

    private final NotebookLlm llm;
    private final StudyContextBuilder contextBuilder;
    private final NotebookOwnership ownership;
    private final NotebookTransactions tx;
    /** {@code prompts/notebookDiagram.txt}: the JSON shape and two examples. Plain text, not a template. */
    private final String format;

    public DiagramService(NotebookLlm llm, StudyContextBuilder contextBuilder, NotebookOwnership ownership,
            NotebookTransactions tx, @Value("classpath:/prompts/notebookDiagram.txt") Resource format) {
        this.llm = llm;
        this.contextBuilder = contextBuilder;
        this.ownership = ownership;
        this.tx = tx;
        try {
            this.format = StreamUtils.copyToString(format.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("prompts/notebookDiagram.txt is missing", e);
        }
    }

    public DiagramResultDTO generate(User user, UUID pageId, DiagramRequestDTO request) {
        boolean grounded = request.grounded() == null || request.grounded();
        DiagramSpec base = request.base() == null ? null : DiagramSanitizer.clean(request.base())
                .orElseThrow(() -> new BusinessException(ErrorKey.INVALID_REQUEST,
                        "base must be a diagram with at least two labelled nodes"));

        record Prompt(String message, boolean fromNotes) {
        }
        Prompt prompt = tx.read(() -> {
            NotebookPage page = ownership.page(user.getId(), pageId);
            StringBuilder message = new StringBuilder("DIAGRAM\n").append(subject(user, page));
            boolean fromNotes = false;
            if (grounded) {
                StudyContextBuilder.Context context =
                        contextBuilder.forQuestion(user, page, page.getTitle() + " " + request.prompt());
                message.append(context.render());
                if (context.isEmpty()) {
                    message.append("(none: this page has no notes and no sources yet)\n\n");
                }
                fromNotes = !context.isEmpty();
            } else {
                message.append("No passages: the person asked for a diagram from your own knowledge.\n\n");
            }
            return new Prompt(message.toString(), fromNotes);
        });

        StringBuilder message = new StringBuilder(prompt.message());
        message.append(request.kind() == null
                ? "Pick the kind that fits the request best.\n"
                : "Draw it as a " + request.kind() + " diagram.\n");
        if (base == null) {
            message.append("The person asks for: \"").append(request.prompt().strip()).append("\"\n\n");
        } else {
            message.append("The diagram the person has now:\n").append(DiagramJson.write(base)).append('\n')
                    .append("The person asks for this change: \"").append(request.prompt().strip()).append("\"\n")
                    .append("Return the whole diagram with the change made. Keep everything the change does not ")
                    .append("touch: the same ids, labels, details, tones and order.\n\n");
        }
        message.append(format);

        DiagramSpec answer = llm.call(DiagramSpec.class, message.toString(), user);
        DiagramSpec drawn = request.kind() == null || answer == null ? answer
                : new DiagramSpec(answer.v(), request.kind(), answer.title(), answer.nodes(), answer.links());
        DiagramSpec clean = DiagramSanitizer.clean(drawn).orElseThrow(() ->
                new BusinessException(ErrorKey.AI_UNAVAILABLE, "The diagram came back with fewer than two nodes"));
        return new DiagramResultDTO(clean, prompt.fromNotes());
    }

    /** Which page and topic the diagram is for, so a diagram from the model's own knowledge stays on the subject. */
    private String subject(User user, NotebookPage page) {
        if (page.isTopic()) {
            return "The person is studying the topic \"" + page.getTitle() + "\".\n";
        }
        String line = "The person is studying the page \"" + page.getTitle() + "\"";
        if (page.getTopicId() != null) {
            line += ", in the topic \"" + ownership.page(user.getId(), page.getTopicId()).getTitle() + "\"";
        }
        return line + ".\n";
    }
}
