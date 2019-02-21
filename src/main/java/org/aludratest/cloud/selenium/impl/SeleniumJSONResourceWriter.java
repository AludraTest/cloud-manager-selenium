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

import org.aludratest.cloud.resource.Resource;
import org.aludratest.cloud.resource.writer.JSONResourceWriter;
import org.aludratest.cloud.selenium.SeleniumResource;
import org.json.JSONException;
import org.json.JSONObject;

public class SeleniumJSONResourceWriter implements JSONResourceWriter {

	@Override
	public boolean canWrite(Resource resource) {
		return resource instanceof SeleniumResource;
	}

	@Override
	public JSONObject writeToJSON(Resource resource) throws JSONException {
		if (!(resource instanceof SeleniumResource)) {
			throw new IllegalArgumentException("This writer can only handle Selenium resources, but got resource of type "
					+ resource.getClass().getName());
		}

		SeleniumResource selRes = (SeleniumResource) resource;

		JSONObject result = new JSONObject();
		result.put("url", selRes.getSeleniumUrl());
		result.put("maintenanceMode", selRes.isInMaintenanceMode());

		return result;
	}

}
