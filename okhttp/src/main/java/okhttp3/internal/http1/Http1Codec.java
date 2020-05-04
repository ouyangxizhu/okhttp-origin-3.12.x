/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.internal.http1;

import java.io.EOFException;
import java.io.IOException;
import java.net.ProtocolException;

import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.internal.Internal;
import okhttp3.internal.Util;
import okhttp3.internal.connection.RealConnection;
import okhttp3.internal.connection.StreamAllocation;
import okhttp3.internal.http.HttpCodec;
import okhttp3.internal.http.HttpHeaders;
import okhttp3.internal.http.RealResponseBody;
import okhttp3.internal.http.RequestLine;
import okhttp3.internal.http.StatusLine;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.ForwardingTimeout;
import okio.Okio;
import okio.Sink;
import okio.Source;
import okio.Timeout;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static okhttp3.internal.Util.checkOffsetAndCount;
import static okhttp3.internal.http.StatusLine.HTTP_CONTINUE;

/**
 * A socket connection that can be used to send HTTP/1.1 messages. This class strictly enforces the
 * following lifecycle:
 *
 * <ol>
 *     <li>{@linkplain #writeRequest Send request headers}.
 *     <li>Open a sink to write the request body. Either {@linkplain #newFixedLengthSink
 *         fixed-length} or {@link #newChunkedSink chunked}.
 *     <li>Write to and then close that sink.
 *     <li>{@linkplain #readResponseHeaders Read response headers}.
 *     <li>Open a source to read the response body. Either {@linkplain #newFixedLengthSource
 *         fixed-length}, {@linkplain #newChunkedSource chunked} or {@linkplain
 *         #newUnknownLengthSource unknown length}.
 *     <li>Read from and close that source.
 * </ol>
 *
 * <p>Exchanges that do not have a request body may skip creating and closing the request body.
 * Exchanges that do not have a response body can call {@link #newFixedLengthSource(long)
 * newFixedLengthSource(0)} and may skip reading and closing that source.
 */
public final class Http1Codec implements HttpCodec {
    private static final int STATE_IDLE = 0; // Idle connections are ready to write request headers.
    private static final int STATE_OPEN_REQUEST_BODY = 1;
    private static final int STATE_WRITING_REQUEST_BODY = 2;
    private static final int STATE_READ_RESPONSE_HEADERS = 3;
    private static final int STATE_OPEN_RESPONSE_BODY = 4;
    private static final int STATE_READING_RESPONSE_BODY = 5;
    private static final int STATE_CLOSED = 6;
    private static final int HEADER_LIMIT = 256 * 1024;

    /**
     * The client that configures this stream. May be null for HTTPS proxy tunnels.
     */
    final OkHttpClient client;
    /**
     * The stream allocation that owns this stream. May be null for HTTPS proxy tunnels.
     */
    final StreamAllocation streamAllocation;

    final BufferedSource source;
    final BufferedSink sink;
    int state = STATE_IDLE;
    private long headerLimit = HEADER_LIMIT;

    public Http1Codec(OkHttpClient client, StreamAllocation streamAllocation, BufferedSource source,
                      BufferedSink sink) {
        this.client = client;
        this.streamAllocation = streamAllocation;
        this.source = source;
        this.sink = sink;
    }

    @Override
    public Sink createRequestBody(Request request, long contentLength) {
        if ("chunked".equalsIgnoreCase(request.header("Transfer-Encoding"))) {
            // Stream a request body of unknown length.
            return newChunkedSink();
        }

        if (contentLength != -1) {
            // Stream a request body of a known length.
            return newFixedLengthSink(contentLength);
        }

        throw new IllegalStateException(
                "Cannot stream a request body without chunked encoding or a known content length!");
    }

    @Override
    public void cancel() {
        RealConnection connection = streamAllocation.connection();
        if (connection != null) connection.cancel();
    }

    /**
     * Prepares the HTTP headers and sends them to the server.
     *
     * <p>For streaming requests with a body, headers must be prepared <strong>before</strong> the
     * output stream has been written to. Otherwise the body would need to be buffered!
     *
     * <p>For non-streaming requests with a body, headers must be prepared <strong>after</strong> the
     * output stream has been written to and closed. This ensures that the {@code Content-Length}
     * header field receives the proper value.
     */
    @Override
    public void writeRequestHeaders(Request request) throws IOException {
        String requestLine = RequestLine.get(
                request, streamAllocation.connection().route().proxy().type());
        //写入header
        writeRequest(request.headers(), requestLine);
    }

