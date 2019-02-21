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

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import org.aludratest.cloud.resource.Resource;
import org.aludratest.cloud.resource.writer.XmlResourceWriter;
import org.aludratest.cloud.selenium.SeleniumResource;

public class SeleniumXmlResourceWriter implements XmlResourceWriter {

	@Override
	public void writeToXml(Resource resource, XMLStreamWriter writer) throws XMLStreamException {
		if (!(resource instanceof SeleniumResource)) {
			throw new IllegalArgumentException("This writer can only handle Selenium resources, but got resource of type "
					+ resource.getClass().getName());
		}
		writer.writeStartElement("resource");
		writer.writeAttribute("type", "selenium");

		writer.writeStartElement("url");
		writer.writeCData(((SeleniumResource) resource).getSeleniumUrl());
		writer.writeEndElement(); // url

		writer.writeEndElement(); // resource
	}

	@Override
	public boolean canWrite(Resource resource) {
		return resource instanceof SeleniumResource;
	}

}
