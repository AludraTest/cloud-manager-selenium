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
import java.net.MalformedURLException;
import java.net.ProxySelector;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.http.HttpServletResponse;

import org.aludratest.cloud.app.CloudManagerAppConfig;
import org.aludratest.cloud.resource.Resource;
import org.aludratest.cloud.resource.ResourceCollectionListener;
import org.aludratest.cloud.resource.ResourceState;
import org.aludratest.cloud.resourcegroup.ResourceGroup;
import org.aludratest.cloud.resourcegroup.ResourceGroupManager;
import org.aludratest.cloud.resourcegroup.ResourceGroupManagerListener;
import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.Configurable;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.config.SocketConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.SystemDefaultRoutePlanner;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Service for checking given Selenium remotes regularly for their availability
 * status. Also, this service offers the possibility to set Selenium resources
 * to orphaned once they are not used for the time configured in the Selenium
 * Resource Module settings.
 *
 * @author falbrech
 *
 */
@Component
public final class SeleniumHealthCheckService
		implements ResourceGroupManagerListener, SeleniumResourceGroupListener, ResourceCollectionListener {

	private static final int SOCKET_TIMEOUT = (int) TimeUnit.SECONDS.toMillis(20);

	private static final Log LOG = LogFactory.getLog(SeleniumHealthCheckService.class);

	private CloudManagerAppConfig applicationConfig;

	private int seleniumTimeoutSeconds = 20;

	private Map<ManagedRemoteSelenium, HealthCheckRunnable> checkRunnables = new HashMap<>();

	private volatile ScheduledExecutorService checkExecutorService = Executors.newScheduledThreadPool(1);

	private CloseableHttpClient httpClient;

	private AtomicInteger runningChecks = new AtomicInteger();

	// TODO put into const interface (also in SMC)
	private int healthCheckIntervalSeconds = 15;

	private ResourceGroupManager groupManager;

	private OrphanedCheckService orphanedCheckService;

	@Autowired
	public SeleniumHealthCheckService(CloudManagerAppConfig applicationConfig,
			OrphanedCheckService orphanedCheckService) {
		this.applicationConfig = applicationConfig;
		this.orphanedCheckService = orphanedCheckService;
	}

	public void start(ResourceGroupManager groupManager) {
		this.groupManager = groupManager;
		groupManager.addResourceGroupManagerListener(this);
		for (int groupId : groupManager.getAllResourceGroupIds()) {
			attachToResourceGroup(groupManager.getResourceGroup(groupId));
		}
	}

	public boolean isRunning() {
		return groupManager != null;
	}

	public void shutdown() {
		checkExecutorService.shutdown();
		if (groupManager != null) {
			for (int groupId : groupManager.getAllResourceGroupIds()) {
				detachFromResourceGroup(groupManager.getResourceGroup(groupId));
			}
			groupManager.removeResourceGroupManagerListener(this);
		}
		groupManager = null;
		try {
			httpClient.close();
		} catch (IOException e) {
			LOG.warn("Exception when closing HTTP Client", e);
		}
	}

	public void setHealthCheckIntervalSeconds(int healthCheckIntervalSeconds) {
		this.healthCheckIntervalSeconds = healthCheckIntervalSeconds;
	}

	public void setSeleniumTimeoutSeconds(int seleniumTimeoutSeconds) {
		this.seleniumTimeoutSeconds = seleniumTimeoutSeconds;
	}

	public void setOrphanedTimeoutSeconds(int maxIdleTimeBetweenCommandsSeconds) {
		orphanedCheckService.setOrphanedTimeoutSeconds(maxIdleTimeBetweenCommandsSeconds);
	}

	public Future<?> directSchedule(Runnable runnable, long delay, TimeUnit unit) {
		return checkExecutorService.schedule(runnable, delay, unit);
	}

	@Override
	public void resourceGroupAdded(ResourceGroup group) {
		attachToResourceGroup(group);
	}

	@Override
	public void resourceGroupRemoved(ResourceGroup group) {
		detachFromResourceGroup(group);
	}

	private void attachToResourceGroup(ResourceGroup group) {
		if (group instanceof SeleniumResourceGroup) {
			((SeleniumResourceGroup) group).addSeleniumResourceGroupListener(this);
			((SeleniumResourceGroup) group).getRemoteSeleniums().forEach(r -> activateHealthCheck(r));

			group.getResourceCollection()
					.forEach(rsh -> orphanedCheckService.registerResource((SeleniumResourceImpl) rsh));
		}
	}

	private void detachFromResourceGroup(ResourceGroup group) {
		if (group instanceof SeleniumResourceGroup) {
			((SeleniumResourceGroup) group).removeSeleniumResourceGroupListener(this);
			((SeleniumResourceGroup) group).getRemoteSeleniums().forEach(r -> deactivateHealthCheck(r));

			group.getResourceCollection()
					.forEach(rsh -> orphanedCheckService.unregisterResource((SeleniumResourceImpl) rsh));
		}
	}

	@Override
	public void remoteSeleniumAdded(ManagedRemoteSelenium remoteSelenium) {
		activateHealthCheck(remoteSelenium);
	}

	@Override
	public void remoteSeleniumRemoved(ManagedRemoteSelenium remoteSelenium) {
		deactivateHealthCheck(remoteSelenium);
	}

	@Override
	public void resourceAdded(Resource resource) {
		if (resource instanceof SeleniumResourceImpl) {
			orphanedCheckService.registerResource((SeleniumResourceImpl) resource);
		}
	}

	@Override
	public void resourceRemoved(Resource resource) {
		orphanedCheckService.unregisterResource((SeleniumResourceImpl) resource);
	}

	private void activateHealthCheck(ManagedRemoteSelenium remoteSelenium) {
		synchronized (checkRunnables) {
			if (!checkRunnables.containsKey(remoteSelenium)) {
				LOG.debug("Registering health check for " + remoteSelenium.getSeleniumUrl());
				HealthCheckRunnable checkRunnable = new HealthCheckRunnable(remoteSelenium);
				// schedule faster for initial check
				checkRunnable.schedule((int) (Math.random() * 5));
				checkRunnables.put(remoteSelenium, checkRunnable);
			}
		}
	}

	private void deactivateHealthCheck(ManagedRemoteSelenium remoteSelenium) {
		synchronized (checkRunnables) {
			HealthCheckRunnable checkRunnable = checkRunnables.get(remoteSelenium);
			if (checkRunnable != null) {
				checkRunnable.checkFuture.cancel(false);
			}
			checkRunnables.remove(remoteSelenium);
		}
	}

	private URL buildCheckUrl(String seleniumUrl) throws MalformedURLException {
		if (!seleniumUrl.endsWith("/")) {
			seleniumUrl += "/";
		}
		return new URL(seleniumUrl + "status");
	}

	public void resourceUsed(SeleniumResourceImpl resource) {
		synchronized (checkRunnables) {
			checkRunnables.entrySet().stream()
					.filter(e -> e.getKey().getSeleniumUrl().equals(resource.getOriginalUrl())).findFirst()
					.ifPresent(e -> e.getValue().resetScheduler());
		}
		orphanedCheckService.triggerUsage(resource);
	}

	private CloseableHttpClient createHealthCheckHttpClient() {
		RequestConfig config = RequestConfig.custom()
				.setConnectTimeout((int) TimeUnit.SECONDS.toMillis(seleniumTimeoutSeconds))
				.setSocketTimeout(SOCKET_TIMEOUT)
				.build();
		SocketConfig soConfig = SocketConfig.custom().setSoTimeout(SOCKET_TIMEOUT).build();
		ProxySelector proxySelector = applicationConfig.buildProxySelector();
		SystemDefaultRoutePlanner routePlanner = new SystemDefaultRoutePlanner(proxySelector);
		return HttpClients.custom().setDefaultRequestConfig(config).setDefaultSocketConfig(soConfig)
				.setRoutePlanner(routePlanner).build();
	}

	private synchronized CloseableHttpClient getHttpClient() {
		if (httpClient == null) {
			httpClient = createHealthCheckHttpClient();
		}
		else if (runningChecks.get() == 0 && (httpClient instanceof Configurable)) {
			RequestConfig config = ((Configurable) httpClient).getConfig();
			if (config.getConnectTimeout() != TimeUnit.SECONDS.toMillis(seleniumTimeoutSeconds)) {
				try {
					httpClient.close();
				} catch (IOException e) {
					LOG.warn("Exception when closing HTTP client", e);
				}
				httpClient = createHealthCheckHttpClient();
			}
		}
		return httpClient;
	}

	private boolean doHealthCheck(ManagedRemoteSelenium remoteSelenium) {
		InputStream in = null;
		HttpGet request = null;
		CloseableHttpResponse response = null;
		CloseableHttpClient httpClient = getHttpClient();
		try {
			runningChecks.incrementAndGet();
			URL checkUrl = buildCheckUrl(remoteSelenium.getSeleniumUrl());
			LOG.debug("hChecking health state for " + remoteSelenium.getSeleniumUrl());

			request = new HttpGet(checkUrl.toURI());
			response = httpClient.execute(request);

			if (response.getStatusLine() != null
					&& response.getStatusLine().getStatusCode() == HttpServletResponse.SC_GATEWAY_TIMEOUT) {
				LOG.debug(remoteSelenium.getSeleniumUrl() + " is DISCONNECTED (proxy timeout)");
				return false;
			}
			else if (response.getStatusLine() != null && response.getStatusLine().getStatusCode() != HttpServletResponse.SC_OK) {
				LOG.debug(remoteSelenium.getSeleniumUrl() + " is DISCONNECTED (invalid HTTP status code "
						+ response.getStatusLine().getStatusCode()
						+ ")");
				return false;
			}
			else if (response.getEntity() != null) {
				in = response.getEntity().getContent();
				ByteArrayOutputStream buf = new ByteArrayOutputStream();
				IOUtils.copy(in, buf);
				String statusStr = new String(buf.toByteArray(), "UTF-8");
				try {
					JSONObject statusObj = new JSONObject(statusStr);
					if (statusObj.has("status") && statusObj.getInt("status") == 0) {
						LOG.debug(remoteSelenium.getSeleniumUrl() + " is HEALTHY");
						return true;
					}
				}
				catch (JSONException e) {
					// fallthrough
				}
				LOG.debug(remoteSelenium.getSeleniumUrl() + " is DISCONNECTED (invalid response content: " + statusStr
						+ ")");
				return false;
			}
			else {
				LOG.debug(remoteSelenium.getSeleniumUrl() + " is DISCONNECTED (invalid or no HTTP response)");
				return false;
			}
		}
		catch (IOException e) {
			if (LOG.isTraceEnabled()) {
				LOG.trace(remoteSelenium.getSeleniumUrl() + " is DISCONNECTED due to IOException: " + e.getMessage());
			} else {
				LOG.debug(remoteSelenium.getSeleniumUrl() + " is DISCONNECTED due to IOException");
			}
			return false;
		}
		catch (URISyntaxException e) {
			LOG.debug(
					remoteSelenium.getSeleniumUrl() + " is DISCONNECTED due to URISyntaxException: " + e.getMessage());
			return false;
		}
		finally {
			IOUtils.closeQuietly(in);
			IOUtils.closeQuietly(response);
			runningChecks.decrementAndGet();
		}

	}

	private class HealthCheckRunnable implements Runnable {

		private ManagedRemoteSelenium remoteSelenium;

		private ScheduledFuture<?> checkFuture;

		public HealthCheckRunnable(ManagedRemoteSelenium remoteSelenium) {
			this.remoteSelenium = remoteSelenium;
		}

		private void schedule(int scheduleSeconds) {
			checkFuture = checkExecutorService.schedule(this, scheduleSeconds, TimeUnit.SECONDS);
		}

		@Override
		public void run() {
			boolean healthy = doHealthCheck(remoteSelenium);
			if (!healthy) {
				// set all affected resources to DISCONNECTED
				remoteSelenium.getResources().forEach(r -> r.setState(ResourceState.DISCONNECTED));

				// wait a little longer than normal
				schedule(healthCheckIntervalSeconds + (int) (Math.random() * 5.0));
			} else {
				remoteSelenium.getResources().stream().filter(r -> r.getState() == ResourceState.DISCONNECTED).forEach(
						r -> r.setState(r.isInMaintenanceMode() ? ResourceState.CONNECTED : ResourceState.READY));
				schedule(healthCheckIntervalSeconds);
			}
		}

		public void resetScheduler() {
			if (checkFuture != null && checkFuture.cancel(false)) {
				schedule(healthCheckIntervalSeconds);
			}
		}
	}

}
