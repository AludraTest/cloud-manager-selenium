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
package org.aludratest.cloud.selenium.config;

import org.aludratest.cloud.config.MutablePreferences;
import org.aludratest.cloud.config.admin.AbstractConfigNodeBased;

public class ClientEntry extends AbstractConfigNodeBased {

	public final static String PREFIX = "";

	public ClientEntry(MutablePreferences parentNode, int id) {
		super(parentNode, id);
	}

	public void setSeleniumUrl(String value) {
		getConfigNode().setValue("seleniumUrl", value);
	}

	public String getSeleniumUrl() {
		return getConfigNode().getStringValue("seleniumUrl");
	}

	public void setMaxSessions(int maxSessions) {
		getConfigNode().setValue("maxSessions", maxSessions);
	}

	public int getMaxSessions() {
		return getConfigNode().getIntValue("maxSessions", 1);
	}

	@Override
	protected String getConfigNodeName(int id) {
		return PREFIX + id;
	}

	@Override
	public String toString() {
		return getSeleniumUrl() + " (max. " + getMaxSessions() + " session(s))";
	}

}