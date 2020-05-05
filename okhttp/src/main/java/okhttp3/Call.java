/*
 * Copyright (C) 2014 Square, Inc.
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
package okhttp3;

import java.io.IOException;

import okio.Timeout;

/**
 * A call is a request that has been prepared for execution. A call can be canceled. As this object
 * represents a single request/response pair (stream), it cannot be executed twice.
 */
public interface Call extends Cloneable {
    /**
     * Returns the original request that initiated this call.
     */
    // 返回当前请求
    Request request();

    /**
     * Invokes the request immediately, and blocks until the response can be processed or is in
     * error.
     *
     * <p>To avoid leaking resources callers should close the {@link Response} which in turn will
     * close the underlying {@link ResponseBody}.
     *
     * <pre>{@code
     *
     *   // ensure the response (and underlying response body) is closed
     *   try (Response response = client.newCall(request).execute()) {
     *     ...
     *   }
     *
     * }</pre>
     *
     * <p>The caller may read the response body with the response's {@link Response#body} method. To
     * avoid leaking resources callers must {@linkplain ResponseBody close the response body} or the
     * Response.
     *
     * <p>Note that transport-layer success (receiving a HTTP response code, headers and body) does
     * not necessarily indicate application-layer success: {@code response} may still indicate an
     * unhappy HTTP response code like 404 or 500.
     *
     * @throws IOException           if the request could not be executed due to cancellation, a connectivity
     *                               problem or timeout. Because networks can fail during an exchange, it is possible that the
     *                               remote server accepted the request before the failure.
     * @throws IllegalStateException when the call has already been executed.
     */
    // 同步请求方法
    Response execute() throws IOException;

    /**
     * Schedules the request to be executed at some point in the future.
     *
     * <p>The {@link OkHttpClient#dispatcher dispatcher} defines when the request will run: usually
     * immediately unless there are several other requests currently being executed.
     *
     * <p>This client will later call back {@code responseCallback} with either an HTTP response or a
     * failure exception.
     *
     * @throws IllegalStateException when the call has already been executed.
     */
    // 异步请求方法
    void enqueue(Callback responseCallback);

    /**
     * Cancels the request, if possible. Requests that are already complete cannot be canceled.
     */
    //取消请求
    void cancel();

    /**
     * Returns true if this call has been either {@linkplain #execute() executed} or {@linkplain
     * #enqueue(Callback) enqueued}. It is an error to execute a call more than once.
     */
    // 请求是否在执行（当execute()或者enqueue(Callback responseCallback)执行后该方法返回true）
    boolean isExecuted();

    // 请求是否被取消
    boolean isCanceled();

    /**
     * Returns a timeout that spans the entire call: resolving DNS, connecting, writing the request
     * body, server processing, and reading the response body. If the call requires redirects or
     * retries all must complete within one timeout period.
     *
     * <p>Configure the client's default timeout with {@link OkHttpClient.Builder#callTimeout}.
     */
    Timeout timeout();

    /**
     * Create a new, identical call to this one which can be enqueued or executed even if this call
     * has already been.
     */
    // 创建一个新的一模一样的请求
    Call clone();

    interface Factory {
        Call newCall(Request request);
    }
}