    @Override
    public ResponseBody openResponseBody(Response response) throws IOException {
        streamAllocation.eventListener.responseBodyStart(streamAllocation.call);
        String contentType = response.header("Content-Type");

        if (!HttpHeaders.hasBody(response)) {
            Source source = newFixedLengthSource(0);
            return new RealResponseBody(contentType, 0, Okio.buffer(source));
        }

        if ("chunked".equalsIgnoreCase(response.header("Transfer-Encoding"))) {
            Source source = newChunkedSource(response.request().url());
            return new RealResponseBody(contentType, -1L, Okio.buffer(source));
        }

        long contentLength = HttpHeaders.contentLength(response);
        if (contentLength != -1) {
            Source source = newFixedLengthSource(contentLength);
            return new RealResponseBody(contentType, contentLength, Okio.buffer(source));
        }

        return new RealResponseBody(contentType, -1L, Okio.buffer(newUnknownLengthSource()));
    }

    /**
     * Returns true if this connection is closed.
     */
    public boolean isClosed() {
        return state == STATE_CLOSED;
    }

    @Override
    public void flushRequest() throws IOException {
        sink.flush();
    }

    @Override
    public void finishRequest() throws IOException {
        sink.flush();
    }

    /**
     * Returns bytes of a request header for sending on an HTTP transport.
     * 就是遍历Header，然后通过前一个过滤器建立的连接得到的Sink，来进行写操作，这也是为什么OkHttp依赖于Okio。
     * 这里还有一个可以注意的地方，这里最后将state变量赋值为了STATE_OPEN_REQUEST_BODY，其实会发现，随着方法的 进行，
     * 这个state变量会从上一个状态变为下一个状态，这里其实用到了状态模式的思想，
     * 虽然没有那么细致将每个状态分出来，但是状态模式的思想，状态的改变还是用到的。
     *
     *
     */
    public void writeRequest(Headers headers, String requestLine) throws IOException {
        //写入header
        if (state != STATE_IDLE) throw new IllegalStateException("state: " + state);
        //Okio中的sink，底层是socket使用的是okio优化
        sink.writeUtf8(requestLine).writeUtf8("\r\n");
        for (int i = 0, size = headers.size(); i < size; i++) {
            sink.writeUtf8(headers.name(i))
                    .writeUtf8(": ")
                    .writeUtf8(headers.value(i))
                    .writeUtf8("\r\n");
        }
        sink.writeUtf8("\r\n");
        state = STATE_OPEN_REQUEST_BODY;
    }

    /**
     * 第一步：构建了一个ResponseBulder。
     * 第二步：通过返回的状态码判断是否请求可以继续进行（“Expect:100-continue”情况的处理），如果可以返回null.
     * 第三步：否则，也就是不可以，返回构建的ResponseBuilder。
     *
     *
     * @param expectContinue true to return null if this is an intermediate response with a "100"
     * @return
     * @throws IOException
     */
    @Override
    public Response.Builder readResponseHeaders(boolean expectContinue) throws IOException {
        if (state != STATE_OPEN_REQUEST_BODY && state != STATE_READ_RESPONSE_HEADERS) {
            throw new IllegalStateException("state: " + state);
        }

        try {
            StatusLine statusLine = StatusLine.parse(readHeaderLine());

            //构建一个reponseBuilder
            Response.Builder responseBuilder = new Response.Builder()
                    .protocol(statusLine.protocol)
                    .code(statusLine.code)
                    .message(statusLine.message)
                    .headers(readHeaders());

            //如果得到的返回码是可以继续访问，返回null
            if (expectContinue && statusLine.code == HTTP_CONTINUE) {
                return null;
            } else if (statusLine.code == HTTP_CONTINUE) {
                state = STATE_READ_RESPONSE_HEADERS;
                return responseBuilder;
            }

            state = STATE_OPEN_RESPONSE_BODY;
            //不然返回构建出来的reponseBuilder
            return responseBuilder;
        } catch (EOFException e) {
            // Provide more context if the server ends the stream before sending a response.
            IOException exception = new IOException("unexpected end of stream on " + streamAllocation);
            exception.initCause(e);
            throw exception;
        }
    }

    private String readHeaderLine() throws IOException {
        String line = source.readUtf8LineStrict(headerLimit);
        headerLimit -= line.length();
        return line;
    }

    /**
     * Reads headers or trailers.
     */
    public Headers readHeaders() throws IOException {
        Headers.Builder headers = new Headers.Builder();
        // parse the result headers until the first blank line
        for (String line; (line = readHeaderLine()).length() != 0; ) {
            Internal.instance.addLenient(headers, line);
        }
        return headers.build();
    }

    public Sink newChunkedSink() {
        if (state != STATE_OPEN_REQUEST_BODY) throw new IllegalStateException("state: " + state);
        state = STATE_WRITING_REQUEST_BODY;
        return new ChunkedSink();
    }

