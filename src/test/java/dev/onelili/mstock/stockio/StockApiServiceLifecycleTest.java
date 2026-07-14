package dev.onelili.mstock.stockio;

import dev.onelili.mstock.model.StockInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockApiServiceLifecycleTest {

    @Test
    void cancelsPendingRequestsAndRejectsRequestsAfterClose() {
        PendingSource source = new PendingSource();
        StockApiService service = new StockApiService(
                List.of(source), 1_000L, Logger.getAnonymousLogger());

        CompletableFuture<StockInfo> request = service.fetch("TEST");
        service.close();
        service.close();

        assertTrue(request.isCancelled());
        assertTrue(source.closed.get());
        assertFalse(service.isSupported("TEST"));
        CompletionException error = assertThrows(
                CompletionException.class, () -> service.fetch("TEST").join());
        assertTrue(error.getCause() instanceof IllegalStateException);
    }

    private static final class PendingSource implements StockSource {
        private final CompletableFuture<StockInfo> request = new CompletableFuture<>();
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public boolean supports(String code) {
            return true;
        }

        @Override
        public CompletableFuture<StockInfo> fetch(String code) {
            return request;
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }
}
