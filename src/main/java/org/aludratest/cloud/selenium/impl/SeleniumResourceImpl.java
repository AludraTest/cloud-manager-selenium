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

import org.aludratest.cloud.resource.AbstractUsableResource;
import org.aludratest.cloud.resource.ResourceState;
import org.aludratest.cloud.selenium.SeleniumResource;
import org.aludratest.cloud.selenium.SeleniumResourceType;

/**
 * Implementation of a Selenium Resource. The implementation knows the original and the proxy URL for the Selenium resource, and
 * owns a Proxy servlet for proxying the Selenium requests to the original URL. It also tracks last used time of the resource to
 * be able to discover idle resources, and it knows how to kill and regain these idle resources.
 *
 * @author falbrech
 *
 */
public class SeleniumResourceImpl extends AbstractUsableResource implements SeleniumResource {

	private String proxyUrl;

	private String originalUrl;

	private int sessionNo;

	private ResourceState state;

	private long lastUsedTime;

	private boolean maintenanceMode;

	SeleniumResourceImpl(String originalUrl, int sessionNo) {
		this.originalUrl = originalUrl;
		this.sessionNo = sessionNo;
		if (originalUrl.endsWith("/")) {
			this.originalUrl = originalUrl.substring(0, originalUrl.length() - 1);
		}
		state = ResourceState.DISCONNECTED;
	}

	void setProxyUrl(String proxyUrl) {
		this.proxyUrl = proxyUrl;
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
		return proxyUrl;
	}

	public String getOriginalUrl() {
		return originalUrl;
	}

	@Override
	public String toString() {
		return "Selenium @ " + originalUrl + (sessionNo > 1 ? " #" + sessionNo : "");
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
	public boolean isInMaintenanceMode() {
		return maintenanceMode;
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

	synchronized void triggerUsage() {
		lastUsedTime = System.currentTimeMillis();
	}

	synchronized long getIdleTime() {
		return System.currentTimeMillis() - lastUsedTime;
	}

	void fireOrphaned() {
		fireResourceOrphaned();
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
		return res.originalUrl != null && res.originalUrl.equals(originalUrl) && res.sessionNo == sessionNo;
	}

	@Override
	public int hashCode() {
		return originalUrl.hashCode() + sessionNo;
	}

}
