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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.aludratest.cloud.resource.Resource;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.ExecutorThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base class for proxy servers proxying HTTP requests to custom HttpProxies. A server created by this class serves all
 * requests to <code>/proxyNN</code> URLs and delegates them to the according registered proxy.
 * 
 * @author falbrech
 * 
 * @param <T>
 *            Type of the Proxies managed by this server.
 * @param <R>
 *            Type of the resources accessed via the proxies.
 */
public abstract class HttpResourceProxyServer<T extends HttpResourceProxy, R extends Resource> extends HttpServlet {

	private static final long serialVersionUID = 6018055751276833467L;

	private static final Logger LOG = LoggerFactory.getLogger(HttpResourceProxyServer.class);

	private Map<Integer, T> proxies = new LinkedHashMap<Integer, T>();

	private AtomicInteger nextProxyId = new AtomicInteger(0);

	private Server jettyServer;

	private ServletContextHandler servletContextHandler;

	private volatile ServletConfig servletConfig;

	private String hostName;

	private int port;

	private int maxProxyQueueSize;

	private int maxProxyThreads;

	public HttpResourceProxyServer(String hostName, int port, int maxProxyQueueSize, int maxProxyThreads) {
		this.hostName = hostName;
		this.port = port;
		this.maxProxyQueueSize = maxProxyQueueSize;
		this.maxProxyThreads = maxProxyThreads;
		createJetty(port);
	}

	private void createJetty(int port) {
		jettyServer = new Server(port);

		createThreadPool();

		servletContextHandler = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
		servletContextHandler.setContextPath("/");

		servletContextHandler.addServlet(new ServletHolder(this), "/*");

		jettyServer.setHandler(servletContextHandler);
	}

	private void createThreadPool() {
		LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<Runnable>(maxProxyQueueSize);

		ThreadFactory threadFactory = new ThreadFactory() {

			private AtomicInteger threadNum = new AtomicInteger();

			@Override
			public Thread newThread(Runnable r) {
				Thread thread = new Thread(r);
				thread.setName("Selenium Proxy Server Jetty-" + threadNum.incrementAndGet());
				return thread;
			}
		};

		ThreadPoolExecutor executor = new ThreadPoolExecutor(5, maxProxyThreads, 5, TimeUnit.MINUTES, queue, threadFactory);
		ExecutorThreadPool threadPool = new ExecutorThreadPool(executor);
		jettyServer.setThreadPool(threadPool);
	}

	public int getPort() {
		return port;
	}

	public void start() throws Exception {
		jettyServer.start();
	}

	public boolean isRunning() {
		return jettyServer.isRunning();
	}

	public void restartJetty(int newJettyPort) throws Exception {
		if (jettyServer.isRunning()) {
			jettyServer.stop();
			jettyServer.join();
		}
		createJetty(newJettyPort);
		start();
		updateProxyAccessUrls();
	}

	public void shutdown() throws Exception {
		if (jettyServer.isRunning()) {
			jettyServer.stop();
			jettyServer.join();
		}

		for (T proxy : proxies.values()) {
			proxy.destroy();
		}
		proxies.clear();
	}

	/**
	 * Reconfigures the proxy server's parameters. Does not reflect changes in port value, as this would require a server restart.
	 * Use {@link #restartJetty(int)} for restarting Jetty, which throws e.g. an exception when the port is blocked.
	 * 
	 */
	public void reconfigure(int maxProxyThreads, int maxProxyQueueSize) {
		boolean updateThreadPool = this.maxProxyQueueSize != maxProxyQueueSize || this.maxProxyThreads != maxProxyThreads;

		this.maxProxyQueueSize = maxProxyQueueSize;
		this.maxProxyThreads = maxProxyThreads;

		if (updateThreadPool) {
			createThreadPool();
		}
	}

	protected abstract T findExistingProxy(R resource, Collection<T> existingProxies);

	protected abstract T createProxy(int id, R resource, String path, String accessUrl);

	protected abstract void updateProxyAccessUrl(T proxy, String newAccessUrl);

	protected final synchronized List<T> getAllProxies() {
		return new ArrayList<T>(proxies.values());
	}

	public T addProxyForResource(R resource) throws MalformedURLException {
		T proxy = findExistingProxy(resource, getAllProxies());
		if (proxy != null) {
			return proxy;
		}

		int id = nextProxyId.incrementAndGet();
		String path = "/proxy" + id;
		String accessUrl = "http://" + hostName + ":" + port + path;

		proxy = createProxy(id, resource, path, accessUrl);

		synchronized (this) {
			proxies.put(id, proxy);
		}

		if (servletConfig != null) {
			try {
				proxy.init(servletConfig);
			}
			catch (ServletException e) {
				LOG.error("Could not init HTTP Proxy", e);
			}
		}

		return proxy;
	}

	public void removeProxy(T proxy) {
		synchronized (this) {
			if (!proxies.containsValue(proxy)) {
				return;
			}
			proxies.remove(proxy.getId());
		}

		proxy.destroy();
	}

	public void updateHostName(String newHostName) {
		hostName = newHostName;
		updateProxyAccessUrls();
	}

	@Override
	public void init(ServletConfig config) throws ServletException {
		super.init(config);
		this.servletConfig = config;

		for (T proxy : getAllProxies()) {
			proxy.init(config);
		}
	}

	protected final Server getJettyServer() {
		return jettyServer;
	}

	private static final Pattern PATTERN_PROXY_ID = Pattern.compile("/proxy([0-9]+)/.*");

	@Override
	protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		// extract and identify proxy ID; forward to proxy
		String path = req.getPathInfo();
		if (path == null) {
			throw new FileNotFoundException();
		}

		Matcher m = PATTERN_PROXY_ID.matcher(path);
		if (m.matches()) {
			try {
				int id = Integer.parseInt(m.group(1));
				if (proxies.containsKey(id)) {
					proxies.get(id).service(req, resp);
					return;
				}
			}
			catch (ServletException e) {
				throw e;
			}
			catch (IOException e) {
				throw e;
			}
			catch (Throwable t) {
				LOG.warn("Delegating resource proxy threw unknown error", t);
				throw new FileNotFoundException();
			}
		}

		LOG.debug("No proxy found to handle request to " + path);
		throw new FileNotFoundException();
	}

	private void updateProxyAccessUrls() {
		for (T proxy : getAllProxies()) {
			String path = "/proxy" + proxy.getId();
			String accessUrl = "http://" + hostName + ":" + port + path;
			updateProxyAccessUrl(proxy, accessUrl);
		}
	}

}
