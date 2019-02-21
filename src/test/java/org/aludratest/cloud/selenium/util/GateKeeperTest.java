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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.Test;

public class GateKeeperTest {

	@Test
	public void testSimpleGoThrough() throws Exception {
		TestSystemTimeService time = new TestSystemTimeService();

		GateKeeper gk = new GateKeeper(10, TimeUnit.SECONDS, time);

		// must not block
		Thread t = new Thread(() -> {
			try {
				gk.enter();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		});
		t.start();
		t.join(1000);
		assertFalse(t.isAlive());
		if (t.isAlive()) {
			t.interrupt();
		}
	}

	@Test
	public void testSingleBlock() throws Exception {
		TestSystemTimeService time = new TestSystemTimeService();

		GateKeeper gk = new GateKeeper(10, TimeUnit.SECONDS, time);

		// build up two Threads
		Thread[] threads = new Thread[2];
		for (int i = 0; i < threads.length; i++) {
			threads[i] = new Thread(() -> {
				try {
					gk.enter();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			});
		}

		threads[0].start();
		Thread.sleep(10);
		threads[1].start();

		threads[0].join(1000);
		assertFalse(threads[0].isAlive());
		assertTrue(threads[1].isAlive());

		// advance 5 seconds
		time.millis += TimeUnit.SECONDS.toMillis(5);
		Thread.sleep(100);
		assertTrue(threads[1].isAlive());

		// advance another 6 seconds
		time.millis += TimeUnit.SECONDS.toMillis(6);
		Thread.sleep(100);
		assertFalse(threads[1].isAlive());
	}

	private static class TestSystemTimeService implements SystemTimeService {

		private int millis;

		@Override
		public long getTimeMillis() {
			return millis;
		}

	}

}
