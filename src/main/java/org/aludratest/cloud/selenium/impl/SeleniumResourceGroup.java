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

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import org.aludratest.cloud.config.ConfigException;
import org.aludratest.cloud.config.ConfigManager;
import org.aludratest.cloud.config.MainPreferences;
import org.aludratest.cloud.config.Preferences;
import org.aludratest.cloud.config.admin.AbstractConfigurationAdmin;
import org.aludratest.cloud.config.admin.ConfigNodeBasedList;
import org.aludratest.cloud.config.admin.ConfigurationAdmin;
import org.aludratest.cloud.resource.AbstractResourceCollection;
import org.aludratest.cloud.resource.Resource;
import org.aludratest.cloud.resource.ResourceCollection;
import org.aludratest.cloud.resource.ResourceCollectionListener;
import org.aludratest.cloud.resource.ResourceStateHolder;
import org.aludratest.cloud.resourcegroup.AbstractAuthorizingResourceGroup;
import org.aludratest.cloud.resourcegroup.StaticResourceGroupAdmin;
import org.aludratest.cloud.selenium.SeleniumResource;
import org.aludratest.cloud.selenium.SeleniumResourceType;
import org.aludratest.cloud.selenium.config.ClientEntry;
import org.aludratest.cloud.selenium.config.SeleniumResourceGroupAdmin;
import org.aludratest.cloud.user.admin.UserDatabaseRegistry;

public final class SeleniumResourceGroup extends AbstractAuthorizingResourceGroup implements ResourceCollectionListener {

	private static final String PREFS_RESOURCES_NODE = "resources";

	private List<ManagedRemoteSelenium> remoteSeleniums = new ArrayList<>();

	private List<SeleniumResourceGroupListener> listeners = new ArrayList<>();

	private SeleniumResourceCollection resourceCollection = new SeleniumResourceCollection();

	public SeleniumResourceGroup(ConfigManager configManager, UserDatabaseRegistry userDatabaseRegistry) {
		super(SeleniumResourceType.INSTANCE, configManager, userDatabaseRegistry);
	}

	public List<ManagedRemoteSelenium> getRemoteSeleniums() {
		synchronized (remoteSeleniums) {
			return Collections.unmodifiableList(new ArrayList<>(remoteSeleniums));
		}
	}

	public synchronized void addSeleniumResourceGroupListener(SeleniumResourceGroupListener listener) {
		if (!listeners.contains(listener)) {
			listeners.add(listener);
		}
	}

	public synchronized void removeSeleniumResourceGroupListener(SeleniumResourceGroupListener listener) {
		listeners.remove(listener);
	}

	private synchronized List<SeleniumResourceGroupListener> safeGetListeners() {
		return new ArrayList<>(listeners);
	}

	private void fireRemoteSeleniumAdded(ManagedRemoteSelenium remoteSelenium) {
		List<SeleniumResourceGroupListener> listeners = safeGetListeners();
		for (SeleniumResourceGroupListener listener : listeners) {
			listener.remoteSeleniumAdded(remoteSelenium);
		}
	}

	private void fireRemoteSeleniumRemoved(ManagedRemoteSelenium remoteSelenium) {
		List<SeleniumResourceGroupListener> listeners = safeGetListeners();
		for (SeleniumResourceGroupListener listener : listeners) {
			listener.remoteSeleniumRemoved(remoteSelenium);
		}
	}

	@Override
	public ResourceCollection<? extends ResourceStateHolder> getResourceCollection() {
		return resourceCollection;
	}

	@Override
	protected void configure(MainPreferences preferences) throws ConfigException {
		super.configure(preferences);

		List<ManagedRemoteSelenium> currentRemotes = getRemoteSeleniums();

		// build list of remote seleniums - first of all, only for comparison with existing list
		List<ManagedRemoteSelenium> configRemotes = buildRemoteSeleniumFromConfig(preferences);

		List<ManagedRemoteSelenium> toAdd = new ArrayList<>();
		List<ManagedRemoteSelenium> toRemove = new ArrayList<>();

		// first pass: check which remotes have just changed their maxSessions (and find removed ones, too)
		for (ManagedRemoteSelenium remote : currentRemotes) {
			ManagedRemoteSelenium config = findRemoteForUrl(configRemotes, remote.getSeleniumUrl());
			if (config == null) {
				toRemove.add(remote);
			}
			else if (remote.getMaxSessions() != config.getMaxSessions()) {
				// will fire resourceAdded or resourceRemoved
				remote.changeMaxSessions(config.getMaxSessions());
			}
		}

		// second pass: check added ones
		for (ManagedRemoteSelenium remote : configRemotes) {
			if (findRemoteForUrl(currentRemotes, remote.getSeleniumUrl()) == null) {
				toAdd.add(remote);
			}
		}

		for (ManagedRemoteSelenium remote : toAdd) {
			addRemoteSelenium(remote);
		}
		for (ManagedRemoteSelenium remote : toRemove) {
			removeRemoteSelenium(remote);
		}

		// finally, sort to desired order
		synchronized (remoteSeleniums) {
			remoteSeleniums.sort((rs1, rs2) -> {
				int idx1 = indexOf(configRemotes, r -> r.getSeleniumUrl().equals(rs1.getSeleniumUrl()));
				int idx2 = indexOf(configRemotes, r -> r.getSeleniumUrl().equals(rs2.getSeleniumUrl()));
				return idx1 - idx2;
			});
		}
	}

