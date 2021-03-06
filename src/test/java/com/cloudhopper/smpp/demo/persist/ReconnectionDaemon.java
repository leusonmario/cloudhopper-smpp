package com.cloudhopper.smpp.demo.persist;

/*
 * #%L
 * ch-smpp
 * %%
 * Copyright (C) 2009 - 2014 Cloudhopper by Twitter
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** #schedule cannot spawn more threads than corePoolSize, so the blocking work is done by separate executor */
public class ReconnectionDaemon {

	private static final Logger log = LoggerFactory.getLogger(ReconnectionDaemon.class);

	private static final ReconnectionDaemon RECONNECTION_DAEMON = new ReconnectionDaemon("1,5,15");
	private static final long KEEP_ALIVE_TIME = 60L;

	private final String[] reconnectionPeriods;

	private final ThreadPoolExecutor executor;
	private final ScheduledExecutorService scheduledExecutorService;

	public ReconnectionDaemon(String reconnectionPeriods) {
		this.reconnectionPeriods = reconnectionPeriods.split(",");
		scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(getThreadFactory("ReconnectionSchedulerDaemon-"));

		executor = new ThreadPoolExecutor(0, Integer.MAX_VALUE, KEEP_ALIVE_TIME, TimeUnit.SECONDS,
				new SynchronousQueue<Runnable>(), getThreadFactory("ReconnectionExecutorDaemon-"));
	}

	private ThreadFactory getThreadFactory(final String name) {
		return new ThreadFactory() {

			private AtomicInteger sequence = new AtomicInteger(0);

			@Override
			public Thread newThread(Runnable r) {
				Thread t = new Thread(r);
				t.setName(name + sequence.getAndIncrement());
				return t;
			}
		};
	}

	public static ReconnectionDaemon getInstance() {
		return RECONNECTION_DAEMON;
	}

	public void executeReconnect(ReconnectionTask reconnectionTask) {
		executor.execute(reconnectionTask);
	}

	public void scheduleReconnectByFailureNumber(OutboundClient outboundClient, Integer failureCount,
			ReconnectionTask reconnectionTask) {
		String reconnectionPeriod = getReconnectionPeriod(failureCount);
		log.info("Scheduling reconnect for {} in {} seconds", outboundClient, reconnectionPeriod);
		scheduledExecutorService.schedule(new ScheduledTask(reconnectionTask), Long.parseLong(reconnectionPeriod),
				TimeUnit.SECONDS);

	}

	protected String getReconnectionPeriod(Integer failureCount) {
		String reconnectionPeriod;
		if (reconnectionPeriods.length > failureCount) {
			reconnectionPeriod = reconnectionPeriods[failureCount];
		} else {
			reconnectionPeriod = reconnectionPeriods[reconnectionPeriods.length - 1];
		}
		return reconnectionPeriod;
	}

	@PreDestroy
	public void shutdown() {
		executor.shutdown();
		scheduledExecutorService.shutdown();
	}

	private class ScheduledTask implements Runnable {

		private final Runnable task;

		public ScheduledTask(Runnable task) {
			this.task = task;
		}

		@Override
		public void run() {
			executor.execute(task);
		}
	}
}
