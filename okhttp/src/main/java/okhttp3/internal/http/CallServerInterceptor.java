/*
 * Copyright (C) 2016 Square, Inc.
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
package okhttp3.internal.http;

import java.io.IOException;
import java.net.ProtocolException;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.Util;
import okhttp3.internal.connection.RealConnection;
import okhttp3.internal.connection.StreamAllocation;
import okio.Buffer;
import okio.BufferedSink;
import okio.ForwardingSink;
import okio.Okio;
import okio.Sink;

/**
 * This is the last interceptor in the chain. It makes a network call to the server.
 * <p>
 * <p>
 * 这是拦截器链最后一个拦截器，它向服务器发起真正的网络请求
 * 这个过滤器的功能其实就是负责网络通信最后一个步骤：数据交换，
 * 也就是负责向服务器发送请求数据、从服务器读取响应数据(实际网络请求)。
 *
 * 1.先写入请求Header
 * 2.如果请求头的Expect: 100-continue时，只发送请求头，执行3，不然执行4
 * 3.根据后台返回的结果判断是否继续请求流程
 * 4.写入请求体，完成请求
 * 5.得到响应头，构建初步响应
 * 6.构建响应体，完成最终响应
 * 7.返回响应
 *

 */
public final class CallServerInterceptor implements Interceptor {
    private final boolean forWebSocket;

    public CallServerInterceptor(boolean forWebSocket) {
        this.forWebSocket = forWebSocket;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        RealInterceptorChain realChain = (RealInterceptorChain) chain;
        HttpCodec httpCodec = realChain.httpStream();
        StreamAllocation streamAllocation = realChain.streamAllocation();
        RealConnection connection = (RealConnection) realChain.connection();
        Request request = realChain.request();

        long sentRequestMillis = System.currentTimeMillis();

        //开始写入header
        realChain.eventListener().requestHeadersStart(realChain.call());
        //HttpCodec其实是一个接口，对应的使用策略模式分别根据是Http还是Http/2请求，这里就看一下Http1Codec的实现吧。
        httpCodec.writeRequestHeaders(request);//1.开始写入header
        //写入结束
        realChain.eventListener().requestHeadersEnd(realChain.call(), request);

        Response.Builder responseBuilder = null;//首先构建了一个null的responseBuilder。
        if (HttpMethod.permitsRequestBody(request.method()) && request.body() != null) {
            // If there's a "Expect: 100-continue" header on the request, wait for a "HTTP/1.1 100
            // Continue" response before transmitting the request body. If we don't get that, return
            // what we did get (such as a 4xx response) without ever transmitting the request body.
            //2.当Header为Expect: 100-continue时，只发送请求头
//            当Header为Expect: 100-continue时，只发送请求头
//            发送一个请求, 包含一个Expect:100-continue, 询问Server使用愿意接受数据
//            接收到Server返回的100-continue应答以后, 才把数据POST给Server

//            1.如果可以继续请求，则responseBuilder=null
//            2.如果不行，则responseBuilder不为空，并且为返回的Header
            if ("100-continue".equalsIgnoreCase(request.header("Expect"))) {
                httpCodec.flushRequest();//刷新请求
                realChain.eventListener().responseHeadersStart(realChain.call());
                //读取response的header信息，并返回一个responseBuilder赋值给responseBuilder。
                responseBuilder = httpCodec.readResponseHeaders(true);
            }

            //如果可以继续请求，则Responsebuilder=null，执行if判断里的内容，可以看到就是对于请求体的写入操作，当然任然是使用Okio进行写入操作。
            if (responseBuilder == null) {
                // Write the request body if the "Expect: 100-continue" expectation was met.
                //得到响应后，根据Resposne判断是否写入请求体
                // Write the request body if the "Expect: 100-continue" expectation was met.
                //写入请求体
                realChain.eventListener().requestBodyStart(realChain.call());
                long contentLength = request.body().contentLength();
                CountingSink requestBodyOut =
                        new CountingSink(httpCodec.createRequestBody(request, contentLength));
                BufferedSink bufferedRequestBody = Okio.buffer(requestBodyOut);

                //3.写入请求体
                request.body().writeTo(bufferedRequestBody);
                //写入完成
                bufferedRequestBody.close();
                realChain.eventListener()
                        .requestBodyEnd(realChain.call(), requestBodyOut.successfulCount);
            } else if (!connection.isMultiplexed()) {
                // If the "Expect: 100-continue" expectation wasn't met, prevent the HTTP/1 connection
                // from being reused. Otherwise we're still obligated to transmit the request body to
                // leave the connection in a consistent state.
                streamAllocation.noNewStreams();
            }
        }

        //4.结束请求
        httpCodec.finishRequest();

        if (responseBuilder == null) {
            realChain.eventListener().responseHeadersStart(realChain.call());
            //5.得到响应头
            responseBuilder = httpCodec.readResponseHeaders(false);
        }

        //6.构建初步响应
//        通过返回得到的responseBuilder构建携带有响应头的Reponse。
        Response response = responseBuilder
                .request(request)
                .handshake(streamAllocation.connection().handshake())
                .sentRequestAtMillis(sentRequestMillis)
                .receivedResponseAtMillis(System.currentTimeMillis())
                .build();

        int code = response.code();
        if (code == 100) {
            // server sent a 100-continue even though we did not request one.
            // try again to read the actual response
            responseBuilder = httpCodec.readResponseHeaders(false);

            response = responseBuilder
                    .request(request)
                    .handshake(streamAllocation.connection().handshake())
                    .sentRequestAtMillis(sentRequestMillis)
                    .receivedResponseAtMillis(System.currentTimeMillis())
                    .build();

            code = response.code();
        }

        realChain.eventListener()
                .responseHeadersEnd(realChain.call(), response);

//        剩下的其实就是对弈返回码的判断，对应正常的返回码的话，构建响应体到Response中，最后将Response返回。
        if (forWebSocket && code == 101) {
            // Connection is upgrading, but we need to ensure interceptors see a non-null response body.
            //构建响应体
            response = response.newBuilder()
                    .body(Util.EMPTY_RESPONSE)
                    .build();
        } else {
            response = response.newBuilder()
                    .body(httpCodec.openResponseBody(response))
                    .build();
        }

        if ("close".equalsIgnoreCase(response.request().header("Connection"))
                || "close".equalsIgnoreCase(response.header("Connection"))) {
            streamAllocation.noNewStreams();
        }

        if ((code == 204 || code == 205) && response.body().contentLength() > 0) {
            throw new ProtocolException(
                    "HTTP " + code + " had non-zero Content-Length: " + response.body().contentLength());
        }

        //返回响应
        return response;
    }

    static final class CountingSink extends ForwardingSink {
        long successfulCount;

        CountingSink(Sink delegate) {
            super(delegate);
        }

        @Override
        public void write(Buffer source, long byteCount) throws IOException {
            super.write(source, byteCount);
            successfulCount += byteCount;
        }
    }
}
