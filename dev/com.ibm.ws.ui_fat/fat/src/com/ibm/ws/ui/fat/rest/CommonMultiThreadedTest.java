/*
 * IBM Confidential
 *
 * OCO Source Materials
 *
 * WLP Copyright IBM Corp. 2022
 *
 * The source code for this program is not published or otherwise divested 
 * of its trade secrets, irrespective of what has been deposited with the 
 * U.S. Copyright Office.
 */
package com.ibm.ws.ui.fat.rest;

import static org.junit.Assert.assertNull;

import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.junit.After;
import org.junit.Before;

/**
 * Extends {@link CommonRESTTest} to provide convience methods for multi-threaded
 * testing.
 */
public class CommonMultiThreadedTest extends CommonRESTTest {
    protected int THREAD_LOOP_COUNT = 100;
    protected static volatile Throwable inThreadThrowable = null;

    /**
     * Initialize the multi-threaded test based on {@link CommonRESTTest}.
     * 
     * @param c The implementing class
     */
    protected CommonMultiThreadedTest(Class<?> c) {
        super(c);
    }

    /**
     * Define a LatchedRunnable, in which the latch is setable.
     */
    protected abstract class LatchedRunnable implements Runnable {
        protected CountDownLatch latch = null;

        void setCountDownLatch(CountDownLatch latch) {
            this.latch = latch;
        }
    }

    /**
     * Set the static thread Throwable detector to null.
     */
    @Before
    public void setUp() {
        inThreadThrowable = null;
    }

    /**
     * Set the static thread Throwable detector to null.
     */
    @Override
    @After
    public void tearDown() {
        if (inThreadThrowable != null) {
            assertNull("FAIL: inThreadThrowable should be null, but wasn't: " + inThreadThrowable.getMessage(),
                       inThreadThrowable);
        }
    }

    /**
     * Spawn all of the threads in the list and wait for them all to finish.
     * 
     * @param threads
     * @throws InterruptedException
     */
    protected void spawnThreads(final List<LatchedRunnable> threads) throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(threads.size());

        // Set the latch
        for (LatchedRunnable thread : threads) {
            thread.setCountDownLatch(latch);
        }

        // Spawn the threads
        for (Runnable thread : threads) {
            thread.run();
        }

        // Wait for all threads to finish
        latch.await();
    }

}