    public Sink newFixedLengthSink(long contentLength) {
        if (state != STATE_OPEN_REQUEST_BODY) throw new IllegalStateException("state: " + state);
        state = STATE_WRITING_REQUEST_BODY;
        return new FixedLengthSink(contentLength);
    }

    public Source newFixedLengthSource(long length) throws IOException {
        if (state != STATE_OPEN_RESPONSE_BODY) throw new IllegalStateException("state: " + state);
        state = STATE_READING_RESPONSE_BODY;
        return new FixedLengthSource(length);
    }

    public Source newChunkedSource(HttpUrl url) throws IOException {
        if (state != STATE_OPEN_RESPONSE_BODY) throw new IllegalStateException("state: " + state);
        state = STATE_READING_RESPONSE_BODY;
        return new ChunkedSource(url);
    }

    public Source newUnknownLengthSource() throws IOException {
        if (state != STATE_OPEN_RESPONSE_BODY) throw new IllegalStateException("state: " + state);
        if (streamAllocation == null) throw new IllegalStateException("streamAllocation == null");
        state = STATE_READING_RESPONSE_BODY;
        streamAllocation.noNewStreams();
        return new UnknownLengthSource();
    }

    /**
     * Sets the delegate of {@code timeout} to {@link Timeout#NONE} and resets its underlying timeout
     * to the default configuration. Use this to avoid unexpected sharing of timeouts between pooled
     * connections.
     */
    void detachTimeout(ForwardingTimeout timeout) {
        Timeout oldDelegate = timeout.delegate();
        timeout.setDelegate(Timeout.NONE);
        oldDelegate.clearDeadline();
        oldDelegate.clearTimeout();
    }

    /**
     * An HTTP body with a fixed length known in advance.
     */
    private final class FixedLengthSink implements Sink {
        private final ForwardingTimeout timeout = new ForwardingTimeout(sink.timeout());
        private boolean closed;
        private long bytesRemaining;

        FixedLengthSink(long bytesRemaining) {
            this.bytesRemaining = bytesRemaining;
        }

        @Override
        public Timeout timeout() {
            return timeout;
        }

        @Override
        public void write(Buffer source, long byteCount) throws IOException {
            if (closed) throw new IllegalStateException("closed");
            checkOffsetAndCount(source.size(), 0, byteCount);
            if (byteCount > bytesRemaining) {
                throw new ProtocolException("expected " + bytesRemaining
                        + " bytes but received " + byteCount);
            }
            sink.write(source, byteCount);
            bytesRemaining -= byteCount;
        }

        @Override
        public void flush() throws IOException {
            if (closed)
                return; // Don't throw; this stream might have been closed on the caller's behalf.
            sink.flush();
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            if (bytesRemaining > 0) throw new ProtocolException("unexpected end of stream");
            detachTimeout(timeout);
            state = STATE_READ_RESPONSE_HEADERS;
        }
    }

    /**
     * An HTTP body with alternating chunk sizes and chunk bodies. It is the caller's responsibility
     * to buffer chunks; typically by using a buffered sink with this sink.
     */
    private final class ChunkedSink implements Sink {
        private final ForwardingTimeout timeout = new ForwardingTimeout(sink.timeout());
        private boolean closed;

        ChunkedSink() {
        }

        @Override
        public Timeout timeout() {
            return timeout;
        }

        @Override
        public void write(Buffer source, long byteCount) throws IOException {
            if (closed) throw new IllegalStateException("closed");
            if (byteCount == 0) return;

            sink.writeHexadecimalUnsignedLong(byteCount);
            sink.writeUtf8("\r\n");
            sink.write(source, byteCount);
            sink.writeUtf8("\r\n");
        }

