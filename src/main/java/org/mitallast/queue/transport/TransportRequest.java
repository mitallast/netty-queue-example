package org.mitallast.queue.transport;

import io.netty.buffer.ByteBufInputStream;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.QueryStringDecoder;
import org.mitallast.queue.rest.RestRequest;

import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TransportRequest implements RestRequest {

    public static final String METHOD_TUNNEL = "_method";
    private FullHttpRequest httpRequest;
    private HttpMethod httpMethod;
    private HttpHeaders httpHeaders;

    private Map<String, List<String>> paramMap;
    private String queryPath;

    public TransportRequest(FullHttpRequest request) {
        this.httpRequest = request;
        this.httpMethod = request.getMethod();
        this.httpHeaders = request.headers();
        this.parseQueryString();
        determineEffectiveHttpMethod();
    }

    @Override
    public HttpMethod getHttpMethod() {
        return httpMethod;
    }

    @Override
    public Map<String, List<String>> getParamMap() {
        return paramMap;
    }

    @Override
    public String param(String param) {
        List<String> params = paramMap.get(param);
        return (params == null || params.isEmpty())
                ? null
                : params.get(0);
    }

    @Override
    public boolean hasParam(String param) {
        return paramMap.containsKey(param);
    }

    @Override
    public HttpHeaders getHttpHeaders() {
        return httpHeaders;
    }

    @Override
    public InputStream getInputStream() {
        return new ByteBufInputStream(httpRequest.content());
    }

    @Override
    public String getQueryPath() {
        return queryPath;
    }

    @Override
    public String getUri() {
        return httpRequest.getUri();
    }

    private void determineEffectiveHttpMethod() {
        if (!HttpMethod.POST.equals(httpRequest.getMethod())) {
            return;
        }

        String methodString = httpHeaders.get(METHOD_TUNNEL);

        if (HttpMethod.PUT.name().equalsIgnoreCase(methodString) || HttpMethod.DELETE.name().equalsIgnoreCase(methodString)) {
            httpMethod = HttpMethod.valueOf(methodString.toUpperCase());
        }
    }

    private void parseQueryString() {

        String uri = httpRequest.getUri();
        if (!uri.contains("?")) {
            paramMap = new HashMap<>();
            queryPath = uri;
            return;
        }

        QueryStringDecoder decoder = new QueryStringDecoder(uri);
        queryPath = decoder.path();
        paramMap = decoder.parameters();
        if (paramMap == Collections.<String, List<String>>emptyMap()) {
            paramMap = new HashMap<>();
        }
    }
}
