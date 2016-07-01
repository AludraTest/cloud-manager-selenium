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
package org.aludratest.cloud.selenium.util;

import java.net.MalformedURLException;
import java.net.URI;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;

import org.eclipse.jetty.servlets.ProxyServlet;

/**
 * A transparent proxy which notifies waiting threads on init.
 * 
 * @author falbrech
 * 
 */
public class HttpProxy extends ProxyServlet.Transparent {

	public HttpProxy(String schema, String prefix, String host, int port, String path) {
		super(prefix, schema, host, port, path);
	}

	@Override
	public synchronized void init(ServletConfig config) throws ServletException {
		super.init(config);
		notifyAll();
	}

	public static HttpProxy create(String originalUrl) throws MalformedURLException {
		return create(originalUrl, "/");
	}

	public static HttpProxy create(String originalUrl, String prefix) throws MalformedURLException {
		URI oUri = URI.create(originalUrl);
		return new HttpProxy(oUri.getScheme(), prefix, oUri.getHost(), oUri.getPort(), oUri.getPath());
	}

}
