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

import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.aludratest.cloud.request.ResourceRequest;
import org.aludratest.cloud.resource.ResourceType;
import org.aludratest.cloud.selenium.SeleniumResourceType;
import org.aludratest.cloud.user.User;

public final class SeleniumResourceRequest implements ResourceRequest {

	private User requestingUser;

	private int niceLevel;

	private String jobName;

	private Map<String, Object> customAttributes;

	private SeleniumResourceRequest() {
	}

	public static SeleniumResourceRequest fromHttpRequest(HttpServletRequest request, User authenticatedUser)
			throws IllegalArgumentException {
		SeleniumResourceRequest resReq = new SeleniumResourceRequest();
		resReq.requestingUser = authenticatedUser;

		String val = request.getHeader(WebDriverProxyServlet.HEADER_NICE_LEVEL);
		if (val != null) {
			try {
				resReq.niceLevel = Integer.parseInt(val);
				if (resReq.niceLevel < -20 || resReq.niceLevel > 19) {
					throw new IllegalArgumentException("Nice-Level header has invalid value");
				}
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("Nice-Level header has invalid value", e);
			}
		}
		val = request.getHeader(WebDriverProxyServlet.HEADER_JOB_NAME);
		if (val != null) {
			resReq.jobName = val;
		}
		Enumeration<String> attrs = request.getHeaders(WebDriverProxyServlet.HEADER_CUSTOM_ATTRIBUTE);
		if (attrs != null) {
			resReq.customAttributes = new HashMap<>();

			while (attrs.hasMoreElements()) {
				String attr = attrs.nextElement();
				int eqIdx = attr.indexOf('=');
				if (eqIdx == -1) {
					throw new IllegalArgumentException("Invalid Custom-Attribute header");
				}
				resReq.customAttributes.put(attr.substring(0, eqIdx).trim(), attr.substring(eqIdx + 1).trim());
			}
		}

		return resReq;
	}

	@Override
	public User getRequestingUser() {
		return requestingUser;
	}

	@Override
	public ResourceType getResourceType() {
		return SeleniumResourceType.INSTANCE;
	}

	@Override
	public int getNiceLevel() {
		return niceLevel;
	}

	@Override
	public String getJobName() {
		return jobName;
	}

	@Override
	public Map<String, Object> getCustomAttributes() {
		return customAttributes == null ? Collections.emptyMap() : customAttributes;
	}

}
