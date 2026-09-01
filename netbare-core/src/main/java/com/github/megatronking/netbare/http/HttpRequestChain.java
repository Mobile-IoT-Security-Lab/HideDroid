/*  NetBare - An android network capture and injection library.
 *  Copyright (C) 2018-2019 Megatron King
 *  Copyright (C) 2018-2019 GuoShi
 *
 *  NetBare is free software: you can redistribute it and/or modify it under the terms
 *  of the GNU General Public License as published by the Free Software Found-
 *  ation, either version 3 of the License, or (at your option) any later version.
 *
 *  NetBare is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
 *  PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with NetBare.
 *  If not, see <http://www.gnu.org/licenses/>.
 */
package com.github.megatronking.netbare.http;

import androidx.annotation.NonNull;

import com.github.megatronking.netbare.gateway.AbstractRequestChain;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Http request chain, responsible for intercepting http request packets.
 *
 * @author Megatron King
 * @since 2018-11-16 23:21
 */
public class HttpRequestChain extends AbstractRequestChain<HttpRequest, HttpInterceptor> {

    private HttpZygoteRequest mZygoteRequest;
    private HttpVirtualGateway mGateway;

    /* package */ HttpRequestChain(HttpZygoteRequest request, List<HttpInterceptor> interceptors) {
        this(request, interceptors, 0, null);
    }

    /* package */ HttpRequestChain(HttpZygoteRequest request, List<HttpInterceptor> interceptors,
                                   int index, Object tag) {
        super(request, interceptors, index, tag);
        this.mZygoteRequest = request;
    }

    /* package */ void setGateway(HttpVirtualGateway gateway) {
        this.mGateway = gateway;
    }

    /* package */ HttpVirtualGateway gateway() {
        return mGateway;
    }

    /**
     * Injects a synthetic HTTP response as if it came from the remote server, without
     * forwarding the intercepted request. The data must be a complete plaintext HTTP/1.1
     * response; it re-enters the response pipeline after the SSL/HTTP2 decoding stages
     * and before the re-encryption stages, so it reaches the client correctly for both
     * cleartext and HTTPS sessions. Must be called synchronously from a request
     * interception; no-op if the chain has no gateway attached (unit tests).
     *
     * @param httpResponseData A complete HTTP/1.1 response, headers and optional body.
     * @throws IOException If an I/O error has occurred writing to the client tunnel.
     */
    public void injectResponse(byte[] httpResponseData) throws IOException {
        if (mGateway != null) {
            mGateway.injectResponse(httpResponseData);
        }
    }

    HttpZygoteRequest zygoteRequest() {
        return mZygoteRequest;
    }

    @Override
    protected void processNext(ByteBuffer buffer, HttpRequest request,
                               List<HttpInterceptor> interceptors, int index, Object tag) throws IOException {
        HttpInterceptor interceptor = interceptors.get(index);
        if (interceptor != null) {
            HttpRequestChain next = new HttpRequestChain(mZygoteRequest, interceptors, ++index, tag);
            next.setGateway(mGateway);
            interceptor.intercept(next, buffer);
        }
    }

    @Override
    @NonNull
    public HttpRequest request() {
        HttpRequest active = mZygoteRequest.getActive();
        return active != null ? active : mZygoteRequest;
    }

}
