/*******************************************************************************
 * Copyright (c) 2003, 2013 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.osgi.internal.serviceregistry;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.eclipse.osgi.internal.debug.Debug;
import org.eclipse.osgi.internal.framework.BundleContextImpl;
import org.eclipse.osgi.internal.messages.Msg;
import org.eclipse.osgi.util.NLS;
import org.osgi.framework.ServiceException;

/**
 * This class represents the use of a service by a bundle. One is created for each
 * service acquired by a bundle.
 *
 * <p>
 * This class manages a singleton service.
 *
 * @ThreadSafe
 */
public class ServiceUse<S> {

	/**
	 * Custom ServiceException type to indicate a deadlock occurred during service
	 * registration.
	 */
	public static final int DEADLOCK = 1001;

	/** BundleContext associated with this service use */
	final BundleContextImpl context;
	/** ServiceDescription of the registered service */
	final ServiceRegistrationImpl<S> registration;
	final Debug debug;

	/** bundle's use count for this service */
	/* @GuardedBy("this") */
	private int useCount;

	/**
	 * ReentrantLock for this service. Use the @{@link #getLock()} method to obtain
	 * the lock.
	 */
	private final ServiceUseLock lock = new ServiceUseLock();

	/**
	 * Constructs a service use encapsulating the service object.
	 *
	 * @param   context bundle getting the service
	 * @param   registration ServiceRegistration of the service
	 */
	ServiceUse(BundleContextImpl context, ServiceRegistrationImpl<S> registration) {
		this.useCount = 0;
		this.registration = registration;
		this.context = context;
		this.debug = context.getContainer().getConfiguration().getDebug();
	}

