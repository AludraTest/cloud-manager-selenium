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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.AbstractSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.aludratest.cloud.app.CloudManagerApp;
import org.aludratest.cloud.app.CloudManagerAppConfig;
import org.aludratest.cloud.resource.ResourceState;
import org.aludratest.cloud.selenium.util.HttpProxy;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.config.SocketConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.eclipse.jetty.client.Address;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationListener;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class SeleniumHttpProxy extends HttpProxy implements HttpResourceProxy {
	
	private static final Logger LOG = LoggerFactory.getLogger(SeleniumHttpProxy.class);

	private int id;

	private SeleniumResourceImpl resource;

	private String seleniumSessionId;

	private String accessUrl;

	private boolean selenium1;

	private long timeout;

	private long maxIdleTime;

	private CloseableHttpClient healthCheckClient;

	private ScheduledExecutorService healthCheckExecutor;

	private ScheduledFuture<?> nextHealthCheck;

	private static final Pattern PATTERN_SESSION_ID_SEL1 = Pattern.compile("(^|&)sessionId=([^&]+)");

	private static final Pattern PATTERN_SESSION_ID_SEL2 = Pattern.compile("/wd/hub/session/([^/]+)/");

	static HttpClient httpClient;

	private static final AtomicInteger httpClientReferences = new AtomicInteger();

	private SeleniumHttpProxy(String schema, String prefix, String host, int port, String path) {
		super(schema, prefix, host, port, path);
	}

	public static SeleniumHttpProxy create(int id, SeleniumResourceImpl resource, String prefix, long timeout, long maxIdleTime,
			String accessUrl, ScheduledExecutorService healthCheckExecutor) {
		URI oUri = URI.create(resource.getOriginalUrl());
		SeleniumHttpProxy proxy = new SeleniumHttpProxy(oUri.getScheme(), prefix, oUri.getHost(), oUri.getPort(), oUri.getPath());
		proxy.id = id;
		proxy.resource = resource;
		proxy.timeout = timeout;
		proxy.accessUrl = accessUrl;
		proxy.maxIdleTime = maxIdleTime;
		proxy.healthCheckClient = createHealthCheckHttpClient();
		proxy.healthCheckExecutor = healthCheckExecutor;

		// set resource to DISCONNECTED first
		resource.setState(ResourceState.DISCONNECTED);
		proxy.nextHealthCheck = healthCheckExecutor.schedule(proxy.checkStatusRunnable, 2000, TimeUnit.MILLISECONDS);

		return proxy;
	}

	@Override
	public void destroy() {
		// NO CALL OF SUPER.DESTROY!! All it does is killing the (commonly used!) HTTP Client.
		int refs = httpClientReferences.decrementAndGet();
		synchronized (this) {
			if (refs == 0) {
				try {
					httpClient.stop();
				}
				catch (Exception e) {
					// ignore
				}
				httpClient = null;
			}
		}

		IOUtils.closeQuietly(healthCheckClient);
	}

	@Override
	protected void customizeContinuation(Continuation continuation) {
		super.customizeContinuation(continuation);
		if (LOG.isTraceEnabled()) {
			continuation.addContinuationListener(new ContinuationListener() {
				@Override
				public void onTimeout(Continuation continuation) {
					String id = (String) continuation.getAttribute("selenium.requestId");
					LOG.trace("Request timeout for " + resource + ", request " + id);
				}

				@Override
				public void onComplete(Continuation continuation) {
					String id = (String) continuation.getAttribute("selenium.requestId");
					LOG.trace("Request complete for " + resource + ", request " + id);
				}
			});
		}
	}

	@Override
	protected void customizeExchange(HttpExchange exchange, HttpServletRequest request) {
		// spy into the content to extract selenium URL, if any
		try {
			InputStream in = exchange.getRequestContentSource();
			if (in != null) {
				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				IOUtils.copy(in, baos);

				String data = new String(baos.toByteArray(), "UTF-8");
				
				// only trigger usage in successful case, because otherwise Proxy Health Check also triggers usage
				if (extractSeleniumSessionId(request, data)) {
					resource.triggerUsage();
					// treat as healthy until error occurs, reset "counter"
					if (nextHealthCheck != null) {
						synchronized (checkStatusRunnable) {
							nextHealthCheck.cancel(true);
							nextHealthCheck = healthCheckExecutor.schedule(checkStatusRunnable, 5, TimeUnit.SECONDS);
						}
					}
				}

				// and put cached bytes into exchange again
				exchange.setRequestContentSource(new ByteArrayInputStream(baos.toByteArray()));
			}
		}
		catch (IOException e) {
			// too bad, will occur anyway
		}

		super.customizeExchange(exchange, request);
	}

	private boolean extractSeleniumSessionId(HttpServletRequest request, String data) {
		// Selenium 1 style
		Matcher m = PATTERN_SESSION_ID_SEL1.matcher(data);
		if (m.find()) {
			seleniumSessionId = m.group(2);
			selenium1 = true;
			return true;
		}

		// Selenium 2 style
		m = PATTERN_SESSION_ID_SEL2.matcher(request.getRequestURI());
		if (m.find()) {
			seleniumSessionId = m.group(1);
			selenium1 = false;
			return true;
		}

		return false;
	}

	@Override
	protected HttpClient createHttpClient(ServletConfig config) throws Exception {
		synchronized (SeleniumHttpProxy.class) {
			if (httpClient != null) {
				httpClientReferences.incrementAndGet();
				return httpClient;
			}
		}

		HttpClient client = createHttpClientInstance();
		client.setConnectorType(HttpClient.CONNECTOR_SELECT_CHANNEL);

		client.setThreadPool(new NamedQueuedThreadPool(250));

		client.setMaxConnectionsPerAddress(100);
		client.setConnectTimeout((int) timeout);
		// 30 minutes IDLE timeout
		client.setIdleTimeout(30 * 60 * 1000l);

		String t = config.getInitParameter("requestHeaderSize");

		if (t != null) {
			client.setRequestHeaderSize(Integer.parseInt(t));
		}

		t = config.getInitParameter("requestBufferSize");

		if (t != null) {
			client.setRequestBufferSize(Integer.parseInt(t));
		}

		t = config.getInitParameter("responseHeaderSize");

		if (t != null) {
			client.setResponseHeaderSize(Integer.parseInt(t));
		}

		t = config.getInitParameter("responseBufferSize");

		if (t != null) {
			client.setResponseBufferSize(Integer.parseInt(t));
		}

		synchronized (SeleniumHttpProxy.class) {
			if (httpClient == null) {
				client.start();
				httpClient = client;

				updateProxyConfig();
			}
			httpClientReferences.incrementAndGet();
			return httpClient;
		}
	}

	@Override
	public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException {
		if (LOG.isTraceEnabled()) {
			// give a random ID for this request
			String id = Integer.toHexString((int) (Math.random() * Integer.MAX_VALUE));
			while (id.length() < 8) {
				id = "0" + id;
			}
			LOG.trace("service() enter for " + resource + ", unique request code " + id);
			req.setAttribute("selenium.requestId", id);
		}

		// wait for update proxy if in progress
		synchronized (SeleniumHttpProxy.class) {
		}

		super.service(req, res);

		if (Boolean.TRUE.equals(req.getAttribute("selenium.connectFailed"))) {
			((HttpServletResponse) res).sendError(HttpServletResponse.SC_GATEWAY_TIMEOUT);
		}
	}

	@Override
	protected void handleOnConnectionFailed(Throwable ex, HttpServletRequest request, HttpServletResponse response) {
		request.setAttribute("selenium.connectFailed", Boolean.TRUE);
		super.handleOnConnectionFailed(ex, request, response);
	}

	@Override
	protected void handleOnException(Throwable ex, HttpServletRequest request, HttpServletResponse response) {
		LOG.warn("Exception in communication with Selenium resource " + resource, ex);
		super.handleOnException(ex, request, response);
	}

	@Override
	protected void handleOnExpire(HttpServletRequest request, HttpServletResponse response) {
		LOG.warn("Timeout when waiting for Selenium response from " + resource);
		super.handleOnExpire(request, response);
	}

	@Override
	public int getId() {
		return id;
	}

	public SeleniumResourceImpl getResource() {
		return resource;
	}

	@Override
	public String getAccessUrl() {
		return accessUrl;
	}

	public String getSeleniumSessionId() {
		return seleniumSessionId;
	}

	public boolean isSelenium1() {
		return selenium1;
	}

	// when port or host name of Proxy Server changes
	void setAccessUrl(String accessUrl) {
		this.accessUrl = accessUrl;
	}

	private static CloseableHttpClient createHealthCheckHttpClient() {
		RequestConfig config = RequestConfig.custom().setConnectTimeout(10000).setSocketTimeout(20000).build();
		SocketConfig soConfig = SocketConfig.custom().setSoTimeout(20000).build();
		return HttpClients.custom().setDefaultRequestConfig(config).setDefaultSocketConfig(soConfig).build();
	}

	static synchronized void updateProxyConfig() {
		if (httpClient == null) {
			return;
		}
		LOG.debug("Updating HttpClient Proxy Configuration");

		boolean oldUseProxy = httpClient.isProxied();
		Address oldProxyAddress = httpClient.getProxy();
		CloudManagerAppConfig basicConfig = CloudManagerApp.getInstance().getBasicConfiguration();
		Address newProxyAddress = basicConfig.isUseProxy() && basicConfig.getProxyHost() != null ? new Address(
				basicConfig.getProxyHost(), basicConfig.getProxyPort()) : null;

		if (basicConfig.isUseProxy() != oldUseProxy || oldProxyAddress == null ? newProxyAddress != null : !oldProxyAddress
				.equals(newProxyAddress)) {
			httpClient.setProxy(newProxyAddress);
		}

		// use a flaw in httpClient design to inject "regexp" logic as a virtual set
		// do this always as we cannot be sure that bypass hasn't changed
		if (httpClient.isProxied()) {
			String regexp = basicConfig.getBypassProxyRegexp();
			if (regexp != null && !"".equals(regexp.trim())) {
				LOG.debug("Updating HttpClient bypass regexp to " + regexp);
				httpClient.setNoProxy(new RegexpSet(regexp));
			}
			else {
				httpClient.setNoProxy(null);
			}
		}

		// clear HttpClient destination cache
		LOG.debug("Clearing HttpClient destination cache");
		Set<Address> destAddresses = new HashSet<Address>(httpClient.getDestinations());
		for (Address a : destAddresses) {
			try {
				HttpDestination dest = httpClient.getDestination(a, false);
				httpClient.removeDestination(dest);
				dest = httpClient.getDestination(a, true);
				httpClient.removeDestination(dest);
			}
			catch (IOException e) {
				// ignore this destination
				continue;
			}
		}
		LOG.debug("HttpClient Proxy Configuration done");
	}

	private void checkState() {
		// first of all, check if the resource is idle for too long. Regain it then.
		if (resource.getState() == ResourceState.IN_USE && resource.getIdleTime() > maxIdleTime) {
			// try to safely shutdown Selenium session
			LOG.info("Detected IDLE IN_USE resource (" + resource.getOriginalUrl() + "), trying to regain it.");
			resource.tryKillSession();
			resource.stopUsing();
			return;
		}

		// check state via our very own proxy - to get custom timeouts and direct feedback for lost connections.
		InputStream in = null;
		HttpGet request = null;
		CloseableHttpResponse response = null;
		try {
			URL uurl = new URL(accessUrl);
			URL checkUrl = new URL("http://127.0.0.1:" + uurl.getPort() + uurl.getPath() + "/wd/hub/status");
			LOG.debug("Checking health state for " + resource.getOriginalUrl() + " using " + checkUrl.toExternalForm());

			request = new HttpGet(checkUrl.toURI());
			response = healthCheckClient.execute(request);

			if (response.getStatusLine() != null
					&& response.getStatusLine().getStatusCode() == HttpServletResponse.SC_GATEWAY_TIMEOUT) {
				LOG.debug(resource.getOriginalUrl() + " is DISCONNECTED (proxy timeout)");
				resource.setState(ResourceState.DISCONNECTED);
			}
			else if (response.getStatusLine() != null && response.getStatusLine().getStatusCode() != HttpServletResponse.SC_OK) {
				LOG.debug(resource.getOriginalUrl() + " is DISCONNECTED (invalid HTTP status code "
						+ response.getStatusLine().getStatusCode() + ")");
				resource.setState(ResourceState.DISCONNECTED);
			}
			else if (response.getEntity() != null) {
				in = response.getEntity().getContent();
				ByteArrayOutputStream buf = new ByteArrayOutputStream();
				IOUtils.copy(in, buf);
				String statusStr = new String(buf.toByteArray(), "UTF-8");
				if (statusStr.contains("\"status\":0")) {
					if (resource.getState() == ResourceState.DISCONNECTED || resource.getState() == ResourceState.CONNECTED) {
						resource.setState(ResourceState.READY);
						LOG.debug(resource.getOriginalUrl() + " is READY");
					}
				}
				else {
					LOG.debug(resource.getOriginalUrl() + " is DISCONNECTED (invalid response content: " + statusStr + ")");
					resource.setState(ResourceState.DISCONNECTED);
				}
			}
			else {
				LOG.debug(resource.getOriginalUrl() + " is DISCONNECTED (invalid or no HTTP response)");
				resource.setState(ResourceState.DISCONNECTED);
			}
		}
		catch (IOException e) {
			LOG.debug(resource.getOriginalUrl() + " is DISCONNECTED due to IOException: " + e.getMessage());
			resource.setState(ResourceState.DISCONNECTED);
		}
		catch (URISyntaxException e) {
			LOG.debug(resource.getOriginalUrl() + " is DISCONNECTED due to URISyntaxException: " + e.getMessage());
			resource.setState(ResourceState.DISCONNECTED);
		}
		finally {
			IOUtils.closeQuietly(in);
			IOUtils.closeQuietly(response);
		}
	}

	private static class NamedQueuedThreadPool extends QueuedThreadPool {

		public NamedQueuedThreadPool(int maxThreads) {
			super(maxThreads);
			setName("Selenium Proxy HttpClient");
			setMaxStopTimeMs(5000);
		}
	}

	private Runnable checkStatusRunnable = new Runnable() {

		@Override
		public void run() {
			synchronized (this) {
				nextHealthCheck = null;
			}
			checkState();

			// avoid double and multi executions
			if (nextHealthCheck == null) {
				switch (resource.getState()) {
					case CONNECTED:
					case IN_USE:
					case READY:
						synchronized (this) {
							if (nextHealthCheck == null) {
								// good state, check in 5 seconds again (random add to spread checks over time)
								nextHealthCheck = healthCheckExecutor.schedule(this, (long) (5000 + Math.random() * 500),
										TimeUnit.MILLISECONDS);
							}
						}
						break;
					case DISCONNECTED:
					case ERROR:
						synchronized (this) {
							if (nextHealthCheck == null) {
								// do not expect it to be good that soon. (random add to spread checks over time)
								nextHealthCheck = healthCheckExecutor.schedule(this, (long) (10000 + Math.random() * 500),
										TimeUnit.MILLISECONDS);
							}
						}
						break;
					default:
						break;
				}
			}
		}

	};

	private static class RegexpSet extends AbstractSet<String> {

		private Pattern regexp;

		public RegexpSet(String regexp) {
			this.regexp = Pattern.compile(regexp);
		}

		@Override
		public Iterator<String> iterator() {
			return Collections.<String> emptySet().iterator();
		}

		@Override
		public int size() {
			return 0;
		}

		// contains() is the only method being called by HttpClient
		@Override
		public boolean contains(Object o) {
			if (!(o instanceof String)) {
				return false;
			}
			LOG.debug("Checking host " + o + " against pattern " + regexp);

			return regexp.matcher(o.toString()).matches();
		}
	}

}
