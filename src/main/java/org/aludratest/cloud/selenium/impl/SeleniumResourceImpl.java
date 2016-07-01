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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.List;

import org.aludratest.cloud.app.CloudManagerApp;
import org.aludratest.cloud.resource.AbstractResource;
import org.aludratest.cloud.resource.ResourceState;
import org.aludratest.cloud.selenium.SeleniumResource;
import org.aludratest.cloud.selenium.SeleniumResourceType;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicNameValuePair;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of a Selenium Resource. The implementation knows the original and the proxy URL for the Selenium resource, and
 * owns a Proxy servlet for proxying the Selenium requests to the original URL. It also tracks last used time of the resource to
 * be able to discover idle resources, and it knows how to kill and regain these idle resources.
 * 
 * @author falbrech
 * 
 */
public class SeleniumResourceImpl extends AbstractResource implements SeleniumResource, Serializable {

	private static final long serialVersionUID = 8442244736399976660L;

	private static final Logger LOG = LoggerFactory.getLogger(SeleniumResourceImpl.class);

	private String originalUrl;

	private transient SeleniumHttpProxy proxy;

	private ResourceState state;

	private long lastUsedTime;

	private boolean maintenanceMode;

	SeleniumResourceImpl(String originalUrl) throws MalformedURLException {
		this.originalUrl = originalUrl;
		if (originalUrl.endsWith("/")) {
			this.originalUrl = originalUrl.substring(0, originalUrl.length() - 1);
		}
		state = ResourceState.DISCONNECTED;
	}

	@Override
	public SeleniumResourceType getResourceType() {
		return SeleniumResourceType.INSTANCE;
	}

	@Override
	public synchronized ResourceState getState() {
		return state;
	}

	@Override
	public String getSeleniumUrl() {
		return getProxy().getAccessUrl();
	}

	@Override
	public String toString() {
		return "Selenium @ " + originalUrl;
	}

	@Override
	public void startUsing() {
		triggerUsage();
		setState(ResourceState.IN_USE);
	}

	@Override
	public void stopUsing() {
		if (state == ResourceState.IN_USE) {
			setState(maintenanceMode ? ResourceState.CONNECTED : ResourceState.READY);
		}
	}

	@Override
	public void switchToMaintenanceMode(boolean maintenanceMode) {
		this.maintenanceMode = maintenanceMode;
		if (!maintenanceMode) {
			if (getState() == ResourceState.CONNECTED) {
				setState(ResourceState.READY);
			}
		}
		else {
			if (getState() == ResourceState.READY) {
				setState(ResourceState.CONNECTED);
			}
		}
	}

	@Override
	public void forceCloseAllSessions() {
		LOG.info("Force closing all Selenium 2 sessions on " + originalUrl);
		List<String> sessionIds;
		try {
			sessionIds = getSelenium2SessionIds(originalUrl);
		}
		catch (IOException e) {
			return;
		}
		for (String sessionId : sessionIds) {
			try {
				closeSelenium2Session(originalUrl, sessionId);
			}
			catch (Exception e) {
				// ignore; possibly already closed
			}
		}
	}

	@Override
	public boolean isInMaintenanceMode() {
		return maintenanceMode;
	}

	SeleniumHttpProxy getProxy() {
		if (proxy == null) {
			SeleniumResourceModule module = (SeleniumResourceModule) CloudManagerApp.getInstance().getResourceModule(
					SeleniumResourceType.INSTANCE);
			try {
				proxy = module.getProxyServer().addProxyForResource(this);
			}
			catch (MalformedURLException e) {
				LOG.error("Could not create Selenium proxy due to invalid URL", e);
			}

		}
		return proxy;
	}

	@Override
	public String getOriginalUrl() {
		return originalUrl;
	}

	void setState(ResourceState state) {
		if (state == ResourceState.READY && maintenanceMode) {
			state = ResourceState.CONNECTED;
		}

		ResourceState oldState;
		synchronized (this) {
			oldState = this.state;
			if (oldState == state) {
				return;
			}
			this.state = state;
		}
		fireResourceStateChanged(oldState, state);
	}

	void tryKillSession() {
		SeleniumHttpProxy proxy = getProxy();
		if (proxy.getSeleniumSessionId() == null) {
			return;
		}

		// simple close for Selenium1
		if (proxy.isSelenium1()) {
			closeSelenium1Session(originalUrl + "/selenium-server/driver/", proxy.getSeleniumSessionId());
		}
		else {
			closeSelenium2Session(originalUrl, proxy.getSeleniumSessionId());
		}
	}

	private static void closeSelenium1Session(String url, String sessionId) {
		List<NameValuePair> urlParameters = new ArrayList<NameValuePair>();
		urlParameters.add(new BasicNameValuePair("cmd", "testComplete"));
		urlParameters.add(new BasicNameValuePair("sessionId", sessionId));

		CloseableHttpClient client = HttpClientBuilder.create().build();

		try {
			HttpPost request = new HttpPost(url);
			request.setEntity(new UrlEncodedFormEntity(urlParameters));
			client.execute(request);
		}
		catch (IOException e) {
			// ignore silently
			LOG.debug("Could not execute a POST on url " + url, e);
		}
		finally {
			IOUtils.closeQuietly(client);
		}
	}

