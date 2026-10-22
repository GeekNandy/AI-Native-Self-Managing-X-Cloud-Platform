package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Enforces the request limit even when a client streams HTTP chunked bodies. */
final class BoundedRequest extends HttpServletRequestWrapper {
    private ServletInputStream input;

    BoundedRequest(HttpServletRequest request) {
        super(request);
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        if (input != null) return input;
        var delegate = super.getInputStream();
        input =
                new ServletInputStream() {
                    long bytes;

                    private void count(int n) throws IOException {
                        if (n > 0 && (bytes += n) > 1_048_576)
                            throw new IOException("Request exceeds 1 MiB");
                    }

                    @Override
                    public int read() throws IOException {
                        int value = delegate.read();
                        count(value < 0 ? 0 : 1);
                        return value;
                    }

                    @Override
                    public int read(byte[] b, int offset, int length) throws IOException {
                        int n = delegate.read(b, offset, Math.min(length, 8192));
                        count(n);
                        return n;
                    }

                    @Override
                    public boolean isFinished() {
                        return delegate.isFinished();
                    }

                    @Override
                    public boolean isReady() {
                        return delegate.isReady();
                    }

                    @Override
                    public void setReadListener(ReadListener listener) {
                        delegate.setReadListener(listener);
                    }
                };
        return input;
    }

    @Override
    public BufferedReader getReader() throws IOException {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
}
