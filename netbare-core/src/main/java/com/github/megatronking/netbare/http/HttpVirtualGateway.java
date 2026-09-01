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

import com.github.megatronking.netbare.gateway.Request;
import com.github.megatronking.netbare.gateway.Response;
import com.github.megatronking.netbare.gateway.VirtualGateway;
import com.github.megatronking.netbare.http2.Http2DecodeInterceptor;
import com.github.megatronking.netbare.http2.Http2EncodeInterceptor;
import com.github.megatronking.netbare.net.Session;
import com.github.megatronking.netbare.ssl.JKS;
import com.github.megatronking.netbare.ssl.SSLEngineFactory;
import com.github.megatronking.netbare.tcp.TcpVirtualGateway;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link VirtualGateway} that is responsible for HTTP(S) packets interception. It integrates
 * several internal {@link HttpInterceptor}s to decode and parse HTTP(S) packets. And also it
 * supports extensional {@link HttpInterceptor}s. Use {@link HttpVirtualGatewayFactory} to
 * create an instance.
 *
 * @author Megatron King
 * @since 2018-11-20 23:43
 */
/* package */ class HttpVirtualGateway extends TcpVirtualGateway {

    private HttpZygoteRequest mHttpZygoteRequest;
    private HttpZygoteResponse mHttpZygoteResponse;

    private List<HttpInterceptor> mInterceptors;

    // Index in mInterceptors right after the ContainerHttpInterceptor: injected
    // responses re-enter the response pipeline here, i.e. after the SSL/HTTP2
    // decoding stages and before the HTTP2 encoding / SSL re-encryption stages,
    // exactly where decrypted server data would appear.
    private final int mInjectIndex;

    /* package */ HttpVirtualGateway(Session session, Request request, Response response, JKS jks,
                                     final List<HttpInterceptorFactory> factories) {
        super(session, request, response);

        HttpSessionFactory sessionFactory = new HttpSessionFactory();
        this.mHttpZygoteRequest = new HttpZygoteRequest(request, sessionFactory);
        this.mHttpZygoteResponse = new HttpZygoteResponse(response, sessionFactory);

        SSLEngineFactory sslEngineFactory;
        try {
            sslEngineFactory = SSLEngineFactory.get(jks);
        } catch (GeneralSecurityException | IOException e) {
            sslEngineFactory = null;
        }

        // Add default interceptors.
        HttpSSLCodecInterceptor codecInterceptor = new HttpSSLCodecInterceptor(sslEngineFactory, request, response);
        this.mInterceptors = new ArrayList<>(8);

        mInterceptors.add(new HttpSniffInterceptor(sessionFactory.create(session.id)));
        mInterceptors.add(codecInterceptor);
        mInterceptors.add(new Http2SniffInterceptor(codecInterceptor));
        mInterceptors.add(new Http2DecodeInterceptor(codecInterceptor, mHttpZygoteRequest, mHttpZygoteResponse));
        mInterceptors.add(new HttpMultiplexInterceptor(mHttpZygoteRequest, mHttpZygoteResponse));
        mInterceptors.add(new HttpHeaderSniffInterceptor(codecInterceptor));
        mInterceptors.add(new ContainerHttpInterceptor(new HttpInterceptorsFactory() {
            @NonNull
            @Override
            public List<HttpInterceptor> create() {
                List<HttpInterceptor> subs = new ArrayList<>(factories.size() + 2);
                subs.add(new HttpHeaderSeparateInterceptor());
                subs.add(new HttpHeaderParseInterceptor());
                // Add extension interceptors.
                for (HttpInterceptorFactory factory : factories) {
                    subs.add(factory.create());
                }
                return subs;
            }
        }));
        mInjectIndex = mInterceptors.size();
        // Goalkeepers.
        mInterceptors.add(mInterceptors.size(), new Http2EncodeInterceptor());
        mInterceptors.add(mInterceptors.size(), new HttpSSLRefluxInterceptor(codecInterceptor));

        //
        // SSL Flow Model:
        //
        //        Request                                  Response
        //
        //     out        in                             in        out
        //      ⇈         ⇊                               ⇊         ⇈
        //       Encrypted                                 Encrypted
        //      ⇈         ⇊                               ⇊         ⇈
        //   -----------------------------------------------------------
        //  |                     Codec Interceptor                     |
        //   -----------------------------------------------------------
        //      ⇈  |      ⇊              ...              ⇊      |  ⇈
        //         |      ⇊              ...              ⇊      |
        //      ⇈  |  Decrypted  |   interceptors  |  Decrypted  |  ⇈
        //         |      ⇊              ...              ⇊      |
        //      ⇈  |      ⇊              ...              ⇊      |  ⇈
        //   -----------------------------------------------------------
        //  |                     Reflux Interceptor                    |
        //   -----------------------------------------------------------
        //      ⇈ ⇇  ⇇  ⇇ ⇊                               ⇊ ⇉  ⇉  ⇉ ⇈
        //
    }

    @Override
    public void onSpecRequest(ByteBuffer buffer) throws IOException {
        HttpRequestChain chain = new HttpRequestChain(mHttpZygoteRequest, mInterceptors);
        chain.setGateway(this);
        chain.process(buffer);
    }

    /**
     * Injects a synthetic plaintext HTTP/1.1 response into the response pipeline, as if
     * the remote server had sent it. Used to answer blocked requests without forwarding
     * them: the data enters after the decoding stages and flows through the normal
     * HTTP2-encode/SSL-encrypt goalkeepers, so it is delivered to the client correctly
     * for cleartext and HTTPS sessions alike. No server data can race it, because the
     * intercepted request is never forwarded. Only valid for HTTP/1.x requests - an
     * HTTP/2 response needs HPACK frames, not raw HTTP/1.1 bytes.
     *
     * @param responseData A complete HTTP/1.1 response, headers and optional body.
     * @throws IOException If an I/O error has occurred writing to the client tunnel.
     */
    /* package */ void injectResponse(byte[] responseData) throws IOException {
        new HttpResponseChain(mHttpZygoteResponse, mInterceptors, mInjectIndex, null)
                .process(ByteBuffer.wrap(responseData));
    }

    @Override
    public void onSpecResponse(ByteBuffer buffer) throws IOException {
        new HttpResponseChain(mHttpZygoteResponse, mInterceptors).process(buffer);
    }

    @Override
    public void onSpecRequestFinished() {
        for (HttpInterceptor interceptor: mInterceptors) {
            interceptor.onRequestFinished(mHttpZygoteRequest);
        }
    }

    @Override
    public void onSpecResponseFinished() {
        for (HttpInterceptor interceptor: mInterceptors) {
            interceptor.onResponseFinished(mHttpZygoteResponse);
        }
    }

}