	/**
	 * Get a service's service object and increment the use count.
	 *
	 * @return The service object.
	 */
	/* @GuardedBy("this") */
	S getService() {
		assert lock.isHeldByCurrentThread();
		if (debug.DEBUG_SERVICES) {
			Debug.println('[' + Thread.currentThread().getName() + "] getService[factory=" + registration.getBundle() //$NON-NLS-1$
					+ "](" + context.getBundleImpl() + "," + registration + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}

		incrementUse();
		return registration.getServiceObject();
	}

	/**
	 * Unget a service's service object.
	 *
	 * <p>
	 * Decrements the use count if the service was being used.
	 *
	 * @return true if the service was ungotten; otherwise false.
	 */
	/* @GuardedBy("this") */
	boolean ungetService() {
		assert lock.isHeldByCurrentThread();
		if (!inUse()) {
			return false;
		}
		if (debug.DEBUG_SERVICES) {
			Debug.println('[' + Thread.currentThread().getName() + "] ungetService[factory=" + registration.getBundle() //$NON-NLS-1$
					+ "](" + context.getBundleImpl() + "," + registration + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
		decrementUse();
		return true;
	}

	/**
	 * Return the service object for this service use.
	 *
	 * @return The service object.
	 */
	/* @GuardedBy("this") */
	S getCachedService() {
		return registration.getServiceObject();
	}

	/**
	 * Get a new service object for the service.
	 *
	 * <p>
	 * By default, this returns the result of {@link #getService()}.
	 *
	 * @return The service object.
	 */
	/* @GuardedBy("this") */
	S newServiceObject() {
		return getService();
	}

	/**
	 * Release a service object for the service.
	 *
	 * <p>
	 * By default, this returns the result of {@link #ungetService()}.
	 *
	 * @param service The service object to release.
	 * @return true if the service was released; otherwise false.
	 * @throws IllegalArgumentException If the specified service was not
	 *         provided by this object.
	 */
	/* @GuardedBy("this") */
	boolean releaseServiceObject(final S service) {
		if ((service == null) || (service != getCachedService())) {
			throw new IllegalArgumentException(Msg.SERVICE_OBJECTS_UNGET_ARGUMENT_EXCEPTION);
		}
		if (debug.DEBUG_SERVICES) {
			Debug.println('[' + Thread.currentThread().getName() + "] releaseServiceObject[factory=" //$NON-NLS-1$
					+ registration.getBundle() + "](" + context.getBundleImpl() + "," + registration + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
		return ungetService();
	}

	/**
	 * Release all uses of the service and reset the use count to zero.
	 */
	/* @GuardedBy("this") */
	void release() {
		assert lock.isHeldByCurrentThread();
		resetUse();
	}

	/**
	 * Is this service use using any services?
	 *
	 * @return true if no services are being used and this service use can be discarded.
	 */
	/* @GuardedBy("this") */
	boolean isEmpty() {
		assert lock.isHeldByCurrentThread();
		return !inUse();
	}

	/**
	 * Is the use count non zero?
	 *
	 * @return true if the use count is greater than zero.
	 */
	/* @GuardedBy("this") */
	boolean inUse() {
		return useCount > 0;
	}

	/**
	 * Incrementing the use count.
	 */
	/* @GuardedBy("this") */
	void incrementUse() {
		if (useCount == Integer.MAX_VALUE) {
			throw new ServiceException(Msg.SERVICE_USE_OVERFLOW);
		}
		useCount++;
	}

	/**
	 * Decrementing the use count.
	 */
	/* @GuardedBy("this") */
	void decrementUse() {
		assert inUse();
		useCount--;
	}

	/**
	 * Reset the use count to zero.
	 */
	/* @GuardedBy("this") */
	void resetUse() {
		useCount = 0;
	}

	private static final ConcurrentMap<Thread, ServiceUseLock> AWAITED_LOCKS = new ConcurrentHashMap<>();

	/**
	 * Acquires the lock of this ServiceUse.
	 * 
	 * If this ServiceUse is locked by another thread then the current thread lies
	 * dormant until the lock has been acquired.
	 * 
	 * @return The {@link AutoCloseable autoclosable} locked state of this
	 *         ServiceUse
	 * @throws ServiceException if a deadlock with another ServiceUse is detected
	 */
	ServiceUseLock lock() {
		boolean clearAwaitingLock = false;
		boolean interrupted = false;
		do {
			try {
				if (lock.tryLock(100_000_000L, TimeUnit.NANOSECONDS)) { // 100ms (but prevent conversion)
					if (clearAwaitingLock) {
						AWAITED_LOCKS.remove(Thread.currentThread());
					}
					if (interrupted) {
						Thread.currentThread().interrupt();
					}
					return lock;
				}
				AWAITED_LOCKS.put(Thread.currentThread(), lock);
				clearAwaitingLock = true;
				Set<Lock> lockCycle = getLockCycle(lock);
				if (lockCycle.contains(lock)) {
					throw new ServiceException(NLS.bind(Msg.SERVICE_USE_DEADLOCK, lock));
				}
				// Not (yet) a dead-lock. Lock was regularly hold by another thread. Try again.
				// Race conditions are not an issue here. A deadlock is a static situation and
				// if we closely missed the other thread putting its awaited lock it will be
				// noticed in the next loop-pass.
			} catch (InterruptedException e) {
				interrupted = true;
				// Clear interrupted status and try again to lock, just like a plain
				// synchronized. Re-interrupted before returning to the caller.
			}
		} while (true);
	}

	private static Set<Lock> getLockCycle(ServiceUseLock lock) {
		Set<Lock> encounteredLocks = new HashSet<>();
		Deque<Lock> lockPath = new ArrayDeque<>();
		while (encounteredLocks.add(lock)) {
			lockPath.addLast(lock);
			Thread owner = lock.getOwner();
			if (owner == null || (lock = AWAITED_LOCKS.get(owner)) == null) {
				return Collections.emptySet(); // lock could be released in the meantime
			}
		}
		// Found a cycle, remove all locks from the path that are not in the cycle
		for (Lock l : lockPath) {
			if (l == lock) {
				return encounteredLocks;
			}
			encounteredLocks.remove(l);
		}
		throw new IllegalStateException(); // Cannot happen
	}

	/**
	 * ReentrantLock subclass with exposed {@link #getOwner()} that implements
	 * {@link AutoCloseable}.
	 * 
	 * This lock is unlocked if the close method is invoked. It therefore can be
	 * used as resource of a try-with-resources block.
	 */
	static class ServiceUseLock extends ReentrantLock implements AutoCloseable {
		private static final long serialVersionUID = 4281308691512232595L;

		@Override
		protected Thread getOwner() {
			return super.getOwner();
		}

		/** Close and unlock this lock. */
		@Override
		public void close() {
			unlock();
		}

		/**
		 * Returns a string identifying this lock, as well as its lock state. The state,
		 * in brackets, includes either the String {@code "Unlocked"} or the String
		 * {@code "Locked by"} followed by the {@linkplain Thread#getName name} of the
		 * owning thread.
		 *
		 * @return a string identifying this lock, as well as its lock state
		 */
		@SuppressWarnings("nls")
		@Override
		public String toString() {
			Thread o = getOwner();

			if (o != null) {
				try {
					ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
					ThreadInfo threadInfo = threadMXBean.getThreadInfo(o.getId(), Integer.MAX_VALUE);
					StackTraceElement[] trace = threadInfo.getStackTrace();
					StringBuilder sb = new StringBuilder("\"" + o.getName() + "\"" + (o.isDaemon() ? " daemon" : "")
							+ " prio=" + o.getPriority() + " Id=" + o.getId() + " " + o.getState());

					for (StackTraceElement traceElement : trace)
						sb.append("\tat " + traceElement + "\n");

					return super.toString() + "[Locked by thread " + o.getName() + "], Details:\n" + sb.toString();
				} catch (Exception e) {
					// do nothing and fall back to just the default, thread might be gone
				}
			}
			return super.toString() + "[Unlocked]";
		}
	}
}
