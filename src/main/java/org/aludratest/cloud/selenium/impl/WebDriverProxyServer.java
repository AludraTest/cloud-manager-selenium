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

import java.util.Arrays;

import org.apache.commons.logging.LogFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class WebDriverProxyServer {

	private Server jettyServer;

	private int maxThreads = 150;

	private WebDriverProxyServlet servlet;

	private int port;

	@Autowired
	public WebDriverProxyServer(WebDriverProxyServlet servlet) {
		this.servlet = servlet;
	}

	public void start(int port) throws Exception {
		if (jettyServer != null) {
			throw new IllegalStateException("WebDriverProxyServer already running");
		}

		this.port = port;

		jettyServer = new Server(new QueuedThreadPool(500));
		ServerConnector connector = new ServerConnector(jettyServer);
		connector.setPort(port);
		jettyServer.addConnector(connector);

		ServletContextHandler servletContextHandler = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
		servletContextHandler.setContextPath("/");

		servletContextHandler.addServlet(new ServletHolder(servlet), "/*");

		jettyServer.setHandler(servletContextHandler);
		jettyServer.start();
	}

	public void stop() throws Exception {
		if (jettyServer != null) {
			jettyServer.stop();
			jettyServer = null;
		}
	}

	public boolean isRunning() {
		// do NOT query jettyServer.isRunning() here, as this means something different
		// - here, we want to avoid calling start() twice.
		return jettyServer != null;
	}

	public int getPort() {
		return port;
	}

	public void moveToPort(int newPort) {
		if (newPort == port) {
			return;
		}

		int oldPort = port;
		ServerConnector newConnector = new ServerConnector(jettyServer);
		newConnector.setPort(newPort);
		try {
			newConnector.start();
		} catch (Exception e) {
			LogFactory.getLog(WebDriverProxyServer.class)
					.error("Could not start Selenium Proxy Server on port " + newPort, e);
			newConnector.close();
			return; // this keeps the previous port active
		}
		jettyServer.addConnector(newConnector);
		Arrays.asList(jettyServer.getConnectors()).stream().filter(c -> c instanceof ServerConnector)
				.map(c -> (ServerConnector) c).filter(s -> s.getPort() == oldPort)
				.forEach(s -> {
					s.close();
					jettyServer.removeConnector(s);
				});
		this.port = newPort;
	}

}
