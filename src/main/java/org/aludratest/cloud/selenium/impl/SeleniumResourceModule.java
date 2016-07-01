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

import org.aludratest.cloud.config.ConfigException;
import org.aludratest.cloud.config.Configurable;
import org.aludratest.cloud.config.MainPreferences;
import org.aludratest.cloud.config.MutablePreferences;
import org.aludratest.cloud.config.Preferences;
import org.aludratest.cloud.config.PreferencesListener;
import org.aludratest.cloud.config.admin.ConfigurationAdmin;
import org.aludratest.cloud.module.AbstractResourceModule;
import org.aludratest.cloud.module.ResourceModule;
import org.aludratest.cloud.resource.writer.ResourceWriterFactory;
import org.aludratest.cloud.resourcegroup.ResourceGroup;
import org.aludratest.cloud.selenium.SeleniumResourceType;
import org.codehaus.plexus.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(role = ResourceModule.class, hint = "selenium")
public class SeleniumResourceModule extends AbstractResourceModule implements Configurable, PreferencesListener {

	private static final Logger LOGGER = LoggerFactory.getLogger(SeleniumResourceModule.class);

	private SeleniumResourceWriterFactory writerFactory = new SeleniumResourceWriterFactory();
	
	private SeleniumProxyServer proxyServer;

	private SeleniumModuleConfiguration configuration;

	private MainPreferences preferences;

	private String hostName = "localhost";

	@Override
	public SeleniumResourceType getResourceType() {
		return SeleniumResourceType.INSTANCE;
	}

	@Override
	public String getDisplayName() {
		return "Selenium Clients";
	}

	@Override
	public ResourceGroup createResourceGroup() {
		return new SeleniumResourceGroup();
	}

	@Override
	public ResourceWriterFactory getResourceWriterFactory() {
		return writerFactory;
	}

	public SeleniumProxyServer getProxyServer() {
		return proxyServer;
	}

	@Override
	public void fillDefaults(MutablePreferences preferences) {
		SeleniumModuleConfiguration.fillDefaults(preferences);
	}

	@Override
	public void validateConfiguration(Preferences preferences) throws ConfigException {
		int maxThreadSize = preferences.getIntValue("maxProxyThreads", 150);
		if (maxThreadSize < 5) {
			throw new ConfigException("Max Thread Count for Selenium Proxy Server must be greater than 5.");
		}

		// TODO Auto-generated method stub
	}

	@Override
	public void setPreferences(MainPreferences preferences) throws ConfigException {
		if (this.preferences != null) {
			this.preferences.removePreferencesListener(this);
		}
		this.preferences = preferences;
		preferences.addPreferencesListener(this);
		
		// attach a listener for when the host name changes
		if (preferences.getParent() != null && preferences.getParent().getParent() != null) {
			MainPreferences basic = preferences.getParent().getParent().getChildNode("basic");
			if (basic != null) {
				basic.addPreferencesListener(new PreferencesListener() {
					@Override
					public void preferencesChanged(Preferences oldPreferences, MainPreferences newPreferences)
							throws ConfigException {
						String oldHostName = getHostNameFromBasicPreferences(oldPreferences);
						String newHostName = getHostNameFromBasicPreferences(newPreferences);
						if (!oldHostName.equals(newHostName)) {
							handleHostNameChanged(newHostName);
						}

						// also update Proxy configuration of SHP
						SeleniumHttpProxy.updateProxyConfig();
					}

					@Override
					public void preferencesAboutToChange(Preferences oldPreferences, Preferences newPreferences)
							throws ConfigException {
					}
				});
				hostName = getHostNameFromBasicPreferences(basic);
			}
		}


		configure(preferences);
	}

	private String getHostNameFromBasicPreferences(Preferences basicPreferences) {
		return basicPreferences.getStringValue("hostName", "localhost");
	}

	private void handleHostNameChanged(String newHostName) {
		hostName = newHostName;
		if (proxyServer != null) {
			proxyServer.updateHostName(newHostName);
		}

	}

	private void configure(MainPreferences preferences) throws ConfigException {
		configuration = new SeleniumModuleConfiguration(preferences);

		if (proxyServer == null) {
			proxyServer = new SeleniumProxyServer(configuration, hostName);
			try {
				proxyServer.start();
			}
			catch (Exception e) {
				throw new ConfigException("Could not startup Selenium Proxy Server", e);
			}
		}
		else if (proxyServer.getPort() != configuration.getSeleniumProxyPort()) {
			// restart Jetty server, if required
			try {
				proxyServer.restartJetty(configuration.getSeleniumProxyPort());
			}
			catch (Exception e) {
				LOGGER.warn("Exception when restarting Selenium proxy server", e);
			}
		}

		// update configuration
		proxyServer.reconfigure(configuration);
	}

	public void validateNonExistingSeleniumUrl(String url) throws ConfigException {

	}

	@Override
	public <T extends ConfigurationAdmin> T getAdminInterface(Class<T> ifaceClass) {
		// TODO create admin interface for Selenium
		return null;
	}

	@Override
	public void handleApplicationShutdown() {
		// stop proxy server, if any
		if (proxyServer != null) {
			try {
				proxyServer.shutdown();
			}
			catch (Exception e) {
				LOGGER.warn("Exception when shutting down Selenium Proxy Server", e);
			}
		}

		super.handleApplicationShutdown();
	}

	@Override
	public void preferencesAboutToChange(Preferences oldPreferences, Preferences newPreferences) throws ConfigException {
		validateConfiguration(newPreferences);
	}

	@Override
	public void preferencesChanged(Preferences oldPreferences, MainPreferences newPreferences) throws ConfigException {
		configure(newPreferences);
	}

}
