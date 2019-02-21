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

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.aludratest.cloud.manager.ManagedResourceRequest;
import org.aludratest.cloud.manager.ResourceManager;
import org.aludratest.cloud.manager.ResourceManagerException;
import org.aludratest.cloud.request.ResourceRequest;
import org.aludratest.cloud.resource.OrphanedListener;
import org.aludratest.cloud.resource.Resource;
import org.aludratest.cloud.resource.ResourceListener;
import org.aludratest.cloud.resource.ResourceState;
import org.aludratest.cloud.selenium.util.GateKeeper;
import org.aludratest.cloud.selenium.util.WebDriverUtil;
import org.aludratest.cloud.user.User;
import org.aludratest.cloud.user.admin.UserDatabaseRegistry;
import org.aludratest.cloud.web.util.BasicAuthUtil;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.util.StringUtil;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * This class acts like a normal WebDriver servlet, but intercepts all
 * communication and session creation and destruction. It is the main entry
 * point towards the Selenium resource module from a client's point of view. It
 * is hosted by a {@link WebDriverProxyServer}.
 *
 * @author falbrech
 *
 */
@Component
public class WebDriverProxyServlet extends HttpServlet implements ResourceListener, OrphanedListener {

	private static final long serialVersionUID = 8613927453312907443L;

	private static final Log LOG = LogFactory.getLog(WebDriverProxyServlet.class);

	private static final String HEADER_REQUEST_ID = "X-ACM-Request-ID";

	private static final String HEADER_RETRY = "X-ACM-Retry";

	static final String HEADER_NICE_LEVEL = "X-ACM-Nice-Level";

	static final String HEADER_JOB_NAME = "X-ACM-Job-Name";

	static final String HEADER_CUSTOM_ATTRIBUTE = "X-ACM-Custom-Attribute";

	private static final String COMMAND_NEW_SESSION = "/session";

	private static final String COMMAND_STATUS = "/status";

	private static final String COMMAND_SESSIONS = "/sessions";

	private static final Pattern PATTERN_SESSION_ID = Pattern.compile("/session/([^/]+)(/.*)?");

	private UserDatabaseRegistry userDatabaseRegistry;

	private ResourceManager resourceManager;

	private SeleniumCommandForwarder forwarder;

	private SeleniumHealthCheckService healthCheckService;

	/* Request ID -> Waiting Request */
	private Map<String, WaitingManagedRequest> waitingRequests = new ConcurrentHashMap<>();

	/* Selenium Session ID -> Managed Request */
	private Map<String, ManagedResourceRequest> workingRequests = new ConcurrentHashMap<>();

	private GateKeeper gateKeeper = new GateKeeper(50, TimeUnit.MILLISECONDS);

	@Autowired
	public WebDriverProxyServlet(ResourceManager resourceManager, SeleniumCommandForwarder forwarder,
			UserDatabaseRegistry userDatabaseRegistry, SeleniumHealthCheckService healthCheckService) {
		this.resourceManager = resourceManager;
		this.forwarder = forwarder;
		this.userDatabaseRegistry = userDatabaseRegistry;
		this.healthCheckService = healthCheckService;
	}

	@Override
	public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException {
		String threadName = Thread.currentThread().getName();

		if (req instanceof HttpServletRequest) {
			Thread.currentThread().setName(threadName + " handling " + ((HttpServletRequest) req).getRequestURI());
		}

		try {
			super.service(req, res);
		} finally {
			Thread.currentThread().setName(threadName);
		}
	}

	@Override
	protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		String command = req.getRequestURI();
		LOG.trace("Received WebDriver request on " + command);

		if (HttpMethod.POST.asString().equals(req.getMethod()) && COMMAND_NEW_SESSION.equals(command)) {
			handleCreateSession(req, resp);
			return;
		}

		// handle non-session commands here
		if (COMMAND_STATUS.equals(command)) {
			handleStatus(req, resp);
			return;
		}

		if (COMMAND_SESSIONS.equals(command)) {
			handleAllSessions(req, resp);
			return;
		}

		// extract session ID from URL
		Matcher m = PATTERN_SESSION_ID.matcher(command);
		if (!m.matches()) {
			resp.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}

		String sessionId = m.group(1);

		ManagedResourceRequest workingRequest = workingRequests.get(sessionId);
		if (workingRequest == null) {
			WebDriverUtil.sendWebDriverError(resp, 404, "invalid session id", "The session ID is not valid", null);
			return;
		}

		SeleniumResourceImpl resource = safeGetResource(workingRequest);
		if (resource == null) {
			resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			return;
		}

