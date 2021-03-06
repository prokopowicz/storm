/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package backtype.storm.utils;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.InsufficientCapacityException;
import com.lmax.disruptor.MultiThreadedClaimStrategy;
import org.junit.Assert;
import org.junit.Test;
import junit.framework.TestCase;

public class DisruptorQueueTest extends TestCase {

    private final static int TIMEOUT = 1000; // MS
    private final static int PRODUCER_NUM = 4;

    @Test
    public void testFirstMessageFirst() throws InterruptedException {
      for (int i = 0; i < 100; i++) {
        DisruptorQueue queue = createQueue("firstMessageOrder", 16);

        queue.publish("FIRST");

        Runnable producer = new Producer(queue, String.valueOf(i));

        final AtomicReference<Object> result = new AtomicReference<Object>();
        Runnable consumer = new Consumer(queue, new EventHandler<Object>() {
            private boolean head = true;

            @Override
            public void onEvent(Object obj, long sequence, boolean endOfBatch)
                    throws Exception {
                if (head) {
                    head = false;
                    result.set(obj);
                }
            }
        });

        run(producer, consumer);
        Assert.assertEquals("We expect to receive first published message first, but received " + result.get(),
                "FIRST", result.get());
      }
    }
   
    @Test 
    public void testConsumerHang() throws InterruptedException {
        final AtomicBoolean messageConsumed = new AtomicBoolean(false);

        // Set queue length to 1, so that the RingBuffer can be easily full
        // to trigger consumer blocking
        DisruptorQueue queue = createQueue("consumerHang", 1);
        Runnable producer = new Producer(queue, "msg");
        Runnable consumer = new Consumer(queue, new EventHandler<Object>() {
            @Override
            public void onEvent(Object obj, long sequence, boolean endOfBatch)
                    throws Exception {
                messageConsumed.set(true);
            }
        });

        run(producer, consumer);
        Assert.assertTrue("disruptor message is never consumed due to consumer thread hangs",
                messageConsumed.get());
    }


    private void run(Runnable producer, Runnable consumer)
            throws InterruptedException {

        Thread[] producerThreads = new Thread[PRODUCER_NUM];
        for (int i = 0; i < PRODUCER_NUM; i++) {
            producerThreads[i] = new Thread(producer);
            producerThreads[i].start();
        }
        
        Thread consumerThread = new Thread(consumer);
        consumerThread.start();
        Thread.sleep(10);
        for (int i = 0; i < PRODUCER_NUM; i++) {
            producerThreads[i].interrupt();
        }
        consumerThread.interrupt();
        
        for (int i = 0; i < PRODUCER_NUM; i++) {
            producerThreads[i].join(TIMEOUT);
            assertFalse("producer "+i+" is still alive", producerThreads[i].isAlive());
        }
        consumerThread.join(TIMEOUT);
        assertFalse("consumer is still alive", consumerThread.isAlive());
    }

    private static class Producer implements Runnable {
        private String msg;
        private DisruptorQueue queue;

        Producer(DisruptorQueue queue, String msg) {
            this.msg = msg;
            this.queue = queue;
        }

        @Override
        public void run() {
            try {
                while (true) {
                    queue.publish(msg, false);
                }
            } catch (InsufficientCapacityException e) {
                return;
            }
        }
    };

    private static class Consumer implements Runnable {
        private EventHandler handler;
        private DisruptorQueue queue;

        Consumer(DisruptorQueue queue, EventHandler handler) {
            this.handler = handler;
            this.queue = queue;
        }

        @Override
        public void run() {
            queue.consumerStarted();
            try {
                while(true) {
                    queue.consumeBatchWhenAvailable(handler);
                }
            }catch(RuntimeException e) {
                //break
            }
        }
    };

    private static DisruptorQueue createQueue(String name, int queueSize) {
        return new DisruptorQueue(name, new MultiThreadedClaimStrategy(
                queueSize), new BlockingWaitStrategy(), 10L);
    }
}