	private static void closeSelenium2Session(String originalUrl, String sessionId) {
		CloseableHttpClient client = HttpClientBuilder.create().build();

		String baseUrl = originalUrl + "/wd/hub/session/" + sessionId;

		try {
			HttpGet getMethod = new HttpGet(baseUrl + "/window_handles");
			HttpResponse response = client.execute(getMethod);
			JSONObject result = extractJSONObject(response);
			if (result != null) {
				// close all of these windows
				String url = baseUrl + "/window";

				JSONArray array = result.getJSONArray("value");
				for (int i = 0; i < array.length(); i++) {
					// activate this window, and close it
					JSONObject obj = new JSONObject();
					obj.put("name", array.getString(i));
					performPost(url, obj.toString());
					performDelete(url);
				}
			}

			// now, delete the session
			performDelete(baseUrl);
		}
		catch (IOException e) {
			// ignore silently
		}
		catch (JSONException e) {
			// ignore silently
		}
		finally {
			IOUtils.closeQuietly(client);
		}
	}

	private static List<String> getSelenium2SessionIds(String originalUrl) throws IOException {
		CloseableHttpClient client = HttpClientBuilder.create().build();

		String baseUrl = originalUrl + "/wd/hub/sessions";

		List<String> result = new ArrayList<String>();

		try {
			HttpGet getMethod = new HttpGet(baseUrl);
			HttpResponse response = client.execute(getMethod);
			JSONObject sessions = extractJSONObject(response);
			if (sessions != null) {
				JSONArray array = sessions.getJSONArray("value");
				for (int i = 0; i < array.length(); i++) {
					JSONObject obj = array.getJSONObject(i);
					result.add(obj.getString("id"));
				}
			}
		}
		catch (IOException e) {
			// ignore silently
		}
		catch (JSONException e) {
			// ignore silently
		}
		finally {
			IOUtils.closeQuietly(client);
		}

		return result;
	}

	private static JSONObject extractJSONObject(HttpResponse response) throws IOException {
		if (response.getStatusLine() != null && response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
			throw new IOException("HTTP error code from Selenium");
		}
		HttpEntity entity = response.getEntity();

		if (entity == null || entity.getContentLength() == 0) {
			return null; // no response
		}

		InputStream in = entity.getContent();
		if (in == null) {
			return null;
		}
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			IOUtils.copy(in, baos);
			JSONObject object = new JSONObject(new String(baos.toByteArray(), "UTF-8"));
			// check that state is success
			if (!"success".equals(object.getString("state"))) {
				return null;
			}
			return object;
		}
		catch (JSONException e) {
			throw new IOException("Invalid JSON", e);
		}
		finally {
			IOUtils.closeQuietly(in);
		}
	}

	private static void performPost(String url, String data) {
		CloseableHttpClient client = HttpClientBuilder.create().build();

		try {
			HttpPost request = new HttpPost(url);
			request.setEntity(new StringEntity(data, ContentType.DEFAULT_BINARY));
			client.execute(request);
		}
		catch (IOException e) {
			// ignore silently
			LOG.debug("Could not execute a POST on url " + url, e);
		}
		finally {
			IOUtils.closeQuietly(client);
		}
	}

	private static void performDelete(String url) {
		CloseableHttpClient client = HttpClientBuilder.create().build();

		try {
			HttpDelete request = new HttpDelete(url);
			client.execute(request);
		}
		catch (IOException e) {
			// ignore silently
			LOG.debug("Could not execute a DELETE on url " + url, e);
		}
		finally {
			IOUtils.closeQuietly(client);
		}
	}

	synchronized void triggerUsage() {
		lastUsedTime = System.currentTimeMillis();
	}

	synchronized long getIdleTime() {
		return System.currentTimeMillis() - lastUsedTime;
	}

	public void removeProxy() {
		if (proxy != null) {
			LOG.debug("Removing proxy for resource " + this);
			SeleniumResourceModule module = (SeleniumResourceModule) CloudManagerApp.getInstance().getResourceModule(
					SeleniumResourceType.INSTANCE);
			module.getProxyServer().removeProxy(proxy);
			proxy = null;
		}
	}

	@Override
	public boolean equals(Object obj) {
		if (obj == null) {
			return false;
		}
		if (obj == this) {
			return true;
		}
		if (obj.getClass() != getClass()) {
			return false;
		}

		SeleniumResourceImpl res = (SeleniumResourceImpl) obj;
		return res.originalUrl != null && res.originalUrl.equals(originalUrl);
	}

	@Override
	public int hashCode() {
		return originalUrl.hashCode();
	}

}
