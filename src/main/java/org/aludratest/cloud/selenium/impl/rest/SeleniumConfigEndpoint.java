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
package org.aludratest.cloud.selenium.impl.rest;

import org.aludratest.cloud.config.ConfigException;
import org.aludratest.cloud.selenium.config.SeleniumResourceModuleConfigAdmin;
import org.aludratest.cloud.selenium.impl.SeleniumModuleConfiguration;
import org.aludratest.cloud.selenium.impl.SeleniumResourceModule;
import org.aludratest.cloud.web.rest.AbstractRestController;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SeleniumConfigEndpoint extends AbstractRestController {

	SeleniumResourceModule seleniumModule;

	@Autowired
	public SeleniumConfigEndpoint(SeleniumResourceModule seleniumModule) {
		this.seleniumModule = seleniumModule;
	}

	@PreAuthorize("hasAuthority('ROLE_ADMIN')")
	@RequestMapping(value = "/api/selenium/config", method = RequestMethod.GET, produces = JSON_TYPE)
	public ResponseEntity<String> getSeleniumConfig() {
		JSONObject result = new JSONObject();

		SeleniumModuleConfiguration config = seleniumModule.getConfiguration();

		result.put(SeleniumModuleConfiguration.PROP_PROXY_PORT, config.getSeleniumProxyPort());
		result.put(SeleniumModuleConfiguration.PROP_HEALTH_CHECK_INTERVAL, config.getHealthCheckIntervalSeconds());
		result.put(SeleniumModuleConfiguration.PROP_MAX_IDLE_TIME, config.getMaxIdleTimeBetweenCommandsSeconds());
		result.put(SeleniumModuleConfiguration.PROP_SELENIUM_TIMEOUT, config.getSeleniumTimeoutSeconds());

		return wrapResultObject(result);
	}

	@PreAuthorize("hasAuthority('ROLE_ADMIN')")
	@RequestMapping(value = "/api/selenium/config", method = RequestMethod.POST, consumes = JSON_TYPE, produces = JSON_TYPE)
	public ResponseEntity<String> setSeleniumConfig(@RequestBody String newConfigData) {
		JSONObject newConfig;
		try {
			newConfig = new JSONObject(newConfigData);
		} catch (JSONException e) {
			return ResponseEntity.badRequest().build();
		}

		SeleniumResourceModuleConfigAdmin configAdmin = seleniumModule
				.getAdminInterface(SeleniumResourceModuleConfigAdmin.class);

		// use optInt everywhere to use 0 in case of string field
		if (newConfig.has(SeleniumModuleConfiguration.PROP_PROXY_PORT)) {
			configAdmin.setSeleniumProxyPort(newConfig.optInt(SeleniumModuleConfiguration.PROP_PROXY_PORT));
		}
		if (newConfig.has(SeleniumModuleConfiguration.PROP_HEALTH_CHECK_INTERVAL)) {
			configAdmin.setHealthCheckIntervalSeconds(
					newConfig.optInt(SeleniumModuleConfiguration.PROP_HEALTH_CHECK_INTERVAL));
		}
		if (newConfig.has(SeleniumModuleConfiguration.PROP_MAX_IDLE_TIME)) {
			configAdmin.setMaxIdleTimeBetweenCommandsSeconds(
					newConfig.optInt(SeleniumModuleConfiguration.PROP_MAX_IDLE_TIME));
		}
		if (newConfig.has(SeleniumModuleConfiguration.PROP_SELENIUM_TIMEOUT)) {
			configAdmin.setSeleniumTimeoutSeconds(newConfig.optInt(SeleniumModuleConfiguration.PROP_SELENIUM_TIMEOUT));
		}

		try {
			configAdmin.commit();
			return getSeleniumConfig();
		} catch (ConfigException ce) {
			JSONObject error = new JSONObject();
			error.put("error", ce.getMessage());
			error.put("message", ce.getMessage());
			error.put("property", ce.getProperty());
			return ResponseEntity.badRequest().body(error.toString());
		}
	}

}
