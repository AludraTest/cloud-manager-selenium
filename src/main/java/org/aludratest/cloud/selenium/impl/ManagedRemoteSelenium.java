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

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import javax.imageio.ImageIO;

import org.aludratest.cloud.resource.AbstractResourceCollection;
import org.aludratest.cloud.resource.Resource;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;

/**
 * Represents a remote selenium instance with 1 to N allowed and 0 to N active sessions. <br>
 * TODO more Javadoc
 *
 * @author falbrech
 *
 */
public final class ManagedRemoteSelenium extends AbstractResourceCollection<SeleniumResourceImpl> {

	private String seleniumUrl;

	private int maxSessions;

	private List<SeleniumResourceImpl> resources = Collections.emptyList();

	public ManagedRemoteSelenium(String seleniumUrl, int maxSessions) {
		this.seleniumUrl = seleniumUrl;
		changeMaxSessions(maxSessions);
	}

	public String getSeleniumUrl() {
		return seleniumUrl;
	}

	public int getMaxSessions() {
		return maxSessions;
	}

	public List<SeleniumResourceImpl> getResources() {
		return resources;
	}

	public void changeMaxSessions(int newMaxSessions) {
		int diff = newMaxSessions - maxSessions;
		List<SeleniumResourceImpl> newResources = new ArrayList<>(resources);
		if (diff < 0) {
			while (diff < 0) {
				fireResourceRemoved(newResources.remove(newResources.size() - 1));
				diff++;
			}
		}
		else {
			for (int i = 0; i < diff; i++) {
				SeleniumResourceImpl res = new SeleniumResourceImpl(seleniumUrl, maxSessions + i + 1);
				newResources.add(res);
				fireResourceAdded(res);
			}
		}
		maxSessions = newMaxSessions;
		resources = Collections.unmodifiableList(newResources);
	}

	@Override
	public int getResourceCount() {
		return resources.size();
	}

	@Override
	public boolean contains(Resource resource) {
		return resources.contains(resource);
	}

	@Override
	public Iterator<SeleniumResourceImpl> iterator() {
		return resources.iterator();
	}

	@Override
	public int hashCode() {
		return seleniumUrl.hashCode();
	}

	public byte[] takeScreenshot() throws IOException {
		String url = seleniumUrl + "/selenium-server/driver/?cmd=captureScreenshotToString";

		try (InputStream in = new URL(url).openStream()) {
			;
			in.read(new byte[3]); // read away "OK,"
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			IOUtils.copy(in, baos);

			// decode Base64
			byte[] rawImageData = Base64.decodeBase64(baos.toByteArray());

			// create image from bytes
			BufferedImage img = ImageIO.read(new ByteArrayInputStream(rawImageData));

			// shrink image
			float sizeFactor = 2;
			BufferedImage imgSmall = new BufferedImage((int) (img.getWidth() / sizeFactor),
					(int) (img.getHeight() / sizeFactor), BufferedImage.TYPE_INT_RGB);
			Graphics2D g2d = imgSmall.createGraphics();
			g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2d.drawImage(img, 0, 0, imgSmall.getWidth(), imgSmall.getHeight(), 0, 0, img.getWidth(), img.getHeight(),
					null);
			g2d.dispose();

			// get PNG bytes
			baos = new ByteArrayOutputStream();
			ImageIO.write(imgSmall, "png", baos);
			return baos.toByteArray();
		}
	}

	public void setMaintenanceMode(boolean maintananceMode) {
		resources.forEach(r -> r.switchToMaintenanceMode(maintananceMode));
	}

	public void toggleMaintenanceMode() {
		if (resources.isEmpty()) {
			return;
		}

		// assume that first resource is same as all resources
		boolean currentMode = resources.get(0).isInMaintenanceMode();
		setMaintenanceMode(!currentMode);
	}

}
