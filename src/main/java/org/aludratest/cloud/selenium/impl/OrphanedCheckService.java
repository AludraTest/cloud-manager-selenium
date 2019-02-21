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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.aludratest.cloud.resource.Resource;
import org.aludratest.cloud.resource.ResourceListener;
import org.aludratest.cloud.resource.ResourceState;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.stereotype.Component;

@Component
public final class OrphanedCheckService implements ResourceListener {

	private static final Log LOG = LogFactory.getLog(OrphanedCheckService.class);

	private ScheduledExecutorService orphanedExecutor = Executors.newScheduledThreadPool(1, new ThreadFactory() {
		@Override
		public Thread newThread(Runnable r) {
			return new Thread(r, "Orphaned Check Executor");
		}
	});

	private Map<SeleniumResourceImpl, Future<?>> resourceFutures = new ConcurrentHashMap<>();

	private int orphanedTimeoutSeconds = SeleniumModuleConfiguration.DEFAULT_MAX_IDLE_TIME;

	public void setOrphanedTimeoutSeconds(int orphanedTimeoutSeconds) {
		this.orphanedTimeoutSeconds = orphanedTimeoutSeconds;
	}

	public void shutdown() {
		LOG.debug("Shutting down OrphanedCheckService");
		orphanedExecutor.shutdown();
	}

	public void registerResource(SeleniumResourceImpl resource) {
		resource.addResourceListener(this);
	}

	public void unregisterResource(SeleniumResourceImpl resource) {
		stopMonitorResource(resource);
		resource.removeResourceListener(this);
	}

	@Override
	public void resourceStateChanged(Resource resource, ResourceState previousState, ResourceState newState) {
		if (previousState != ResourceState.IN_USE && newState == ResourceState.IN_USE) {
			LOG.debug("Resource usage has started, monitoring now resource " + resource);
			monitorResource((SeleniumResourceImpl) resource);
		} else if (previousState == ResourceState.IN_USE && newState != ResourceState.IN_USE) {
			LOG.debug("Resource usage has ended, no longer monitoring resource " + resource);
			stopMonitorResource((SeleniumResourceImpl) resource);
		}
	}

	public void triggerUsage(SeleniumResourceImpl resource) {
		if (resourceFutures.containsKey(resource)) {
			stopMonitorResource(resource);
			monitorResource(resource);
		}
	}

	private void monitorResource(SeleniumResourceImpl resource) {
		resourceFutures.put(resource,
				orphanedExecutor.schedule(() -> resourceOrphaned(resource), orphanedTimeoutSeconds, TimeUnit.SECONDS));
	}

	private void stopMonitorResource(SeleniumResourceImpl resource) {
		Future<?> future = resourceFutures.get(resource);
		if (future != null) {
			future.cancel(false);
		}
		resourceFutures.remove(resource);
	}

	private void resourceOrphaned(SeleniumResourceImpl resource) {
		LOG.debug("Detected resource as orphaned: " + resource);
		resourceFutures.remove(resource);
		resource.fireOrphaned();
	}
}
