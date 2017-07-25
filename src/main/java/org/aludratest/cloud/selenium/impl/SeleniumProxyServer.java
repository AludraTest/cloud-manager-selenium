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

import java.lang.management.ManagementFactory;
import java.net.MalformedURLException;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * Encapsulates a Jetty server which takes and manages Selenium HTTP Proxy servlets. 
 * 
 * @author falbrech
 *
 */
public final class SeleniumProxyServer extends HttpResourceProxyServer<SeleniumHttpProxy, SeleniumResourceImpl> implements
		SeleniumProxyServerMBean {
	
	private static final long serialVersionUID = -5256156265002984079L;

	private int executorSize = 4;

	private DelegatingScheduledExecutorService healthCheckDelegator = new DelegatingScheduledExecutorService(
			createHealthCheckExecutor(executorSize));

	private SeleniumModuleConfiguration configuration;

	public SeleniumProxyServer(SeleniumModuleConfiguration configuration, String hostName) {
		super(hostName, configuration.getSeleniumProxyPort(), configuration.getMaxProxyQueueSize(), configuration
				.getMaxProxyThreads());
		this.configuration = configuration;
	}

	@Override
	public void start() throws Exception {
		super.start();
		// register in JMS
		MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
		try {
			mbs.unregisterMBean(new ObjectName("org.aludratest.cloud:00=selenium,type=SeleniumProxyServer"));
		}
		catch (Throwable t) {
			// ignore
		}
		mbs.registerMBean(this, new ObjectName("org.aludratest.cloud:00=selenium,type=SeleniumProxyServer"));
	}

	@Override
	public void shutdown() throws Exception {
		healthCheckDelegator.shutdown();
		super.shutdown();
		healthCheckDelegator.awaitTermination(10, TimeUnit.SECONDS);

		MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
		mbs.unregisterMBean(new ObjectName("org.aludratest.cloud:00=selenium,type=SeleniumProxyServer"));
	}

	/**
	 * Reconfigures the proxy server's parameters. Does not reflect changes in port value, as this would require a server restart.
	 * Use {@link #restartJetty(int)} for restarting Jetty, which throws e.g. an exception when the port is blocked.
	 * 
	 * @param configuration
	 *            New configuration for this proxy server.
	 */
	public void reconfigure(SeleniumModuleConfiguration configuration) {
		this.configuration = configuration;
		super.reconfigure(configuration.getMaxProxyThreads(), configuration.getMaxProxyQueueSize());
	}

	@Override
	protected SeleniumHttpProxy createProxy(int id, SeleniumResourceImpl resource, String path, String accessUrl) {
		SeleniumHttpProxy proxy = SeleniumHttpProxy.create(id, resource, path, configuration.getSeleniumTimeoutSeconds() * 1000l,
				configuration.getMaxIdleTimeBetweenCommandsSeconds() * 1000l, accessUrl, healthCheckDelegator);

		return proxy;
	}

	@Override
	public SeleniumHttpProxy addProxyForResource(SeleniumResourceImpl resource) throws MalformedURLException {
		SeleniumHttpProxy proxy = super.addProxyForResource(resource);
		updateHealthCheckExecutorSize();
		return proxy;
	}

	@Override
	public void removeProxy(SeleniumHttpProxy proxy) {
		super.removeProxy(proxy);
		updateHealthCheckExecutorSize();
	}

	@Override
	protected SeleniumHttpProxy findExistingProxy(SeleniumResourceImpl resource, Collection<SeleniumHttpProxy> existingProxies) {
		for (SeleniumHttpProxy proxy : existingProxies) {
			if (proxy.getResource().getOriginalUrl().equals(resource.getOriginalUrl())) {
				return proxy;
			}
		}

		return null;
	}

	@Override
	protected void updateProxyAccessUrl(SeleniumHttpProxy proxy, String newAccessUrl) {
		proxy.setAccessUrl(newAccessUrl);
	}

	private ScheduledExecutorService createHealthCheckExecutor(int size) {
		return Executors.newScheduledThreadPool(size, new ThreadFactory() {
			@Override
			public Thread newThread(Runnable r) {
				Thread thread = new Thread(r);
				thread.setName("Selenium Health Check " + thread.getName());
				return thread;
			}
		});
	}

	private void updateHealthCheckExecutorSize() {
		int newHealthSize = Math.max(4, (int) Math.ceil(getAllProxies().size() / 5.0));
		if (newHealthSize != executorSize) {
			healthCheckDelegator.setDelegate(createHealthCheckExecutor(executorSize = newHealthSize));
		}
	}

	@Override
	public int getProxyThreadCount() {
		return getJettyServer().getThreadPool().getThreads();
	}

	// Implement a delegating pattern to be able to change Thread count over time
	private static class DelegatingScheduledExecutorService implements ScheduledExecutorService {

		private volatile ScheduledExecutorService delegate;

		public DelegatingScheduledExecutorService(ScheduledExecutorService delegate) {
			this.delegate = delegate;
		}

		public void setDelegate(ScheduledExecutorService delegate) {
			// store reference to ALWAYS have a valid service in this.delegate
			ScheduledExecutorService oldDelegate = this.delegate;
			this.delegate = delegate;
			oldDelegate.shutdown();
		}

		@Override
		public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
			return delegate.schedule(command, delay, unit);
		}

		@Override
		public void execute(Runnable command) {
			delegate.execute(command);
		}

		@Override
		public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
			return delegate.schedule(callable, delay, unit);
		}

		@Override
		public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
			return delegate.scheduleAtFixedRate(command, initialDelay, period, unit);
		}

		@Override
		public void shutdown() {
			delegate.shutdown();
		}

		@Override
		public List<Runnable> shutdownNow() {
			return delegate.shutdownNow();
		}

		@Override
		public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
			return delegate.scheduleWithFixedDelay(command, initialDelay, delay, unit);
		}

		@Override
		public boolean isShutdown() {
			return delegate.isShutdown();
		}

		@Override
		public boolean isTerminated() {
			return delegate.isTerminated();
		}

		@Override
		public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
			return delegate.awaitTermination(timeout, unit);
		}

		@Override
		public <T> Future<T> submit(Callable<T> task) {
			return delegate.submit(task);
		}

		@Override
		public <T> Future<T> submit(Runnable task, T result) {
			return delegate.submit(task, result);
		}

		@Override
		public Future<?> submit(Runnable task) {
			return delegate.submit(task);
		}

		@Override
		public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
			return delegate.invokeAll(tasks);
		}

		@Override
		public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
				throws InterruptedException {
			return delegate.invokeAll(tasks, timeout, unit);
		}

		@Override
		public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
			return delegate.invokeAny(tasks);
		}

		@Override
		public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException,
				ExecutionException, TimeoutException {
			return delegate.invokeAny(tasks, timeout, unit);
		}

	}

}
