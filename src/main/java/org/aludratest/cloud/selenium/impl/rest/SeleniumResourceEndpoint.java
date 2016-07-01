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

import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;

import org.aludratest.cloud.app.CloudManagerApp;
import org.aludratest.cloud.config.ConfigException;
import org.aludratest.cloud.resource.ResourceStateHolder;
import org.aludratest.cloud.resourcegroup.ResourceGroup;
import org.aludratest.cloud.resourcegroup.ResourceGroupManager;
import org.aludratest.cloud.resourcegroup.StaticResourceGroupAdmin;
import org.aludratest.cloud.rest.AbstractRestConnector;
import org.aludratest.cloud.rest.RestConnector;
import org.aludratest.cloud.selenium.config.ClientEntry;
import org.aludratest.cloud.selenium.impl.SeleniumResourceGroup;
import org.aludratest.cloud.selenium.impl.SeleniumResourceImpl;
import org.aludratest.cloud.selenium.impl.SeleniumUtil;
import org.codehaus.plexus.component.annotations.Component;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

@Component(role = RestConnector.class, hint = "selenium-resource")
@Path("/groups/{groupId: [0-9]{1,10}}/selenium/resources")
public class SeleniumResourceEndpoint extends AbstractRestConnector {

	@GET
	@Produces(JSON_TYPE)
	public Response getResources(@PathParam("groupId") int groupId) throws JSONException {
		ResourceGroupManager manager = CloudManagerApp.getInstance().getResourceGroupManager();

		ResourceGroup group = manager.getResourceGroup(groupId);
		if (group == null || !(group instanceof SeleniumResourceGroup)) {
			return Response.status(HttpServletResponse.SC_NOT_FOUND).build();
		}

		JSONArray arr = new JSONArray();

		for (ResourceStateHolder rsh : group.getResourceCollection()) {
			if (!(rsh instanceof SeleniumResourceImpl)) {
				continue;
			}

			SeleniumResourceImpl res = (SeleniumResourceImpl) rsh;
			JSONObject obj = new JSONObject();
			obj.put("url", res.getOriginalUrl());
			// TODO add name as soon as available
			arr.put(obj);
		}

		JSONObject result = new JSONObject();
		result.put("resources", arr);

		return wrapResultObject(result);
	}

	@DELETE
	@Produces(JSON_TYPE)
	public Response deleteResource(@PathParam("groupId") int groupId, @QueryParam("url") String url) throws JSONException {
		return doUrlAction(groupId, url, new SeleniumAdminAction() {
			@Override
			public void perform(ClientEntry entry, StaticResourceGroupAdmin<ClientEntry> admin) throws ConfigException {
				admin.removeResource(entry);
			}
		});
	}
	
	@PUT
	@Consumes(FORM_TYPE)
	@Produces(JSON_TYPE)
	public Response addResource(@PathParam("groupId") int groupId, @FormParam("url") String url) throws JSONException {
		// TODO add name as soon as available
		ResourceGroupManager manager = CloudManagerApp.getInstance().getResourceGroupManager();

		ResourceGroup group = manager.getResourceGroup(groupId);
		if (group == null || !(group instanceof SeleniumResourceGroup)) {
			return Response.status(HttpServletResponse.SC_NOT_FOUND).build();
		}

		if (url == null || "".equals(url)) {
			return createErrorObject(new IllegalArgumentException("You must specify an URL of the Selenium resource to add."));
		}

		@SuppressWarnings("unchecked")
		StaticResourceGroupAdmin<ClientEntry> admin = ((SeleniumResourceGroup) group)
				.getAdminInterface(StaticResourceGroupAdmin.class);

		try {
			SeleniumUtil.validateSeleniumResourceNotExisting(url, admin, Integer.valueOf(groupId));
		}
		catch (ConfigException e) {
			return createErrorObject(e);
		}

		ClientEntry ce = admin.addResource();
		ce.setSeleniumUrl(url);

		try {
			admin.commit();
			return Response.status(HttpServletResponse.SC_CREATED).build();
		}
		catch (ConfigException e) {
			return createErrorObject(e);
		}
	}

	@POST
	@Path("/move/up")
	@Consumes(FORM_TYPE)
	@Produces(JSON_TYPE)
	public Response moveResourceUp(@PathParam("groupId") int groupId, @FormParam("url") String url) throws JSONException {
		return doUrlAction(groupId, url, new SeleniumAdminAction() {
			@Override
			public void perform(ClientEntry entry, StaticResourceGroupAdmin<ClientEntry> admin) throws ConfigException {
				admin.moveUpResource(entry);
			}
		});
	}

	@POST
	@Path("/move/down")
	@Consumes(FORM_TYPE)
	@Produces(JSON_TYPE)
	public Response moveResourceDown(@PathParam("groupId") int groupId, @FormParam("url") String url) throws JSONException {
		return doUrlAction(groupId, url, new SeleniumAdminAction() {
			@Override
			public void perform(ClientEntry entry, StaticResourceGroupAdmin<ClientEntry> admin) throws ConfigException {
				admin.moveDownResource(entry);
			}
		});
	}

	private Response doUrlAction(int groupId, String url, SeleniumAdminAction action) throws JSONException {
		ResourceGroupManager manager = CloudManagerApp.getInstance().getResourceGroupManager();

		ResourceGroup group = manager.getResourceGroup(groupId);
		if (group == null || !(group instanceof SeleniumResourceGroup)) {
			return Response.status(HttpServletResponse.SC_NOT_FOUND).build();
		}

		if (url == null || "".equals(url)) {
			return createErrorObject(new IllegalArgumentException("You must specify an URL of the Selenium resource."));
		}

		@SuppressWarnings("unchecked")
		StaticResourceGroupAdmin<ClientEntry> admin = ((SeleniumResourceGroup) group)
				.getAdminInterface(StaticResourceGroupAdmin.class);

		// find resource to delete
		ClientEntry entry = null;
		for (ClientEntry ce : admin.getConfiguredResources()) {
			if (url.equals(ce.getSeleniumUrl())) {
				entry = ce;
				break;
			}
		}

		if (entry == null) {
			return createErrorObject(new IllegalArgumentException("No Selenium resource with this URL found in this group."));
		}

		try {
			action.perform(entry, admin);
			admin.commit();
			return getResources(groupId);
		}
		catch (ConfigException e) {
			return createErrorObject(e);
		}
	}


	private static interface SeleniumAdminAction {

		public void perform(ClientEntry entry, StaticResourceGroupAdmin<ClientEntry> admin) throws ConfigException;

	}
}
