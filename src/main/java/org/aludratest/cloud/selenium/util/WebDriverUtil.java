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
package org.aludratest.cloud.selenium.util;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.json.JSONObject;

public final class WebDriverUtil {

	private WebDriverUtil() {
	}

	public static void sendWebDriverError(HttpServletResponse response, int httpCode, String errorCode,
			String errorMessage, Throwable throwable) throws IOException {
		sendWebDriverError(response, httpCode, errorCode, errorMessage, throwable, Collections.emptyMap());
	}

	public static void sendWebDriverError(HttpServletResponse response, int httpCode, String errorCode,
			String errorMessage, Throwable throwable, Map<String, String> additionalHeaders) throws IOException {
		additionalHeaders.entrySet().stream().forEach(e -> response.setHeader(e.getKey(), e.getValue()));

		// Build WebDriver error object
		JSONObject value = new JSONObject();
		value.put("error", errorCode);
		value.put("message", errorMessage == null ? "" : errorMessage);

		if (throwable != null) {
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			throwable.printStackTrace(pw);
			pw.close();
			value.put("stacktrace", sw.toString());
		}

		JSONObject result = new JSONObject();
		result.put("value", value);

		sendJsonObject(response, result, httpCode);
	}

	public static void sendJsonObject(HttpServletResponse resp, JSONObject obj, int httpCode) throws IOException {
		resp.setHeader("Content-Type", "application/json; charset=utf-8");
		resp.setHeader("Cache-Control", "no-cache");

		resp.setStatus(httpCode);

		OutputStream os = resp.getOutputStream();
		os.write(obj.toString().getBytes("UTF-8"));
	}
}
