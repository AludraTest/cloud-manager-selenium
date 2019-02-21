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

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;

import org.aludratest.cloud.config.ConfigException;
import org.aludratest.cloud.resource.ResourceStateHolder;
import org.aludratest.cloud.resourcegroup.ResourceGroup;
import org.aludratest.cloud.resourcegroup.ResourceGroupManager;
import org.aludratest.cloud.selenium.config.ClientEntry;
import org.aludratest.cloud.selenium.config.SeleniumResourceGroupAdmin;
import org.aludratest.cloud.selenium.impl.ManagedRemoteSelenium;
import org.aludratest.cloud.selenium.impl.SeleniumResourceGroup;
import org.aludratest.cloud.selenium.impl.SeleniumResourceImpl;
import org.aludratest.cloud.web.rest.AbstractRestController;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SeleniumResourceEndpoint extends AbstractRestController {

	@Autowired
	private ResourceGroupManager groupManager;

	@PreAuthorize("hasAuthority('ROLE_ADMIN')")
	@RequestMapping(value = "/api/groups/{groupId}/selenium/resources", method = RequestMethod.GET, produces = JSON_TYPE)
	public ResponseEntity<String> getResources(@PathVariable("groupId") int groupId) {
		ResourceGroup group = groupManager.getResourceGroup(groupId);
		if (group == null || !(group instanceof SeleniumResourceGroup)) {
			return ResponseEntity.notFound().build();
		}

		JSONArray arr = new JSONArray();

		for (ResourceStateHolder rsh : group.getResourceCollection()) {
			if (!(rsh instanceof SeleniumResourceImpl)) {
				continue;
			}

			SeleniumResourceImpl res = (SeleniumResourceImpl) rsh;
			JSONObject obj = new JSONObject();
			obj.put("url", res.getOriginalUrl());
			arr.put(obj);
		}

		JSONObject result = new JSONObject();
		result.put("resources", arr);

		return wrapResultObject(result);
	}

	@PreAuthorize("hasAuthority('ROLE_ADMIN')")
	@RequestMapping(value = "/api/groups/{groupId}/selenium/config/resources", method = RequestMethod.PUT, consumes = FORM_TYPE, produces = JSON_TYPE)
	public ResponseEntity<String> addResource(@PathVariable("groupId") int groupId,
			@RequestParam("url") String seleniumUrl,
			@RequestParam(name = "maxSessions", defaultValue = "1") int maxSessions) {
		ResourceGroup group = groupManager.getResourceGroup(groupId);
		if (group == null || !(group instanceof SeleniumResourceGroup)) {
			return ResponseEntity.notFound().build();
		}

		SeleniumResourceGroupAdmin admin = ((SeleniumResourceGroup) group)
				.getAdminInterface(SeleniumResourceGroupAdmin.class);
		if (admin == null) {
			return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
		}

		ClientEntry entry = admin.addResource();
		entry.setSeleniumUrl(seleniumUrl);
		entry.setMaxSessions(maxSessions);

		try {
			admin.commit();
			return ResponseEntity.status(HttpStatus.CREATED).build();
		} catch (ConfigException e) {
			return createErrorObject("Could not add Selenium resource", e);
		}
	}

	@PreAuthorize("hasAuthority('ROLE_ADMIN')")
	@RequestMapping(value = "/api/groups/{groupId}/selenium/config/resources", method = RequestMethod.GET, produces = JSON_TYPE)
	public ResponseEntity<String> getResourceConfig(@PathVariable("groupId") int groupId) {
		ResourceGroup group = groupManager.getResourceGroup(groupId);
		if (group == null || !(group instanceof SeleniumResourceGroup)) {
			return ResponseEntity.notFound().build();
		}

		List<ManagedRemoteSelenium> remoteSeleniums = ((SeleniumResourceGroup) group).getRemoteSeleniums();
		JSONArray resources = new JSONArray();
		for (ManagedRemoteSelenium res : remoteSeleniums) {
			JSONObject obj = new JSONObject();
			obj.put("url", res.getSeleniumUrl());
			obj.put("maxSessions", res.getMaxSessions());
			resources.put(obj);
		}

		JSONObject result = new JSONObject();
		result.put("resources", resources);
		return wrapResultObject(result);
	}

	@PreAuthorize("hasAuthority('ROLE_ADMIN')")
	@RequestMapping(value = "/api/groups/{groupId}/selenium/config/resources", method = RequestMethod.POST, consumes = JSON_TYPE, produces = JSON_TYPE)
	public ResponseEntity<String> setResourceConfig(@PathVariable("groupId") int groupId,
			@RequestBody List<SeleniumResourceConfigDto> newResources) {
		ResourceGroup group = groupManager.getResourceGroup(groupId);
		if (group == null || !(group instanceof SeleniumResourceGroup)) {
			return ResponseEntity.notFound().build();
		}

		SeleniumResourceGroupAdmin admin = ((SeleniumResourceGroup) group)
				.getAdminInterface(SeleniumResourceGroupAdmin.class);
		if (admin == null) {
			return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
		}

		// build map for a sensible update, to avoid removing and adding all config
		// entries, which would restart lots of threads, proxy servers etc.
		Map<String, SeleniumResourceConfigDto> newResourcesByUrl = new LinkedHashMap<>();
		newResources.forEach(r -> newResourcesByUrl.put(r.getUrl(), r));

		List<ClientEntry> configResources = new ArrayList<>();
		admin.getConfiguredResources().forEach(configResources::add);

		List<String> desiredUrlOrder = new ArrayList<>(newResourcesByUrl.keySet());

		List<String> urlsToRemove = new ArrayList<>();

		for (ClientEntry ce : new ArrayList<>(configResources)) {
			SeleniumResourceConfigDto dto = newResourcesByUrl.get(ce.getSeleniumUrl());
			if (dto != null) {
				ce.setMaxSessions(dto.getMaxSessions());
				newResourcesByUrl.remove(ce.getSeleniumUrl());
			} else {
				urlsToRemove.add(ce.getSeleniumUrl());
			}
		}

		// ugly loop, but required due to ClientEntry internal working
		for (String url : urlsToRemove) {
			for (ClientEntry ce : admin.getConfiguredResources()) {
				if (url.equals(ce.getSeleniumUrl())) {
					admin.removeResource(ce);
					break;
				}
			}
		}

		for (SeleniumResourceConfigDto dto : newResourcesByUrl.values()) {
			ClientEntry ce = admin.addResource();
			ce.setSeleniumUrl(dto.getUrl());
			ce.setMaxSessions(dto.getMaxSessions());
		}

		sortResources(admin, desiredUrlOrder);

		try {
			admin.commit();
			return getResourceConfig(groupId);
		} catch (ConfigException ce) {
			return createErrorObject("Could not configure Selenium resources", ce);
		}
	}

	@PreAuthorize("hasAuthority('ROLE_ADMIN')")
	@RequestMapping(value = "/api/groups/{groupId}/selenium/config/resources", method = RequestMethod.DELETE, produces = JSON_TYPE)
	public ResponseEntity<String> deleteConfiguredResource(@PathVariable("groupId") int groupId,
			@RequestParam(name = "url", required = true) String url) {
		ResourceGroup group = groupManager.getResourceGroup(groupId);
		if (group == null || !(group instanceof SeleniumResourceGroup)) {
			return ResponseEntity.notFound().build();
		}

		SeleniumResourceGroupAdmin admin = ((SeleniumResourceGroup) group)
				.getAdminInterface(SeleniumResourceGroupAdmin.class);
		if (admin == null) {
			return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
		}

		Optional<ClientEntry> toDelete = StreamSupport.stream(admin.getConfiguredResources().spliterator(), false)
				.filter(ce -> url.equalsIgnoreCase(ce.getSeleniumUrl())).findFirst();
		if (!toDelete.isPresent()) {
			return ResponseEntity.notFound().build();
		}
		admin.removeResource(toDelete.get());

		try {
			admin.commit();
			return ResponseEntity.noContent().build();
		} catch (ConfigException ce) {
			return createErrorObject("Could not remove Selenium resource", ce);
		}
	}

	@PreAuthorize("hasAuthority('ROLE_ADMIN')")
	@RequestMapping(value = "/api/groups/{groupId}/selenium/screenshot", method = RequestMethod.GET, produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
	public ResponseEntity<byte[]> takeScreenshot(@PathVariable("groupId") int groupId,
			@RequestParam(name = "url", required = true) String url) {
		ResourceGroup group = groupManager.getResourceGroup(groupId);
		if (group == null || !(group instanceof SeleniumResourceGroup)) {
			return ResponseEntity.notFound().build();
		}

		SeleniumResourceGroup seleniumGroup = (SeleniumResourceGroup) group;

		ManagedRemoteSelenium selenium = seleniumGroup.getRemoteSeleniums().stream()
				.filter(mrs -> containsUrl(mrs, url)).findFirst().orElse(null);
		if (selenium == null) {
			return ResponseEntity.notFound().build();
		}

		try {
			return ResponseEntity.ok(selenium.takeScreenshot());
		} catch (IOException e) {
			return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
		}
	}

	@PreAuthorize("hasAuthority('ROLE_ADMIN')")
	@RequestMapping(value = "/api/groups/{groupId}/selenium/maintenance", method = RequestMethod.POST)
	public ResponseEntity<byte[]> setMaintenanceMode(@PathVariable("groupId") int groupId,
			@RequestParam(name = "url", required = true) String url,
			@RequestParam(name = "maintenance", required = false) Boolean maintenanceMode) {
		ResourceGroup group = groupManager.getResourceGroup(groupId);
		if (group == null || !(group instanceof SeleniumResourceGroup)) {
			return ResponseEntity.notFound().build();
		}

		SeleniumResourceGroup seleniumGroup = (SeleniumResourceGroup) group;

		ManagedRemoteSelenium selenium = seleniumGroup.getRemoteSeleniums().stream()
				.filter(mrs -> containsUrl(mrs, url)).findFirst().orElse(null);
		if (selenium == null) {
			return ResponseEntity.notFound().build();
		}

		// if maintenance mode is not set, toggle it
		if (maintenanceMode == null) {
			selenium.toggleMaintenanceMode();
		} else {
			selenium.setMaintenanceMode(maintenanceMode);
		}
		return ResponseEntity.ok().build();
	}

	private void sortResources(SeleniumResourceGroupAdmin admin, List<String> newUrlOrder) {
		List<ClientEntry> configResources = new ArrayList<>();
		admin.getConfiguredResources().forEach(configResources::add);

		while (!equalsOrder(configResources, newUrlOrder)) {
			for (int idx1 = 0; idx1 < configResources.size(); idx1++) {
				ClientEntry ce = configResources.get(idx1);
				int idx2 = newUrlOrder.indexOf(ce.getSeleniumUrl());
				if (idx2 != idx1) {
					while (idx2 > idx1) {
						admin.moveDownResource(ce);
						idx2--;
					}
					while (idx2 < idx1) {
						admin.moveUpResource(ce);
						idx2++;
					}
					break;
				}
			}
			configResources.clear();
			admin.getConfiguredResources().forEach(configResources::add);
		}
	}

	private boolean equalsOrder(List<ClientEntry> configResources, List<String> urlOrder) {
		for (int i = 0; i < configResources.size(); i++) {
			if (i != urlOrder.indexOf(configResources.get(i).getSeleniumUrl())) {
				return false;
			}
		}

		return true;
	}

	private boolean containsUrl(ManagedRemoteSelenium selenium, String url) {
		return selenium.getResources().stream().filter(res -> url.equals(res.getSeleniumUrl())).findAny().isPresent();
	}
}
