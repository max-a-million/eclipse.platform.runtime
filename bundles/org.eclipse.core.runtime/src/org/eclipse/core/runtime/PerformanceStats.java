/*******************************************************************************
 * Copyright (c) 2000, 2005 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.core.runtime;

import java.io.PrintWriter;
import java.util.*;
import org.eclipse.core.internal.runtime.*;

/**
 * PerformanceStats collects and aggregates timing data about events such as
 * a builder running, an editor opening, etc.  This data is collected for the 
 * purpose of performance analysis, and is not intended to be used as
 * a generic event tracking and notification system.
 * <p>
 * Each performance event can have an associated maximum acceptable
 * duration that is specified in the platform debug options file (.options).
 * Events that take longer than this maximum are logged as errors.  Along
 * with option file entries for each debug event, there are some global debug
 * options for enabling or disabling performance event gathering and reporting.
 * See the "org.eclipse.core.runtime/perf*" debug options in the .options file
 * for the org.eclipse.core.runtime plugin for more details.
 * <p>
 * A performance event can optionally have additional context information
 * (<code>getContext</code>).  This information is only stored in the case
 * of a performance failure, and can be used to provide further diagnostic 
 * information that can help track down the cause of the failure.
 * <p>
 * Performance events and performance failures are batched up and periodically
 * sent to interested performance event listeners.
 * </p>
 * This class is not intended to be subclassed or instantiated by clients.
 * </p>
 * @since 3.1
 */
public class PerformanceStats {
	/**
	 * A performance listener is periodically notified after performance events occur 
	 * or after events fail.
	 * <p>
	 * This class is intended to be subclassed.
	 * 
	 * @see PerformanceStats#addListener(PerformanceListener)
	 */
	public static abstract class PerformanceListener {
		/**
		 * Creates a new listener.
		 */
		protected PerformanceListener() {
			super();
		}

		/**
		 * Notifies than an event exceeded the maximum duration for that event type.
		 * <p>
		 * This default implementation does nothing. Subclasses may override.
		 * 
		 * @param event The event that failed
		 * @param duration The duration of the failed event, in milliseconds
		 */
		public void eventFailed(PerformanceStats event, long duration) {
			//default implementation does nothing
		}

		/**
		 * Notifies that an event occurred.  Notification might not occur
		 * in the same thread or near the time of the actual event.
		 * <p>
		 * This default implementation does nothing. Subclasses may override.
		 * 
		 * @param event The event that occurred
		 */
		public void eventsOccurred(PerformanceStats[] event) {
			//default implementation does nothing
		}
	}

	/**
	 * An empty stats object that is returned when tracing is turned off
	 */
	private static final PerformanceStats EMPTY_STATS = new PerformanceStats("", ""); //$NON-NLS-1$ //$NON-NLS-2$

	/**
	 * Constant indicating whether or not tracing is enabled
	 */
	public static final boolean ENABLED;

	/**
	 * All known event statistics.
	 */
	private final static Map statMap = Collections.synchronizedMap(new HashMap());

	/**
	 * Maximum allowed durations for each event.
	 * Maps String (event name) -> Long (threshold)
	 */
	private final static Map thresholdMap = Collections.synchronizedMap(new HashMap());

	/**
	 * More specific tracing constants for tracking success and failure
	 */
	private static final boolean TRACE_FAILURE;
	private static final boolean TRACE_SUCCESS;
	/**
	 * An identifier that can be used to figure out who caused the event. This is 
	 * typically the object whose code was running when the event occurred or
	 * a <code>String</code> describing the event should be supplied
	 */
	private Object blame;

	/**
	 * An optional context for the event (may be <code>null</code>).
	 * The context can provide extra information about an event, such as the
	 * name of a project being built, or the input of an editor being opened.
	 */
	private String context;

	/**
	 * The symbolic name of the event that occurred. This is usually the name of 
	 * the debug option for this event.
	 */
	private String event;

	/**
	 * The total number of times this event has exceeded its maximum duration.
	 */
	private int failureCount = 0;

	/**
	 * The total number of times this event has occurred.
	 */
	private int runCount = 0;

	/**
	 * The total time in milliseconds taken by all occurrences of this event.
	 */
	private long runningTime = 0;

