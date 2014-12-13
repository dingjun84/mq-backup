/**
 * Copyright (C) 2010-2013 Alibaba Group Holding Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.rocketmq.test.integration.benchmark;

import java.util.LinkedList;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.alibaba.rocketmq.client.exception.MQBrokerException;
import com.alibaba.rocketmq.client.exception.MQClientException;
import com.alibaba.rocketmq.client.producer.DefaultMQProducer;
import com.alibaba.rocketmq.common.message.Message;
import com.alibaba.rocketmq.remoting.exception.RemotingException;


/**
 * 性能测试，多线程多个 producer 同步发送消息
 * 
 * @author: manhong.yqd<jodie.yqd@gmail.com>
 * @since: 13-9-5
 */
public class ManyProducerTest extends BenchmarkBaseTest {

    public static void main(String[] args) {
        final int threadCount = args.length >= 1 ? Integer.parseInt(args[0]) : 300;
        final int messageSize = args.length >= 2 ? Integer.parseInt(args[1]) : 256;

        System.out.printf("threadCount %d messageSize %d\n", threadCount, messageSize);

        final Message msg = buildMessage(messageSize);

        final ExecutorService sendThreadPool = Executors.newFixedThreadPool(threadCount);

        final StatsBenchmarkProducer statsBenchmark = new StatsBenchmarkProducer();

        final Timer timer = new Timer("BenchmarkTimerThread", true);

        final LinkedList<Long[]> snapshotList = new LinkedList<Long[]>();

        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                snapshotList.addLast(statsBenchmark.createSnapshot());
                if (snapshotList.size() > 10) {
                    snapshotList.removeFirst();
                }
            }
        }, 1000, 1000);

        timer.scheduleAtFixedRate(new TimerTask() {
            private void printStats() {
                if (snapshotList.size() >= 10) {
                    Long[] begin = snapshotList.getFirst();
                    Long[] end = snapshotList.getLast();

                    final long sendTps =
                            (long) (((end[3] - begin[3]) / (double) (end[0] - begin[0])) * 1000L);
                    final double averageRT = ((end[5] - begin[5]) / (double) (end[3] - begin[3]));

                    System.out.printf(
                        "Send TPS: %d Max RT: %d Average RT: %7.3f Send Failed: %d Response Failed: %d\n"//
                        , sendTps//
                        , statsBenchmark.getSendMessageMaxRT().get()//
                        , averageRT//
                        , end[2]//
                        , end[4]//
                        );
                }
            }


            @Override
            public void run() {
                try {
                    this.printStats();
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, 10000, 10000);

        final String instanceName = "ins." + System.currentTimeMillis();
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            sendThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        final DefaultMQProducer producer =
                                new DefaultMQProducer("benchmark_producer" + index);
                        producer.setCompressMsgBodyOverHowmuch(Integer.MAX_VALUE);
                        producer.setInstanceName(instanceName);
                        producer.start();

                        while (true) {
                            final long beginTimestamp = System.currentTimeMillis();
                            producer.send(msg);
                            statsBenchmark.getSendRequestSuccessCount().incrementAndGet();
                            statsBenchmark.getReceiveResponseSuccessCount().incrementAndGet();
                            final long currentRT = System.currentTimeMillis() - beginTimestamp;
                            statsBenchmark.getSendMessageSuccessTimeTotal().addAndGet(currentRT);
                            long prevMaxRT = statsBenchmark.getSendMessageMaxRT().get();
                            while (currentRT > prevMaxRT) {
                                boolean updated =
                                        statsBenchmark.getSendMessageMaxRT().compareAndSet(prevMaxRT,
                                            currentRT);
                                if (updated)
                                    break;

                                prevMaxRT = statsBenchmark.getSendMessageMaxRT().get();
                            }
                        }
                    }
                    catch (RemotingException e) {
                        statsBenchmark.getSendRequestFailedCount().incrementAndGet();
                        e.printStackTrace();
                    }
                    catch (InterruptedException e) {
                        statsBenchmark.getSendRequestFailedCount().incrementAndGet();
                        e.printStackTrace();
                    }
                    catch (MQClientException e) {
                        statsBenchmark.getSendRequestFailedCount().incrementAndGet();
                        e.printStackTrace();
                    }
                    catch (MQBrokerException e) {
                        statsBenchmark.getReceiveResponseFailedCount().incrementAndGet();
                        e.printStackTrace();
                    }
                }
            });
        }
    }
}
