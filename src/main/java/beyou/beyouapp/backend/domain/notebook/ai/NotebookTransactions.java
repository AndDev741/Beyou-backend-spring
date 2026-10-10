package beyou.beyouapp.backend.domain.notebook.ai;

import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Short transactions on either side of a model call, never one across it.
 *
 * <p>A notebook AI call can take up to {@link NotebookLlm#BUDGET}. Inside a {@code @Transactional}
 * method that whole wait holds one of the pool's ten connections, so ten slow answers at once
 * leave the rest of the app waiting for a connection. The services that call the model therefore
 * read what the prompt needs in one short transaction, call the model with none open, and write
 * what came back in a second one. DailyBriefingService and DailyBriefingWrites have the same shape.
 *
 * <p>Both templates JOIN a transaction the caller already holds (REQUIRED), so a caller marked
 * {@code @Transactional} quietly brings the long hold back. NotebookModelCallTransactionIT asks
 * every model-calling path whether a transaction is open at the moment of the call.
 *
 * <p>Whatever crosses from the read to the model must be plain values: an entity loaded in the
 * first transaction is detached by the time the second one runs, so the write side loads what it
 * writes to again.
 */
@Component
public class NotebookTransactions {

    private final TransactionTemplate read;
    private final TransactionTemplate write;

    public NotebookTransactions(PlatformTransactionManager transactionManager) {
        this.read = new TransactionTemplate(transactionManager);
        this.read.setReadOnly(true);
        this.write = new TransactionTemplate(transactionManager);
    }

    public <T> T read(Supplier<T> work) {
        return read.execute(status -> work.get());
    }

    public <T> T write(Supplier<T> work) {
        return write.execute(status -> work.get());
    }
}
