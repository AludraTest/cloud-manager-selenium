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

import org.codehaus.plexus.component.annotations.Component;
import org.aludratest.cloud.resource.writer.JSONResourceWriter;
import org.aludratest.cloud.resource.writer.ResourceWriter;
import org.aludratest.cloud.resource.writer.ResourceWriterFactory;
import org.aludratest.cloud.resource.writer.XmlResourceWriter;

@Component(role = ResourceWriterFactory.class, hint = "selenium")
public class SeleniumResourceWriterFactory implements ResourceWriterFactory {

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ResourceWriter> T getResourceWriter(Class<T> resourceWriterClass) {
		if (resourceWriterClass == XmlResourceWriter.class) {
			return (T) new SeleniumXmlResourceWriter();
		}
		if (resourceWriterClass == JSONResourceWriter.class) {
			return (T) new SeleniumJSONResourceWriter();
		}

		return null;
	}

}
