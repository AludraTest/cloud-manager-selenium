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

import org.aludratest.cloud.app.CloudManagerApp;
import org.aludratest.cloud.config.ConfigException;
import org.aludratest.cloud.resource.ResourceStateHolder;
import org.aludratest.cloud.resourcegroup.ResourceGroup;
import org.aludratest.cloud.resourcegroup.ResourceGroupManager;
import org.aludratest.cloud.resourcegroup.StaticResourceGroupAdmin;
import org.aludratest.cloud.selenium.config.ClientEntry;

public final class SeleniumUtil {

	private SeleniumUtil() {
	}

	public static void validateSeleniumResourceNotExisting(String url, StaticResourceGroupAdmin<ClientEntry> localGroupResAdmin,
			Integer localGroupId) throws ConfigException {
		// validate URL
		try {
			new URL(url);
		}
		catch (MalformedURLException e) {
			throw new ConfigException("This is not a valid URL. Please enter a valid HTTP URL.");
		}

		// check that this URL is not registered in THIS group
		if (localGroupResAdmin != null) {
			for (ClientEntry ce : localGroupResAdmin.getConfiguredResources()) {
				if (url.equalsIgnoreCase(ce.getSeleniumUrl())) {
					throw new ConfigException("This URL already exists in this resource group.");
				}
			}
		}

		// check that this resource is not yet registered in ANY group
		ResourceGroupManager manager = CloudManagerApp.getInstance().getResourceGroupManager();
		for (int groupId : manager.getAllResourceGroupIds()) {
			if (localGroupId == null || groupId != localGroupId.intValue()) {
				ResourceGroup group = manager.getResourceGroup(groupId);
				if (group instanceof SeleniumResourceGroup) {
					for (ResourceStateHolder rsh : group.getResourceCollection()) {
						SeleniumResourceImpl res = (SeleniumResourceImpl) rsh;
						if (url.equals(res.getOriginalUrl())) {
							throw new ConfigException("This URL already exists in another Selenium resource group.");
						}
					}
				}
			}
		}

	}

}
