/*
 * AppInsights-Java
 * Copyright (c) Microsoft Corporation
 * All rights reserved.
 *
 * MIT License
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software and associated documentation files (the ""Software""), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify, merge,
 * publish, distribute, sublicense, and/or sell copies of the Software, and to permit
 * persons to whom the Software is furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED *AS IS*, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
 * PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE
 * FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */

package com.microsoft.applicationinsights.internal.channel.common;

import java.util.Calendar;
import java.util.Date;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.microsoft.applicationinsights.internal.logger.InternalLogger;
import com.microsoft.applicationinsights.internal.shutdown.SDKShutdownActivity;
import com.microsoft.applicationinsights.internal.shutdown.Stoppable;
import com.microsoft.applicationinsights.internal.util.ThreadPoolUtils;

import com.google.common.base.Preconditions;

/**
 * This class is responsible for managing the transmission state.
 *
 * The class might be told to suspend transmission for the next 'X' seconds
 * where the state is set to be one of the states defined in {@link com.microsoft.applicationinsights.internal.channel.common.TransmissionPolicy}
 *
 * The class will keep that state for the requested amount of time and will release it, i.e. reset to 'unblock'
 * when the timeout expires.
 *
 * Created by gupele on 6/29/2015.
 */
public final class TransmissionPolicyManager implements Stoppable {

    // The future date the the transmission is blocked
    private Date suspensionDate;

    // Make sure that we don't double block, we do that by keeping un up-to-date generation id
    private AtomicLong generation = new AtomicLong(0);

    // A thread that will callback when the timeout expires
    private ScheduledThreadPoolExecutor threads;

    // Keeps the current policy state of the transmission
    private final TransmissionPolicyState policyState = new TransmissionPolicyState();
    private boolean throttlingIsEnabled = true;

    /**
     * The class will be activated when a timeout expires
     */
    private class UnSuspender implements Runnable {
        private final long expectedGeneration;

        private UnSuspender(long expectedGeneration) {
            this.expectedGeneration = expectedGeneration;
        }

        @Override
        public void run() {
            try {
                cancelSuspension(expectedGeneration);
            } catch (Throwable t) {
            }
        }
    }

    public TransmissionPolicyManager(boolean throttlingIsEnabled) {
        suspensionDate = null;
        this.throttlingIsEnabled = throttlingIsEnabled;
    }

    public void suspendInSeconds(TransmissionPolicy policy, long suspendInSeconds) {
        if (!throttlingIsEnabled) {
            return;
        }

        Preconditions.checkArgument(suspendInSeconds > 0, "Suspension must be greater than zero");

        createScheduler();

        doSuspend(policy, suspendInSeconds);
    }

    @Override
    public synchronized void stop(long timeout, TimeUnit timeUnit) {
        ThreadPoolUtils.stop(threads, timeout, timeUnit);
    }

    public TransmissionPolicyStateFetcher getTransmissionPolicyState() {
        return policyState;
    }

    private synchronized void doSuspend(TransmissionPolicy policy, long suspendInSeconds) {
        try {
            if (policy == TransmissionPolicy.UNBLOCKED ) {
                return;
            }

            Date date = Calendar.getInstance().getTime();
            date.setTime(date.getTime() + 1000 * suspendInSeconds);
            if (this.suspensionDate != null) {
                long diff = date.getTime() - suspensionDate.getTime();
                if (diff <= 0) {
                    return;
                }
            }

            long currentGeneration = generation.incrementAndGet();

            threads.schedule(new UnSuspender(currentGeneration), suspendInSeconds, TimeUnit.SECONDS);
            policyState.setCurrentState(policy);
            suspensionDate = date;

            InternalLogger.INSTANCE.logAlways(InternalLogger.LoggingLevel.TRACE, "App is throttled, telemetries are blocked from now, for %s seconds", suspendInSeconds);
        } catch (Throwable t) {
            InternalLogger.INSTANCE.logAlways(InternalLogger.LoggingLevel.ERROR, "App is throttled but failed to block transmission exception: %s", t.getMessage());
        }
    }

    private synchronized void cancelSuspension(long expectedGeneration) {
        if (expectedGeneration != generation.get()) {
            return;
        }

        policyState.setCurrentState(TransmissionPolicy.UNBLOCKED);
        suspensionDate = null;
        InternalLogger.INSTANCE.logAlways(InternalLogger.LoggingLevel.TRACE, "App is throttled is cancelled");
    }

    private synchronized void createScheduler() {
        if (threads != null) {
            return;
        }

        threads = new ScheduledThreadPoolExecutor(1);
        threads.setThreadFactory(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r);
                thread.setDaemon(true);
                return thread;
            }
        });

        SDKShutdownActivity.INSTANCE.register(this);
    }
}
