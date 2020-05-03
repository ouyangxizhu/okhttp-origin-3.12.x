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
 * 就是一个空的接口，作用就是标记那些不能被重复请求的请求体，这时候可能就想要了解一下那些请求是不能被重复请求的哪？
 * 看一下那些Request实现了这个接口，结果会发现，到目前Okhttp源码中，
 * 只有一种请求实现了这个接口，那就是StreamedRequestBody。

 */
package okhttp3.internal.http;

public interface UnrepeatableRequestBody {
}
