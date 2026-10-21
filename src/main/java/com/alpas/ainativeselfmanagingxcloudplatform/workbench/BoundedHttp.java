package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import java.io.IOException;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Bounds the complete response, including a body that stalls after headers have arrived. */
final class BoundedHttp {
    private BoundedHttp() {}

    static final class ResponseTooLarge extends IOException {}

    static HttpResponse<byte[]> send(
            HttpClient http, HttpRequest request, int limit, Duration deadline)
            throws IOException, InterruptedException {
        var response = http.sendAsync(request, info -> new LimitedSubscriber(limit));
        try {
            return response.get(Math.max(1, deadline.toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            response.cancel(true);
            throw new java.net.http.HttpTimeoutException("Complete response deadline exceeded");
        } catch (InterruptedException interrupted) {
            response.cancel(true);
            throw interrupted;
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            while (cause instanceof CompletionException && cause.getCause() != null)
                cause = cause.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("HTTP response failed", cause);
        }
    }

    private static final class LimitedSubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate =
                HttpResponse.BodySubscribers.ofByteArray();
        private final long limit;
        private long count;
        private Flow.Subscription subscription;
        private boolean finished;

        LimitedSubscriber(long limit) {
            this.limit = limit;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (finished) return;
            for (var buffer : buffers) count += buffer.remaining();
            if (count > limit) {
                finished = true;
                subscription.cancel();
                delegate.onError(new ResponseTooLarge());
            } else delegate.onNext(buffers);
        }

        @Override
        public void onError(Throwable error) {
            if (!finished) {
                finished = true;
                delegate.onError(error);
            }
        }

        @Override
        public void onComplete() {
            if (!finished) {
                finished = true;
                delegate.onComplete();
            }
        }
    }
}