	static {
		ENABLED = InternalPlatform.getDefault().getBooleanOption(Platform.PI_RUNTIME + "/perf", false);//$NON-NLS-1$
		//turn these on by default if the global trace flag is turned on
		TRACE_FAILURE = InternalPlatform.getDefault().getBooleanOption(Platform.PI_RUNTIME + "/perf/failure", ENABLED); //$NON-NLS-1$
		TRACE_SUCCESS = InternalPlatform.getDefault().getBooleanOption(Platform.PI_RUNTIME + "/perf/success", ENABLED); //$NON-NLS-1$
	}

	/**
	 * Adds a listener that is notified when performance events occur.  If
	 * an equal listener is already installed, it will be replaced.
	 * 
	 * @param listener The listener to be added
	 * @see #removeListener(PerformanceListener)
	 */
	public static void addListener(PerformanceListener listener) {
		if (ENABLED)
			PerformanceStatsProcessor.addListener(listener);
	}

	/**
	 * Discards all known performance event statistics.
	 */
	public static void clear() {
		statMap.clear();
	}

	/**
	 * Returns all performance event statistics.
	 * 
	 * @return An array of known performance event statistics.  The array
	 * will be empty if there are no recorded statistics.
	 */
	public static PerformanceStats[] getAllStats() {
		return (PerformanceStats[]) statMap.values().toArray(new PerformanceStats[statMap.values().size()]);
	}

	/**
	 * Returns the stats object corresponding to the given parameters.  
	 * A stats object is created and added to the global list of events if it did not 
	 * already exist.
	 * 
	 * @param eventName A symbolic event name.  This is usually the name of 
	 * the debug option for this event. An example event name from
	 * the org.eclipse.core.resources plugin describing a build event might look like:
	 * 		<code>"org.eclipse.core.resources/perf/building"</code>"
	 * @param blameObject The blame for the event.  This is typically the object 
	 * whose code was running when the event occurred.  If a blame object cannot 
	 * be obtained, a <code>String</code> describing the event should be supplied
	 */
	public static PerformanceStats getStats(String eventName, Object blameObject) {
		if (!ENABLED)
			return EMPTY_STATS;
		Assert.isNotNull(eventName);
		Assert.isNotNull(blameObject);
		PerformanceStats newStats = new PerformanceStats(eventName, blameObject);
		//use existing stats object if available
		PerformanceStats oldStats = (PerformanceStats) statMap.get(newStats);
		if (oldStats != null)
			return oldStats;
		statMap.put(newStats, newStats);
		return newStats;
	}

	/**
	 * Prints all statistics to the standard output.
	 */
	public static void printStats() {
		if (!ENABLED)
			return;
		PrintWriter writer = new PrintWriter(System.out);
		PerformanceStatsProcessor.printStats(writer);
		writer.flush();
		writer.close();
	}

	/**
	 * Writes all statistics using the provided writer
	 * 
	 * @param out The writer to print stats to.
	 */
	public static void printStats(PrintWriter out) {
		if (!ENABLED)
			return;
		PerformanceStatsProcessor.printStats(out);
	}

	/**
	 * Removes an event listener. Has no effect if an equal
	 * listener object is not currently registered.
	 * 
	 * @param listener The listener to remove
	 * @see #addListener(PerformanceListener)
	 */
	public static void removeListener(PerformanceListener listener) {
		if (ENABLED)
			PerformanceStatsProcessor.removeListener(listener);
	}

	/**
	 * Removes statistics for a given event and blame
	 * 
	 * @param eventName The name of the event to remove
	 * @param blameObject The blame for the event to remove
	 */
	public static void removeStats(String eventName, Object blameObject) {
		synchronized (statMap) {
			for (Iterator it = statMap.keySet().iterator(); it.hasNext();) {
				PerformanceStats stats = (PerformanceStats) it.next();
				if (stats.getEvent().equals(eventName) && stats.getBlame().equals(blameObject))
					it.remove();
			}
		}
	}

	/** 
	 * Creates a new PerformanceStats object.  Private to prevent client instantiation.
	 */
	private PerformanceStats(String event, Object blame) {
		this(event, blame, null);
	}

	/** 
	 * Creates a new PerformanceStats object.  Private to prevent client instantiation.
	 */
	private PerformanceStats(String event, Object blame, String context) {
		this.event = event;
		this.blame = blame;
		this.context = context;
	}

