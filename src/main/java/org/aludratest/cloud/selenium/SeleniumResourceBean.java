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
package org.aludratest.cloud.selenium;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

import javax.faces.bean.ManagedBean;
import javax.faces.bean.SessionScoped;
import javax.faces.event.ActionEvent;
import javax.imageio.ImageIO;

import org.aludratest.cloud.app.CloudManagerApp;
import org.aludratest.cloud.config.MutablePreferences;
import org.aludratest.cloud.config.Preferences;
import org.aludratest.cloud.resource.ResourceCollection;
import org.aludratest.cloud.resource.ResourceStateHolder;
import org.aludratest.cloud.resourcegroup.ResourceGroup;
import org.aludratest.cloud.resourcegroup.ResourceGroupManager;
import org.aludratest.cloud.util.JSFUtil;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.primefaces.model.ByteArrayContent;
import org.primefaces.model.StreamedContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ManagedBean(name = "seleniumResourceBean")
@SessionScoped
public class SeleniumResourceBean {
	
	private static final Logger LOG = LoggerFactory.getLogger(SeleniumResourceBean.class);

	private byte[] currentScreenshot;

	MutablePreferences config;

	private byte[] takeSeleniumResourceScreenshot(String seleniumUrl) {
		String url = seleniumUrl;
		url += "/selenium-server/driver/?cmd=captureScreenshotToString";

		InputStream in = null;
		try {
			in = new URL(url).openStream();
			in.read(new byte[3]); // read away "OK,"
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			IOUtils.copy(in, baos);

			// decode Base64
			byte[] rawImageData = Base64.decodeBase64(baos.toByteArray());

			// create image from bytes
			BufferedImage img = ImageIO.read(new ByteArrayInputStream(rawImageData));

			// shrink image
			float sizeFactor = 2;
			BufferedImage imgSmall = new BufferedImage((int) (img.getWidth() / sizeFactor), (int) (img.getHeight() / sizeFactor),
					BufferedImage.TYPE_INT_RGB);
			Graphics2D g2d = imgSmall.createGraphics();
			g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2d.drawImage(img, 0, 0, imgSmall.getWidth(), imgSmall.getHeight(), 0, 0, img.getWidth(), img.getHeight(), null);
			g2d.dispose();

			// get PNG bytes
			baos = new ByteArrayOutputStream();
			ImageIO.write(imgSmall, "png", baos);
			return baos.toByteArray();
		}
		catch (IOException e) {
			LOG.warn("Could not take Selenium screenshot: " + e.getMessage());
			return null;
		}
		finally {
			IOUtils.closeQuietly(in);
		}
	}
	
	public void updateScreenshot(ActionEvent event) {
		currentScreenshot = null;

		String resourceString = (String) JSFUtil.getParamValue(event.getComponent(), "resourceName");
		if (resourceString == null) {
			return;
		}

		SeleniumResource res = stringToResource(resourceString);
		if (res == null) {
			return;
		}

		currentScreenshot = takeSeleniumResourceScreenshot(res.getOriginalUrl());
	}

	public boolean hasScreenshot() {
		return currentScreenshot != null;
	}

	public StreamedContent getScreenshot() {
		if (currentScreenshot == null) {
			return null;
		}

		return new ByteArrayContent(currentScreenshot, "image/png");
	}

	public String loadConfig(Preferences config) {
		this.config = (MutablePreferences) config;
		return "";
	}

	public synchronized boolean isResourceLocked(String resourceString) {
		// find resource from string representation
		SeleniumResource res = stringToResource(resourceString);
		if (res == null) {
			return false;
		}
		return res.isInMaintenanceMode();
	}

	public synchronized void lockResource(ActionEvent e) {
		String resourceString = (String) JSFUtil.getParamValue(e.getComponent(), "resourceName");
		if (resourceString == null) {
			return;
		}
		SeleniumResource res = stringToResource(resourceString);
		if (res != null) {
			res.switchToMaintenanceMode(!res.isInMaintenanceMode());
		}
	}

	public synchronized void closeResourceSessions(ActionEvent e) {
		String resourceString = (String) JSFUtil.getParamValue(e.getComponent(), "resourceName");
		if (resourceString == null) {
			return;
		}
		SeleniumResource res = stringToResource(resourceString);
		if (res != null) {
			res.forceCloseAllSessions();
		}
	}

	private SeleniumResource stringToResource(String string) {
		ResourceGroupManager manager = CloudManagerApp.getInstance().getResourceGroupManager();
		for (int groupId : manager.getAllResourceGroupIds()) {
			ResourceGroup group = manager.getResourceGroup(groupId);
			if (group.getResourceType().equals(SeleniumResourceType.INSTANCE)) {
				ResourceCollection<?> collection = group.getResourceCollection();
				for (ResourceStateHolder rsh : collection) {
					if (rsh.toString().equals(string)) {
						return (SeleniumResource) rsh;
					}
				}
			}
		}

		return null;
	}

}
