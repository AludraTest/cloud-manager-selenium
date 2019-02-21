/*
 * Copyright (C) 2010-2015 AludraTest.org and the contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.aludratest.cloud.selenium.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.aludratest.cloud.app.CloudManagerAppConfig;
import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpOptions;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpTrace;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.impl.conn.SystemDefaultRoutePlanner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

@Component
public class SeleniumCommandForwarder {

	private static final String[] FORWARD_HEADERS = { "Content-Type" };

	private CloudManagerAppConfig applicationConfig;

	private CloseableHttpClient httpClient;

	@Autowired
	public SeleniumCommandForwarder(CloudManagerAppConfig applicationConfig) {
		this.applicationConfig = applicationConfig;
		this.httpClient = createHttpClient();
	}

	public void forwardTo(HttpServletRequest request, HttpServletResponse response,
			SeleniumResourceImpl resource, HttpCommunicationsListener listener) throws IOException, URISyntaxException {
		StringBuilder sbUri = new StringBuilder(resource.getOriginalUrl());
		if (sbUri.charAt(sbUri.length() - 1) == '/') {
			sbUri.delete(sbUri.length() - 1, 1);
		}
		sbUri.append(request.getRequestURI());
		HttpUriRequest httpRequest = createHttpUriRequest(HttpMethod.resolve(request.getMethod()),
				new URI(sbUri.toString()));

		copyHeaders(request, httpRequest);
		byte[] cachedData = copyBody(request, httpRequest);
		if (listener != null) {
			listener.requestAboutToBeSent(httpRequest, cachedData);
		}

		try (CloseableHttpResponse httpResponse = httpClient.execute(httpRequest)) {
			// cache data, if present
			if (httpResponse.getEntity() != null) {
				try (InputStream in = httpResponse.getEntity().getContent()) {
					cachedData = IOUtils.toByteArray(in);
					if (listener != null) {
						listener.responseComplete(httpResponse, cachedData);
					}
				}
			}

			copyHeaders(httpResponse, response);
			response.setStatus(httpResponse.getStatusLine().getStatusCode());
			OutputStream out = response.getOutputStream();
			out.write(cachedData);
			out.close();
		}
	}

	/**
	 * Directly executes given Selenium command. Useful for internal operations,
	 * e.g. auto-delete session after a timeout.
	 *
	 * @param resource
	 *            Resource to send command to.
	 * @param method
	 *            HTTP method to use, e.g. <code>DELETE</code>.
	 * @param uri
	 *            URI to invoke, e.g. <code>/session/12345</code>.
	 * @return The HTTP response which <b>must be closed by the caller</b>.
	 * @throws IOException
	 *             If communication fails.
	 * @throws URISyntaxException
	 *             If the URI is invalid.
	 */
	public CloseableHttpResponse execute(SeleniumResourceImpl resource, String method, String uri)
			throws IOException, URISyntaxException {
		StringBuilder sbUri = new StringBuilder(resource.getOriginalUrl());
		if (sbUri.charAt(sbUri.length() - 1) == '/') {
			sbUri.delete(sbUri.length() - 1, 1);
		}
		sbUri.append(uri);
		HttpUriRequest httpRequest = createHttpUriRequest(HttpMethod.resolve(method), new URI(sbUri.toString()));

		return httpClient.execute(httpRequest);
	}

	private void copyHeaders(HttpServletRequest request, HttpUriRequest httpRequest) {
		for (String headerName : FORWARD_HEADERS) {
			String headerValue = request.getHeader(headerName);
			if (headerValue != null) {
				httpRequest.setHeader(headerName, headerValue);
			}
		}
	}

	private void copyHeaders(HttpResponse httpResponse, HttpServletResponse response) {
		for (String headerName : FORWARD_HEADERS) {
			Header header = httpResponse.getFirstHeader(headerName);
			if (header != null) {
				response.setHeader(header.getName(), header.getValue());
			}
		}
	}

	private byte[] copyBody(HttpServletRequest request, HttpUriRequest httpRequest) throws IOException {
		InputStream in = request.getInputStream();
		if (in == null) {
			return null;
		}
		byte[] data = IOUtils.toByteArray(in);
		in.close();

		if (data.length > 0 && (httpRequest instanceof HttpEntityEnclosingRequest)) {
			((HttpEntityEnclosingRequest) httpRequest).setEntity(new ByteArrayEntity(data));
		}

		return data;
	}

	private CloseableHttpClient createHttpClient() {
		ProxySelector proxySelector = applicationConfig.buildProxySelector();
		SystemDefaultRoutePlanner routePlanner = new SystemDefaultRoutePlanner(proxySelector);

		PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
		cm.setMaxTotal(4000);
		cm.setDefaultMaxPerRoute(50);

		httpClient = HttpClientBuilder.create().setConnectionManager(cm).setRoutePlanner(routePlanner).build();
		return httpClient;
	}

	private HttpUriRequest createHttpUriRequest(HttpMethod httpMethod, URI uri) {
		switch (httpMethod) {
		case GET:
			return new HttpGet(uri);
		case HEAD:
			return new HttpHead(uri);
		case POST:
			return new HttpPost(uri);
		case PUT:
			return new HttpPut(uri);
		case PATCH:
			return new HttpPatch(uri);
		case DELETE:
			return new HttpDelete(uri);
		case OPTIONS:
			return new HttpOptions(uri);
		case TRACE:
			return new HttpTrace(uri);
		default:
			throw new IllegalArgumentException("Invalid HTTP method: " + httpMethod);
		}
	}
}