	/**
	 * Adds an occurence of this event to the cumulative counters.
	 * @param elapsed The elapsed time of the new occurrence in milliseconds
	 * @param contextName The context for the event to return, or <code>null</code>.
	 * The context optionally provides extra information about an event, such as the
	 * name of a project being built, or the input of an editor being opened.
	 */
	public void addRun(long elapsed, String contextName) {
		if (!ENABLED)
			return;
		runCount++;
		runningTime += elapsed;
		if (elapsed > getThreshold(event) && TRACE_FAILURE) {
			//create a failure event object
			PerformanceStats failure = createFailureStats(contextName, elapsed);
			PerformanceStatsProcessor.failed(failure, elapsed);
		}
		if (TRACE_SUCCESS)
			PerformanceStatsProcessor.changed(this);
	}

	/**
	 * Creates a stats object representing a performance failure
	 * 
	 * @param contextName The failure context information.
	 * @param elapsed The elapsed time in milliseconds
	 * @return The failure stats
	 */
	private PerformanceStats createFailureStats(String contextName, long elapsed) {
		PerformanceStats failure = new PerformanceStats(event, blame, contextName);
		PerformanceStats old = (PerformanceStats) statMap.get(failure);
		if (old == null)
			statMap.put(failure, failure);
		else 
			failure = old;
		failure.failureCount++;
		failure.runCount++;
		failure.runningTime += elapsed;
		return failure;
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#equals()
	 */
	public boolean equals(Object obj) {
		//count and time are not considered part of equality
		if (!(obj instanceof PerformanceStats))
			return false;
		PerformanceStats that = (PerformanceStats) obj;
		if (!this.event.equals(that.event))
			return false;
		if (!this.getBlameString().equals(that.getBlameString()))
			return false;
		return this.context == null ? that.context == null : this.context.equals(that.context);
	}

	/**
	 * Returns an object that can be used to figure out who caused the event,
	 * or a string describing the cause of the event.
	 * 
	 * @return The blame for this event
	 */
	public Object getBlame() {
		return blame;
	}

	/**
	 * Returns a string describing the blame for this event.
	 * 
	 * @return A string describing the blame.
	 */
	public String getBlameString() {
		if (blame instanceof String)
			return (String) blame;
		//use class name rather than toString to ensure accurate reporting
		return blame.getClass().getName();
	}

	/**
	 * Returns the optional event context, such as the input of an editor, or the target project
	 * 
	 * of a build event.
	 * @return The context, or <code>null</code> if there is none
	 */
	public String getContext() {
		return context;
	}

	/**
	 * Returns the symbolic name of the event that occurred.
	 * 
	 * @return The name of the event.
	 */
	public String getEvent() {
		return event;
	}

	/**
	 * Returns the number of times this event has exceeded its
	 * maximum duration.
	 * 
	 * @return The number of times this event has exceeded its
	 * maximum duration.
	 */
	public int getFailureCount() {
		return failureCount;
	}

	/**
	 * Returns the total number of times this event has occurred.
	 * 
	 * @return The number of occurrences of this event.
	 */
	public int getRunCount() {
		return runCount;
	}

	/**
	 * Returns the total execution time in milliseconds for all occurrences
	 * of this event.
	 * 
	 * @return The total running time in milliseconds.
	 */
	public long getRunningTime() {
		return runningTime;
	}

	/**
	 * Returns the performance threshold for this event.
	 */
	private long getThreshold(String eventName) {
		Long value = (Long) thresholdMap.get(eventName);
		if (value == null) {
			String option = InternalPlatform.getDefault().getOption(eventName);
			if (option != null) {
				try {
					value = new Long(option);
				} catch (NumberFormatException e) {
					//invalid option, just ignore
				}
			}
			if (value == null)
				value = new Long(Long.MAX_VALUE);
			thresholdMap.put(eventName, value);
		}
		return value.longValue();
	}

	public int hashCode() {
		//count and time are not considered part of equality
		int hash = event.hashCode() * 37 + getBlameString().hashCode();
		if (context != null)
			hash = hash * 37 + context.hashCode();
		return hash;
	}

	/**
	 * Resets count and running time for this particular stats event.
	 */
	public void reset() {
		runningTime = 0;
		runCount = 0;
	}
	
	/**
	 * For debugging purposes only.
	 */
	public String toString() {
		StringBuffer result = new StringBuffer("PerformanceStats("); //$NON-NLS-1$
		result.append(event);
		result.append(',');
		result.append(blame);
		if (context != null) {
			result.append(',');
			result.append(context);
		}
		result.append(')');
		return result.toString();
	}
}