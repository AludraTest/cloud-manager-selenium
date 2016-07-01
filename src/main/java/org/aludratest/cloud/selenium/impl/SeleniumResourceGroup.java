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

import java.net.MalformedURLException;
import java.net.URL;

import org.aludratest.cloud.config.ConfigException;
import org.aludratest.cloud.config.MainPreferences;
import org.aludratest.cloud.config.Preferences;
import org.aludratest.cloud.resourcegroup.AbstractStaticResourceGroup;
import org.aludratest.cloud.resourcegroup.AbstractStaticResourceGroupConfigurationAdmin;
import org.aludratest.cloud.resourcegroup.StaticResourceGroupAdmin;
import org.aludratest.cloud.selenium.SeleniumResource;
import org.aludratest.cloud.selenium.SeleniumResourceType;
import org.aludratest.cloud.selenium.config.ClientEntry;

public class SeleniumResourceGroup extends AbstractStaticResourceGroup<SeleniumResource> {

	public SeleniumResourceGroup() {
		super(SeleniumResourceType.INSTANCE);
	}

	@Override
	protected SeleniumResource createResourceFromPreferences(Preferences resourceConfig) throws ConfigException {
		String originalUrl = resourceConfig.getStringValue("seleniumUrl");
		if (originalUrl == null) {
			throw new ConfigException("Selenium RC Client URL must be specified", "seleniumUrl");
		}

		try {
			new URL(originalUrl);
			return new SeleniumResourceImpl(originalUrl);
		}
		catch (MalformedURLException e) {
			throw new ConfigException("Selenium RC Client URL is not a valid URL", "seleniumUrl");
		}
	}

	@Override
	protected void validateResourceConfig(Preferences resourceConfig) throws ConfigException {
		String originalUrl = resourceConfig.getStringValue("seleniumUrl");
		if (originalUrl == null) {
			throw new ConfigException("Selenium RC Client URL must be specified", "seleniumUrl");
		}

		try {
			new URL(originalUrl);
		}
		catch (MalformedURLException e) {
			throw new ConfigException("Selenium RC Client URL is not a valid URL", "seleniumUrl");
		}
	}
	
	@Override
	protected StaticResourceGroupAdmin<?> createStaticResourceGroupAdmin(MainPreferences preferences) {
		return new SeleniumResourceGroupConfigurationAdmin(this);
	}

	@Override
	protected void addResource(SeleniumResource resource) {
		super.addResource(resource);

		// force creation and registration of Proxy
		if (resource instanceof SeleniumResourceImpl) {
			((SeleniumResourceImpl) resource).getProxy();
		}
	}

	@Override
	protected void removeResource(SeleniumResource resource) {
		super.removeResource(resource);

		if (resource instanceof SeleniumResourceImpl) {
			((SeleniumResourceImpl) resource).removeProxy();
		}
	}

	private static class SeleniumResourceGroupConfigurationAdmin extends
			AbstractStaticResourceGroupConfigurationAdmin<SeleniumResource, ClientEntry> {

		protected SeleniumResourceGroupConfigurationAdmin(AbstractStaticResourceGroup<SeleniumResource> group) {
			super(group, ClientEntry.class);
		}

		@Override
		protected boolean equals(ClientEntry configuredResource1, ClientEntry configuredResource2) {
			String url = configuredResource1.getSeleniumUrl();
			return url == null ? false : url.equals(configuredResource2.getSeleniumUrl());
		}

		@Override
		protected boolean equals(SeleniumResource existingResource, ClientEntry configuredResource) {
			if (!(existingResource instanceof SeleniumResourceImpl)) {
				return false;
			}

			SeleniumResourceImpl res = (SeleniumResourceImpl) existingResource;
			String url = configuredResource.getSeleniumUrl();

			return url != null && url.equals(res.getOriginalUrl());
		}

	}
}