		if (HttpMethod.DELETE.asString().equals(req.getMethod()) && command.equals("/session/" + sessionId)) {
			handleDeleteSession(req, resp, sessionId, resource);
		} else {
			healthCheckService.resourceUsed(resource);
			try {
				forwarder.forwardTo(req, resp, resource, null);
			} catch (URISyntaxException e) {
				LOG.warn("Invalid target Selenium URL", e);
				resp.sendError(HttpServletResponse.SC_BAD_GATEWAY);
			}
		}
	}

	@Override
	public void resourceStateChanged(Resource resource, ResourceState previousState, ResourceState newState) {
		if (newState == ResourceState.DISCONNECTED) {
			workingRequests.entrySet().stream().filter(e -> resource.equals(safeGetResource(e.getValue()))).findFirst()
					.ifPresent(e -> workingRequests.remove(e.getKey()));
		}
		// everything else will be handled outside this servlet
	}

	@Override
	public void resourceOrphaned(Resource resource) {
		// find associated request and session, kill session
		// marking the request as orphaned is done by ResourceManager
		workingRequests.entrySet().stream().filter(e -> resource.equals(safeGetResource(e.getValue()))).findFirst()
				.ifPresent(e -> deleteSession(e.getKey()));
	}

	private void handleCreateSession(HttpServletRequest request, HttpServletResponse response)
			throws IOException, ServletException {
		LOG.debug("Request is CREATE SESSION");
		// is this a "retry", i.e., a Request-ID Header present?
		String requestId = request.getHeader(HEADER_REQUEST_ID);
		if (!StringUtil.isBlank(requestId)) {
			LOG.debug("Request is a retry for a waiting request");
			WaitingManagedRequest waitingRequest = waitingRequests.get(requestId);
			if (waitingRequest != null && waitingRequest.request.getState() == ManagedResourceRequest.State.WAITING) {
				waitingRequest.cancelTimeout();
				try {
					waitForResource(request, response, waitingRequest, requestId);
				} catch (URISyntaxException e) {
					LOG.warn("Invalid target Selenium URL", e);
					response.sendError(HttpServletResponse.SC_BAD_GATEWAY);
				}
				return;
			}

			// invalid request ID in header, or invalid state of request
			LOG.debug("Invalid request ID found in retry, sending HTTP 400");
			response.sendError(HttpServletResponse.SC_BAD_REQUEST);
			return;
		}

		User user = BasicAuthUtil.authenticate(request, response, userDatabaseRegistry.getSelectedUserDatabase());
		if (user == null) {
			LOG.debug("Could not authenticate request (perhaps sent 401), aborting");
			return;
		}
		LOG.debug("Request authenticated successfully for user " + user.getName());

		// here is the gate keeper, avoiding too many requests at exactly the same time
		try {
			gateKeeper.enter();
		} catch (InterruptedException e) {
			// server stopped?
			response.sendError(HttpServletResponse.SC_BAD_GATEWAY);
			return;
		}

		// create a resource request from this
		ResourceRequest resRequest = SeleniumResourceRequest.fromHttpRequest(request, user);
		try {
			ManagedResourceRequest waitingRequest = resourceManager.handleResourceRequest(resRequest);
			LOG.debug("Created new resource request");
			waitForResource(request, response, new WaitingManagedRequest(waitingRequest), null);
		} catch (ResourceManagerException e) {
			// NO Retry header here
			WebDriverUtil.sendWebDriverError(response, 500, "session not created",
					"No matching selenium resource can be found for the request", e);
		} catch (URISyntaxException e) {
			LOG.warn("Invalid target Selenium URL", e);
			response.sendError(HttpServletResponse.SC_BAD_GATEWAY);
		}
	}

	private void handleDeleteSession(HttpServletRequest request, HttpServletResponse response,
			String sessionId, SeleniumResourceImpl resource) throws IOException {
		try {
			forwarder.forwardTo(request, response, resource, null);
		} catch (URISyntaxException e) {
			LOG.warn("URI syntax exception when trying to delete selenium session", e);
		}
		resource.stopUsing();
		resource.removeResourceListener(this);
		resource.removeOrphanedListener(this);
		workingRequests.remove(sessionId);
	}

	private void deleteSession(String sessionId) {
		ManagedResourceRequest request = workingRequests.get(sessionId);
		if (request != null) {
			try {
				SeleniumResourceImpl resource = safeGetResource(request);
				resource.removeResourceListener(this);
				resource.removeOrphanedListener(this);
				workingRequests.remove(sessionId);
				forwarder.execute(resource, "DELETE", "/session/" + sessionId).close();
				LOG.debug("Deleted session with ID " + sessionId);
			} catch (URISyntaxException e) {
				LOG.warn("URI syntax exception when trying to delete selenium session", e);
			} catch (IOException e) {
				LOG.warn("I/O exception when trying to delete selenium session", e);
			}
		}
		else {
			LOG.debug("Could not delete session " + sessionId + ": No associated working request found");
		}
	}

	private void handleStatus(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		JSONObject status = new JSONObject();
		status.put("sessionId", "");
		status.put("status", 0);

		JSONObject value = new JSONObject();
		JSONObject build = new JSONObject();

		build.put("version", "alpha");
		value.put("build", build);

		JSONObject os = new JSONObject();
		os.put("arch", System.getProperty("os.arch"));
		os.put("name", System.getProperty("os.name"));
		os.put("version", System.getProperty("os.version"));

		value.put("os", os);
		status.put("value", value);

		WebDriverUtil.sendJsonObject(resp, status, HttpServletResponse.SC_OK);
	}

	private void handleAllSessions(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		// we do not reveal sessions, as these could be used for session takeover
		JSONObject result = new JSONObject();
		result.put("sessionId", "");
		result.put("status", 0);
		result.put("value", new JSONArray());

		WebDriverUtil.sendJsonObject(resp, result, HttpServletResponse.SC_OK);
	}

	private void waitForResource(HttpServletRequest request, HttpServletResponse response,
			WaitingManagedRequest waitingRequest, String requestId)
			throws IOException, ServletException, URISyntaxException {
		try {
			Resource res = waitingRequest.request.getResourceFuture().get(10, TimeUnit.SECONDS);
			LOG.debug("Successfully retrieved new session");
			// it is no longer waiting
			removeWaitingRequest(waitingRequest);

			LOG.debug("Successfully retrieved new resource for session, starting using " + res);
			startUsing(request, response, waitingRequest.request, (SeleniumResourceImpl) res);
		} catch (TimeoutException e) {
			// create request ID only in this case...
			if (requestId == null) {
				requestId = UUID.randomUUID().toString();
				waitingRequests.put(requestId, waitingRequest);
			}
			waitingRequest.scheduleTimeout();

			// send error, but with Retry and Request ID header
			Map<String, String> headers = new HashMap<String, String>();
			headers.put(HEADER_RETRY, "true");
			headers.put(HEADER_REQUEST_ID, requestId);

			LOG.debug("No resource currently available, sending retry response");
			WebDriverUtil.sendWebDriverError(response, 500, "session not created",
					"No resource currently available, please try again later.", null, headers);
		} catch (InterruptedException e) {
			response.sendError(HttpServletResponse.SC_BAD_GATEWAY);
		} catch (ExecutionException e) {
			// should never occur
			LOG.error("Internal error getting assigned resource", e);
			response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		}
	}

	private void removeWaitingRequest(WaitingManagedRequest request) {
		waitingRequests.entrySet().stream().filter(e -> e.getValue() == request).map(e -> e.getKey()).findFirst()
				.ifPresent(id -> waitingRequests.remove(id));
	}

	private void startUsing(HttpServletRequest request, HttpServletResponse response, ManagedResourceRequest managedRequest, SeleniumResourceImpl resource)
			throws IOException, URISyntaxException {
		final StringBuilder responseData = new StringBuilder();
		// forward this request to the destination, but catch the response data
		forwarder.forwardTo(request, response, resource, new HttpCommunicationsListener() {
			@Override
			public void responseComplete(HttpResponse response, byte[] cachedResponseBody) {
				try {
					responseData.append(new String(cachedResponseBody, "UTF-8"));
				} catch (UnsupportedEncodingException e) {
					// will never happen
				}
			}

			@Override
			public void requestAboutToBeSent(HttpUriRequest request, byte[] cachedRequestBody) {
				// irrelevant here
			}
		});

		if (responseData.length() > 0) {
			try {
				JSONObject obj = new JSONObject(responseData.toString());
				if (obj.has("sessionId")) {
					String sessionId = toSessionId(obj.getString("sessionId"));
					LOG.debug("Created new session with ID " + sessionId);
					workingRequests.put(sessionId, managedRequest);
					resource.startUsing();
					resource.addResourceListener(this);
					resource.addOrphanedListener(this);
					return;
				}
			}
			catch (Exception e) {
				LOG.error("No session ID in Selenium POST /sessionId response. Will abort request to avoid errors");
				resource.stopUsing();
				// nothing more to do - request has been removed from waitingRequests, and not
				// yet added to workingRequests.
			}
		}
	}

	private SeleniumResourceImpl safeGetResource(ManagedResourceRequest request) {
		try {
			Resource res = request.getResourceFuture().get(10, TimeUnit.MILLISECONDS);
			if (res instanceof SeleniumResourceImpl) {
				return (SeleniumResourceImpl) res;
			}
			return null;
		} catch (TimeoutException | InterruptedException e) {
			return null;
		} catch (ExecutionException e) {
			// very strange case
			LOG.error("Execution error when getting request resource", e);
			return null;
		}
	}

	private String toSessionId(String str) {
		return str.replace("-", "");
	}

	private class WaitingManagedRequest {

		private ManagedResourceRequest request;

		private Runnable waitTimeoutRunnable = new Runnable() {
			@Override
			public void run() {
				request.markOrphaned();
				removeWaitingRequest(WaitingManagedRequest.this);
			}
		};

		private Future<?> waitTimeoutFuture;

		public WaitingManagedRequest(ManagedResourceRequest request) {
			this.request = request;
		}

		public void cancelTimeout() {
			if (waitTimeoutFuture != null) {
				waitTimeoutFuture.cancel(false);
				waitTimeoutFuture = null;
			}
		}

		public void scheduleTimeout() {
			cancelTimeout();
			waitTimeoutFuture = healthCheckService.directSchedule(waitTimeoutRunnable, 1, TimeUnit.MINUTES);
		}
	}

}
