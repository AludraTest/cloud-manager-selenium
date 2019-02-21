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
import org.aludratest.cloud.config.ConfigManager;
import org.aludratest.cloud.config.Configurable;
import org.aludratest.cloud.config.MainPreferences;
import org.aludratest.cloud.config.MutablePreferences;
import org.aludratest.cloud.config.Preferences;
import org.aludratest.cloud.config.PreferencesListener;
import org.aludratest.cloud.config.admin.AbstractConfigurationAdmin;
import org.aludratest.cloud.config.admin.ConfigurationAdmin;
import org.aludratest.cloud.module.AbstractResourceModule;
import org.aludratest.cloud.resource.writer.ResourceWriterFactory;
import org.aludratest.cloud.resourcegroup.ResourceGroup;
import org.aludratest.cloud.resourcegroup.ResourceGroupManager;
import org.aludratest.cloud.selenium.SeleniumResourceType;
import org.aludratest.cloud.selenium.config.SeleniumResourceModuleConfigAdmin;
import org.aludratest.cloud.user.admin.UserDatabaseRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Component("org.aludratest.cloud.module.ResourceModule")
@Qualifier("selenium")
public class SeleniumResourceModule extends AbstractResourceModule
		implements Configurable, PreferencesListener {

	private SeleniumResourceWriterFactory writerFactory = new SeleniumResourceWriterFactory();

	private SeleniumModuleConfiguration configuration;

	private MainPreferences preferences;

	private ConfigManager configManager;

	private UserDatabaseRegistry userDatabaseRegistry;

	private WebDriverProxyServer webDriverProxyServer;

	private SeleniumHealthCheckService healthCheckService;

	private ApplicationContext applicationContext;

	@Autowired
	public SeleniumResourceModule(ConfigManager configManager, UserDatabaseRegistry userDatabaseRegistry,
			WebDriverProxyServer webDriverProxyServer,
			SeleniumHealthCheckService healthCheckService, ApplicationContext applicationContext) {
		this.configManager = configManager;
		this.userDatabaseRegistry = userDatabaseRegistry;
		this.webDriverProxyServer = webDriverProxyServer;
		this.healthCheckService = healthCheckService;
		this.applicationContext = applicationContext;
	}

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
		return new SeleniumResourceGroup(configManager, userDatabaseRegistry);
	}

	@Override
	public ResourceWriterFactory getResourceWriterFactory() {
		return writerFactory;
	}

	@Override
	public void fillDefaults(MutablePreferences preferences) {
		SeleniumModuleConfiguration.fillDefaults(preferences);
	}

	@Override
	public void validateConfiguration(Preferences preferences) throws ConfigException {
		internalValidateConfig(preferences);
	}

	@Override
	public void setPreferences(MainPreferences preferences) throws ConfigException {
		if (this.preferences != null) {
			this.preferences.removePreferencesListener(this);
		}
		this.preferences = preferences;
		preferences.addPreferencesListener(this);
		configure(preferences);
	}

	private void configure(MainPreferences preferences) throws ConfigException {
		configuration = new SeleniumModuleConfiguration(preferences);

		if (!webDriverProxyServer.isRunning()) {
			try {
				webDriverProxyServer.start(configuration.getSeleniumProxyPort());
			} catch (Exception e) {
				throw new ConfigException(
						"Could not start proxy server on port " + configuration.getSeleniumProxyPort(), "port", e);
			}
		}
		else if (configuration.getSeleniumProxyPort() != webDriverProxyServer.getPort()) {
			webDriverProxyServer.moveToPort(configuration.getSeleniumProxyPort());
		}

		if (!healthCheckService.isRunning()) {
			// use the application context to resolve the ResourceGroupManager to avoid
			// cyclic dependency
			healthCheckService.start(applicationContext.getBean(ResourceGroupManager.class));
		}

		healthCheckService.setHealthCheckIntervalSeconds(configuration.getHealthCheckIntervalSeconds());
		healthCheckService.setOrphanedTimeoutSeconds(configuration.getMaxIdleTimeBetweenCommandsSeconds());
		healthCheckService.setSeleniumTimeoutSeconds(configuration.getSeleniumTimeoutSeconds());
	}

	public SeleniumModuleConfiguration getConfiguration() {
		return configuration;
	}

	@Override
	public <T extends ConfigurationAdmin> T getAdminInterface(Class<T> ifaceClass) {
		if (ifaceClass == SeleniumResourceModuleConfigAdmin.class) {
			return ifaceClass.cast(new SeleniumResourceModuleConfigAdminImpl(preferences, configManager));
		}
		return null;
	}

	@Override
	public void handleApplicationShutdown() {
		try {
			webDriverProxyServer.stop();
		} catch (Exception e) {

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

	private static void internalValidateConfig(Preferences preferences) throws ConfigException {
		int value = preferences.getIntValue(SeleniumModuleConfiguration.PROP_PROXY_PORT);
		if (value < 1 || value > 65535) {
			throw new ConfigException("Selenium proxy port must be between 1 and 65535",
					SeleniumModuleConfiguration.PROP_PROXY_PORT);
		}

		value = preferences.getIntValue(SeleniumModuleConfiguration.PROP_HEALTH_CHECK_INTERVAL);
		if (value < 3 || value > 600) {
			throw new ConfigException("Health Check Interval must be between 3 and 600 seconds",
					SeleniumModuleConfiguration.PROP_HEALTH_CHECK_INTERVAL);
		}

		value = preferences.getIntValue(SeleniumModuleConfiguration.PROP_MAX_IDLE_TIME);
		if (value < 5 || value > 3600) {
			throw new ConfigException("Max. idle time must be between 5 and 3600 seconds",
					SeleniumModuleConfiguration.PROP_MAX_IDLE_TIME);
		}

		value = preferences.getIntValue(SeleniumModuleConfiguration.PROP_SELENIUM_TIMEOUT);
		if (value < 3 || value > 600) {
			throw new ConfigException("Selenium connect timeout must be between 3 and 600 seconds",
					SeleniumModuleConfiguration.PROP_SELENIUM_TIMEOUT);
		}
	}

	private static class SeleniumResourceModuleConfigAdminImpl extends AbstractConfigurationAdmin
			implements SeleniumResourceModuleConfigAdmin {

		protected SeleniumResourceModuleConfigAdminImpl(MainPreferences mainPreferences, ConfigManager configManager) {
			super(mainPreferences, configManager);
		}

		@Override
		public void setSeleniumProxyPort(int port) {
			getPreferences().setValue(SeleniumModuleConfiguration.PROP_PROXY_PORT, port);
		}

		@Override
		public void setHealthCheckIntervalSeconds(int healthCheckInterval) {
			getPreferences().setValue(SeleniumModuleConfiguration.PROP_HEALTH_CHECK_INTERVAL, healthCheckInterval);
		}

		@Override
		public void setMaxIdleTimeBetweenCommandsSeconds(int maxIdleTime) {
			getPreferences().setValue(SeleniumModuleConfiguration.PROP_MAX_IDLE_TIME, maxIdleTime);
		}

		@Override
		public void setSeleniumTimeoutSeconds(int timeout) {
			getPreferences().setValue(SeleniumModuleConfiguration.PROP_SELENIUM_TIMEOUT, timeout);
		}

		@Override
		protected void validateConfig(Preferences preferences) throws ConfigException {
			internalValidateConfig(preferences);
		}

	}
}
