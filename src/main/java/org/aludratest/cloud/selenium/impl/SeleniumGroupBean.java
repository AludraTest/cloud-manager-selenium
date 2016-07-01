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

import java.util.ArrayList;
import java.util.List;

import javax.faces.bean.ManagedBean;
import javax.faces.bean.ViewScoped;
import javax.faces.component.UIComponent;
import javax.faces.context.FacesContext;
import javax.faces.convert.Converter;
import javax.faces.convert.ConverterException;
import javax.faces.event.FacesEvent;
import javax.faces.model.SelectItem;

import org.aludratest.cloud.app.CloudManagerApp;
import org.aludratest.cloud.config.ConfigException;
import org.aludratest.cloud.resourcegroup.ResourceGroup;
import org.aludratest.cloud.resourcegroup.StaticResourceGroupAdmin;
import org.aludratest.cloud.selenium.config.ClientEntry;
import org.aludratest.cloud.util.JSFUtil;

@ManagedBean(name = "seleniumGroupBean")
@ViewScoped
public class SeleniumGroupBean {

	private String newSeleniumUrl;

	private ClientEntry selectedResource;
	
	private StaticResourceGroupAdmin<ClientEntry> resAdmin;

	private Integer groupId;

	public ClientEntry getSelectedResource() {
		return selectedResource;
	}

	public void setSelectedResource(ClientEntry selectedResource) {
		this.selectedResource = selectedResource;
	}

	public String getNewSeleniumUrl() {
		return newSeleniumUrl;
	}

	public void setNewSeleniumUrl(String newSeleniumUrl) {
		this.newSeleniumUrl = newSeleniumUrl;
	}
	
	public Converter getResourcesConverter() {
		return resourcesConverter;
	}

	public String setGroupId(Integer groupId) {
		this.groupId = groupId;
		return "";
	}

	public String getCalculateResourceBoxHeight() {
		// for each 10 items, 200px height
		return "" + ((getResourcesItems().size() / 10 + 1) * 200);
	}

	public List<SelectItem> getResourcesItems() {
		List<SelectItem> result = new ArrayList<SelectItem>();
		for (ClientEntry ce : getResAdmin().getConfiguredResources()) {
			if (ce.getSeleniumUrl() != null) {
				result.add(new SelectItem(ce, ce.getSeleniumUrl()));
			}
		}

		return result;
	}

	public void moveUp() {
		StaticResourceGroupAdmin<ClientEntry> resAdmin = getResAdmin();

		if (selectedResource == null || resAdmin == null) {
			return;
		}

		selectedResource = resAdmin.moveUpResource(selectedResource);
	}

	public void moveDown() {
		StaticResourceGroupAdmin<ClientEntry> resAdmin = getResAdmin();

		if (selectedResource == null || resAdmin == null) {
			return;
		}

		selectedResource = resAdmin.moveDownResource(selectedResource);
	}

	public void deleteResource() {
		StaticResourceGroupAdmin<ClientEntry> resAdmin = getResAdmin();

		if (resAdmin != null && selectedResource != null) {
			resAdmin.removeResource(selectedResource);
		}

		selectedResource = null;
	}

	public void addResource(FacesEvent event) {
		StaticResourceGroupAdmin<ClientEntry> resAdmin = getResAdmin();

		if (resAdmin == null) {
			return;
		}

		UIComponent c = event.getComponent().findComponent("seleniumUrl");
		String clientId = c == null ? null : c.getClientId();

		if (newSeleniumUrl == null || "".equals(newSeleniumUrl.trim())) {
			FacesContext.getCurrentInstance().addMessage(clientId,
					JSFUtil.createErrorMessage("Please enter a valid Selenium URL."));
			return;
		}

		try {
			SeleniumUtil.validateSeleniumResourceNotExisting(newSeleniumUrl, resAdmin, groupId);
		}
		catch (ConfigException e) {
			FacesContext.getCurrentInstance().addMessage(clientId, JSFUtil.createErrorMessage(e.getMessage()));
			return;
		}

		ClientEntry ce = resAdmin.addResource();
		ce.setSeleniumUrl(newSeleniumUrl);
		newSeleniumUrl = null;
	}

	public void save() {
		StaticResourceGroupAdmin<ClientEntry> resAdmin = getResAdmin();
		if (resAdmin != null) {
			try {
				resAdmin.commit();
				this.resAdmin = null;

				FacesContext.getCurrentInstance().addMessage(null,
						JSFUtil.createInfoMessage("The resources have been saved successfully."));
			}
			catch (ConfigException e) {
				FacesContext.getCurrentInstance().addMessage(null, JSFUtil.createErrorMessage(e.getMessage()));
			}
		}
	}

	@SuppressWarnings("unchecked")
	private StaticResourceGroupAdmin<ClientEntry> getResAdmin() {
		if (resAdmin == null && groupId != null) {
			ResourceGroup group = CloudManagerApp.getInstance().getResourceGroupManager().getResourceGroup(groupId.intValue());
			if (group != null && (group instanceof SeleniumResourceGroup)) {
				resAdmin = ((SeleniumResourceGroup) group).getAdminInterface(StaticResourceGroupAdmin.class);
			}
		}
		return resAdmin;
	}

	private static Converter resourcesConverter = new Converter() {
		
		@Override
		public String getAsString(FacesContext context, UIComponent component, Object value) throws ConverterException {
			if (!(value instanceof ClientEntry)) {
				return null;
			}
			
			ClientEntry ce = (ClientEntry) value;
			return "" + ce.getSeleniumUrl();
		}
		
		@Override
		public Object getAsObject(FacesContext context, UIComponent component, String value) throws ConverterException {
			if (value == null) {
				return null;
			}
			
			SeleniumGroupBean instance = JSFUtil.getExpressionValue(context, "#{seleniumGroupBean}", SeleniumGroupBean.class);

			if (instance == null) {
				return null;
			}

			StaticResourceGroupAdmin<ClientEntry> resAdmin = instance.getResAdmin();
			if (resAdmin == null) {
				return null;
			}

			for (ClientEntry ce : resAdmin.getConfiguredResources()) {
				if (value.equals(ce.getSeleniumUrl())) {
					return ce;
				}
			}
			
			return null;
		}
	};

}