        @Override
        public synchronized void flush() throws IOException {
            if (closed)
                return; // Don't throw; this stream might have been closed on the caller's behalf.
            sink.flush();
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) return;
            closed = true;
            sink.writeUtf8("0\r\n\r\n");
            detachTimeout(timeout);
            state = STATE_READ_RESPONSE_HEADERS;
        }
    }

    private abstract class AbstractSource implements Source {
        protected final ForwardingTimeout timeout = new ForwardingTimeout(source.timeout());
        protected boolean closed;
        protected long bytesRead = 0;

        @Override
        public Timeout timeout() {
            return timeout;
        }

        @Override
        public long read(Buffer sink, long byteCount) throws IOException {
            try {
                long read = source.read(sink, byteCount);
                if (read > 0) {
                    bytesRead += read;
                }
                return read;
            } catch (IOException e) {
                endOfInput(false, e);
                throw e;
            }
        }

        /**
         * Closes the cache entry and makes the socket available for reuse. This should be invoked when
         * the end of the body has been reached.
         */
        protected final void endOfInput(boolean reuseConnection, IOException e) throws IOException {
            if (state == STATE_CLOSED) return;
            if (state != STATE_READING_RESPONSE_BODY)
                throw new IllegalStateException("state: " + state);

            detachTimeout(timeout);

            state = STATE_CLOSED;
            if (streamAllocation != null) {
                streamAllocation.streamFinished(!reuseConnection, Http1Codec.this, bytesRead, e);
            }
        }
    }

    /**
     * An HTTP body with a fixed length specified in advance.
     */
    private class FixedLengthSource extends AbstractSource {
        private long bytesRemaining;

        FixedLengthSource(long length) throws IOException {
            bytesRemaining = length;
            if (bytesRemaining == 0) {
                endOfInput(true, null);
            }
        }

        @Override
        public long read(Buffer sink, long byteCount) throws IOException {
            if (byteCount < 0) throw new IllegalArgumentException("byteCount < 0: " + byteCount);
            if (closed) throw new IllegalStateException("closed");
            if (bytesRemaining == 0) return -1;

            long read = super.read(sink, Math.min(bytesRemaining, byteCount));
            if (read == -1) {
                ProtocolException e = new ProtocolException("unexpected end of stream");
                endOfInput(false, e); // The server didn't supply the promised content length.
                throw e;
            }

            bytesRemaining -= read;
            if (bytesRemaining == 0) {
                endOfInput(true, null);
            }
            return read;
        }

        @Override
        public void close() throws IOException {
            if (closed) return;

            if (bytesRemaining != 0 && !Util.discard(this, DISCARD_STREAM_TIMEOUT_MILLIS, MILLISECONDS)) {
                endOfInput(false, null);
            }

            closed = true;
        }
    }

    /**
     * An HTTP body with alternating chunk sizes and chunk bodies.
     */
    private class ChunkedSource extends AbstractSource {
        private static final long NO_CHUNK_YET = -1L;
        private final HttpUrl url;
        private long bytesRemainingInChunk = NO_CHUNK_YET;
        private boolean hasMoreChunks = true;

        ChunkedSource(HttpUrl url) {
            this.url = url;
        }

        @Override
        public long read(Buffer sink, long byteCount) throws IOException {
            if (byteCount < 0) throw new IllegalArgumentException("byteCount < 0: " + byteCount);
            if (closed) throw new IllegalStateException("closed");
            if (!hasMoreChunks) return -1;

            if (bytesRemainingInChunk == 0 || bytesRemainingInChunk == NO_CHUNK_YET) {
                readChunkSize();
                if (!hasMoreChunks) return -1;
            }

            long read = super.read(sink, Math.min(byteCount, bytesRemainingInChunk));
            if (read == -1) {
                ProtocolException e = new ProtocolException("unexpected end of stream");
                endOfInput(false, e); // The server didn't supply the promised chunk length.
                throw e;
            }
            bytesRemainingInChunk -= read;
            return read;
        }

        private void readChunkSize() throws IOException {
            // Read the suffix of the previous chunk.
            if (bytesRemainingInChunk != NO_CHUNK_YET) {
                source.readUtf8LineStrict();
            }
            try {
                bytesRemainingInChunk = source.readHexadecimalUnsignedLong();
                String extensions = source.readUtf8LineStrict().trim();
                if (bytesRemainingInChunk < 0 || (!extensions.isEmpty() && !extensions.startsWith(";"))) {
                    throw new ProtocolException("expected chunk size and optional extensions but was \""
                            + bytesRemainingInChunk + extensions + "\"");
                }
            } catch (NumberFormatException e) {
                throw new ProtocolException(e.getMessage());
            }
            if (bytesRemainingInChunk == 0L) {
                hasMoreChunks = false;
                HttpHeaders.receiveHeaders(client.cookieJar(), url, readHeaders());
                endOfInput(true, null);
            }
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            if (hasMoreChunks && !Util.discard(this, DISCARD_STREAM_TIMEOUT_MILLIS, MILLISECONDS)) {
                endOfInput(false, null);
            }
            closed = true;
        }
    }

    /**
     * An HTTP message body terminated by the end of the underlying stream.
     */
    private class UnknownLengthSource extends AbstractSource {
        private boolean inputExhausted;

        UnknownLengthSource() {
        }

        @Override
        public long read(Buffer sink, long byteCount)
                throws IOException {
            if (byteCount < 0) throw new IllegalArgumentException("byteCount < 0: " + byteCount);
            if (closed) throw new IllegalStateException("closed");
            if (inputExhausted) return -1;

            long read = super.read(sink, byteCount);
            if (read == -1) {
                inputExhausted = true;
                endOfInput(true, null);
                return -1;
            }
            return read;
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            if (!inputExhausted) {
                endOfInput(false, null);
            }
            closed = true;
        }
    }
}
