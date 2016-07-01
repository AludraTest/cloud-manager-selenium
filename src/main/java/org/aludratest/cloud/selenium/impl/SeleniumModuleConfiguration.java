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

import org.aludratest.cloud.config.ConfigUtil;
import org.aludratest.cloud.config.MutablePreferences;
import org.aludratest.cloud.config.Preferences;
import org.aludratest.cloud.config.SimplePreferences;

public class SeleniumModuleConfiguration {

	private MutablePreferences configuration;

	public SeleniumModuleConfiguration(Preferences configuration) {
		// copy constructor to be indepdendent of configuration changes
		this.configuration = new SimplePreferences(null);
		ConfigUtil.copyPreferences(configuration, this.configuration);
	}

	public int getSeleniumProxyPort() {
		return configuration.getIntValue("port", 5007);
	}

	public int getHealthCheckIntervalSeconds() {
		return configuration.getIntValue("healthCheckInterval", 15);
	}

	public int getMaxIdleTimeBetweenCommandsSeconds() {
		return configuration.getIntValue("maxIdleTimeBetweenCommands", 60);
	}

	public int getSeleniumTimeoutSeconds() {
		return configuration.getIntValue("seleniumTimeout", 5);
	}

	public int getMaxProxyThreads() {
		return configuration.getIntValue("maxProxyThreads", 150);
	}

	public int getMaxProxyQueueSize() {
		return configuration.getIntValue("maxProxyQueueSize", 300);
	}

	public static void fillDefaults(MutablePreferences preferences) {
		preferences.setValue("port", 5007);
		preferences.setValue("healthCheckInterval", 15);
		preferences.setValue("maxIdleTimeBetweenCommands", 60);
		preferences.setValue("seleniumTimeout", 5);
		preferences.setValue("maxProxyThreads", 150);
		preferences.setValue("maxProxyQueueSize", 300);
	}

}