	@Override
	public void validateConfiguration(Preferences preferences) throws ConfigException {
		super.validateConfiguration(preferences);

		internalValidateConfig(preferences);
	}

	private static void internalValidateConfig(Preferences preferences) throws ConfigException {
		Map<String, Integer> urlCount = new HashMap<>();
		for (Preferences p : getResourcePreferences(preferences)) {
			validateRemoteConfig(p);
			Integer i = urlCount.get(p.getStringValue("seleniumUrl"));
			urlCount.put(p.getStringValue("seleniumUrl"),
					i == null ? Integer.valueOf(1) : Integer.valueOf(i.intValue() + 1));
		}

		// overall validation: No URL must be present twice
		Optional<Map.Entry<String, Integer>> doubleEntry = urlCount.entrySet().stream()
				.filter(e -> e.getValue().intValue() > 1).findAny();
		if (doubleEntry.isPresent()) {
			throw new ConfigException("Selenium URL " + doubleEntry.get().getKey() + " is configured twice");
		}
	}

	private void addRemoteSelenium(ManagedRemoteSelenium remoteSelenium) {
		synchronized (remoteSeleniums) {
			remoteSeleniums.add(remoteSelenium);
		}
		// register ourself as proxy for resource change listener
		remoteSelenium.addResourceCollectionListener(this);

		fireRemoteSeleniumAdded(remoteSelenium);
	}

	private void removeRemoteSelenium(ManagedRemoteSelenium remoteSelenium) {
		synchronized (remoteSeleniums) {
			remoteSeleniums.remove(remoteSelenium);
		}
		remoteSelenium.removeResourceCollectionListener(this);

		fireRemoteSeleniumRemoved(remoteSelenium);
	}

	private static ManagedRemoteSelenium findRemoteForUrl(List<ManagedRemoteSelenium> ls, String url) {
		for (ManagedRemoteSelenium remote : ls) {
			if (url.equals(remote.getSeleniumUrl()))
				return remote;
		}
		return null;
	}

	private static List<Preferences> getResourcePreferences(Preferences config) {
		Preferences resPrefs = config.getChildNode(PREFS_RESOURCES_NODE);
		if (resPrefs == null) {
			return Collections.emptyList();
		}

		List<Preferences> result = new ArrayList<>();
		List<Integer> ids = new ArrayList<Integer>();

		for (String node : resPrefs.getChildNodeNames()) {
			if (node.matches("[0-9]{1,10}")) {
				ids.add(Integer.valueOf(node));
			}
		}

		Collections.sort(ids);

		for (Integer id : ids) {
			result.add(resPrefs.getChildNode(id.toString()));
		}

		return result;

	}

	private List<ManagedRemoteSelenium> buildRemoteSeleniumFromConfig(MainPreferences config) throws ConfigException {
		List<ManagedRemoteSelenium> result = new ArrayList<>();
		for (Preferences p : getResourcePreferences(config)) {
			result.add(createRemoteSeleniumFromPreferencesElement(p));
		}

		return result;
	}

	private ManagedRemoteSelenium createRemoteSeleniumFromPreferencesElement(Preferences resourceConfig) throws ConfigException {
		String originalUrl = resourceConfig.getStringValue("seleniumUrl");
		int maxSessions = resourceConfig.getIntValue("maxSessions", 1);
		if (originalUrl == null) {
			throw new ConfigException("Selenium URL must be specified", "seleniumUrl");
		}

		try {
			new URL(originalUrl);
			return new ManagedRemoteSelenium(originalUrl, maxSessions);
		}
		catch (MalformedURLException e) {
			throw new ConfigException("Selenium RC Client URL is not a valid URL", "seleniumUrl");
		}
	}

	private static void validateRemoteConfig(Preferences resourceConfig) throws ConfigException {
		String originalUrl = resourceConfig.getStringValue("seleniumUrl");
		if (originalUrl == null) {
			throw new ConfigException("Selenium RC Client URL must be specified", "seleniumUrl");
		}

		try {
			new URL(originalUrl);
		}
		catch (MalformedURLException e) {
			throw new ConfigException("Selenium RC Client URL is not a valid URL", "seleniumUrl");
		}

		int maxSessions = resourceConfig.getIntValue("maxSessions", 1);
		if (maxSessions < 1) {
			throw new ConfigException("Max no. of sessions must be greater than 0", "maxSessions");
		}
	}

	@Override
	public void resourceAdded(Resource resource) {
		resourceCollection.fireSeleniumResourceAdded(resource);
	}

	@Override
	public void resourceRemoved(Resource resource) {
		resourceCollection.fireSeleniumResourceRemoved(resource);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ConfigurationAdmin> T getAdminInterface(Class<T> ifaceClass) {
		if (ifaceClass == StaticResourceGroupAdmin.class || ifaceClass == SeleniumResourceGroupAdmin.class) {
			return (T) new SeleniumResourceGroupConfigurationAdmin(getPreferences(), getConfigManager());
		}
		return super.getAdminInterface(ifaceClass);
	}

	private static class SeleniumResourceGroupConfigurationAdmin extends AbstractConfigurationAdmin
			implements SeleniumResourceGroupAdmin {

		private ConfigNodeBasedList<ClientEntry> resourcesList;

		protected SeleniumResourceGroupConfigurationAdmin(MainPreferences groupPreferences, ConfigManager configManager) {
			super(groupPreferences, configManager);
			try {
				resourcesList = new ConfigNodeBasedList<ClientEntry>(
						getPreferences().createChildNode(SeleniumResourceGroup.PREFS_RESOURCES_NODE), "", ClientEntry.class);
			}
			catch (Exception e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		protected void validateConfig(Preferences preferences) throws ConfigException {
			SeleniumResourceGroup.internalValidateConfig(preferences);
		}

		@Override
		public ClientEntry addResource() {
			return resourcesList.addElement();
		}

		@Override
		public void removeResource(ClientEntry resource) {
			resourcesList.remove(resource);
		}

		@Override
		public Iterable<ClientEntry> getConfiguredResources() {
			return Collections.unmodifiableList(new ArrayList<>(resourcesList));
		}

		@Override
		public ClientEntry moveUpResource(ClientEntry resource) {
			int index = resourcesList.indexOf(resource);
			if (index > 0) {
				resourcesList.moveElement(index, true);
				return resourcesList.get(index - 1);
			}
			return resource;
		}

		@Override
		public ClientEntry moveDownResource(ClientEntry resource) {
			int index = resourcesList.indexOf(resource);
			if (index < resourcesList.size() - 1) {
				resourcesList.moveElement(index, false);
				return resourcesList.get(index + 1);
			}
			return resource;
		}
	}

	private class SeleniumResourceCollection extends AbstractResourceCollection<SeleniumResource> {

		private List<ManagedRemoteSelenium> getSafeRemotes() {
			synchronized (remoteSeleniums) {
				return new ArrayList<>(remoteSeleniums);
			}
		}

		@Override
		public int getResourceCount() {
			int count = 0;
			for (ManagedRemoteSelenium remoteSelenium : getSafeRemotes()) {
				count += remoteSelenium.getResources().size();
			}
			return count;
		}

		@Override
		public boolean contains(Resource resource) {
			for (ManagedRemoteSelenium remoteSelenium : getSafeRemotes()) {
				if (remoteSelenium.getResources().contains(resource)) {
					return true;
				}
			}
			return false;
		}

		@Override
		public Iterator<SeleniumResource> iterator() {
			List<SeleniumResource> resources = new ArrayList<>();
			for (ManagedRemoteSelenium remoteSelenium : getSafeRemotes()) {
				resources.addAll(remoteSelenium.getResources());
			}

			return resources.iterator();
		}

		// due to visibility of parent class
		private void fireSeleniumResourceAdded(Resource resource) {
			fireResourceAdded(resource);
		}

		// due to visibility of parent class
		private void fireSeleniumResourceRemoved(Resource resource) {
			fireResourceRemoved(resource);
		}
	}

	private static <T> int indexOf(List<T> list, Predicate<? super T> predicate) {
		int idx = 0;
		for (Iterator<T> iter = list.iterator(); iter.hasNext(); idx++) {
			if (predicate.test(iter.next())) {
				return idx;
			}
		}

		return -1;
	}
}
