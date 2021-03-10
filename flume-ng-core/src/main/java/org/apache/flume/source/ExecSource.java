/*
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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.flume.source;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.flume.Channel;
import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.EventDrivenSource;
import org.apache.flume.Source;
import org.apache.flume.SystemClock;
import org.apache.flume.channel.ChannelProcessor;
import org.apache.flume.conf.BatchSizeSupported;
import org.apache.flume.conf.Configurable;
import org.apache.flume.event.EventBuilder;
import org.apache.flume.instrumentation.SourceCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import java.nio.charset.Charset;

/**
 * <p>
 * A {@link Source} implementation that executes a Unix process and turns each
 * line of text into an event.
 * </p>
 * <p>
 * This source runs a given Unix command on start up and expects that process to
 * continuously produce data on standard out (stderr ignored by default). Unless
 * told to restart, if the process exits for any reason, the source also exits and
 * will produce no further data. This means configurations such as <tt>cat [named pipe]</tt>
 * or <tt>tail -F [file]</tt> are going to produce the desired results where as
 * <tt>date</tt> will probably not - the former two commands produce streams of
 * data where as the latter produces a single event and exits.
 * </p>
 * <p>
 * The <tt>ExecSource</tt> is meant for situations where one must integrate with
 * existing systems without modifying code. It is a compatibility gateway built
 * to allow simple, stop-gap integration and doesn't necessarily offer all of
 * the benefits or guarantees of native integration with Flume. If one has the
 * option of using the <tt>AvroSource</tt>, for instance, that would be greatly
 * preferred to this source as it (and similarly implemented sources) can
 * maintain the transactional guarantees that exec can not.
 * </p>
 * <p>
 * <i>Why doesn't <tt>ExecSource</tt> offer transactional guarantees?</i>
 * </p>
 * <p>
 * The problem with <tt>ExecSource</tt> and other asynchronous sources is that
 * the source can not guarantee that if there is a failure to put the event into
 * the {@link Channel} the client knows about it. As a for instance, one of the
 * most commonly requested features is the <tt>tail -F [file]</tt>-like use case
 * where an application writes to a log file on disk and Flume tails the file,
 * sending each line as an event. While this is possible, there's an obvious
 * problem; what happens if the channel fills up and Flume can't send an event?
 * Flume has no way of indicating to the application writing the log file that
 * it needs to retain the log or that the event hasn't been sent, for some
 * reason. If this doesn't make sense, you need only know this: <b>Your
 * application can never guarantee data has been received when using a
 * unidirectional asynchronous interface such as ExecSource!</b> As an extension
 * of this warning - and to be completely clear - there is absolutely zero
 * guarantee of event delivery when using this source. You have been warned.
 * </p>
 * <p>
 * <b>Configuration options</b>
 * </p>
 * <table>
 * <tr>
 * <th>Parameter</th>
 * <th>Description</th>
 * <th>Unit / Type</th>
 * <th>Default</th>
 * </tr>
 * <tr>
 * <td><tt>command</tt></td>
 * <td>The command to execute</td>
 * <td>String</td>
 * <td>none (required)</td>
 * </tr>
 * <tr>
 * <td><tt>restart</tt></td>
 * <td>Whether to restart the command when it exits</td>
 * <td>Boolean</td>
 * <td>false</td>
 * </tr>
 * <tr>
 * <td><tt>restartThrottle</tt></td>
 * <td>How long in milliseconds to wait before restarting the command</td>
 * <td>Long</td>
 * <td>10000</td>
 * </tr>
 * <tr>
 * <td><tt>logStderr</tt></td>
 * <td>Whether to log or discard the standard error stream of the command</td>
 * <td>Boolean</td>
 * <td>false</td>
 * </tr>
 * <tr>
 * <td><tt>batchSize</tt></td>
 * <td>The number of events to commit to channel at a time.</td>
 * <td>integer</td>
 * <td>20</td>
 * </tr>
 * <tr>
 * <td><tt>batchTimeout</tt></td>
 * <td>Amount of time (in milliseconds) to wait, if the buffer size was not reached,
 *     before data is pushed downstream.</td>
 * <td>long</td>
 * <td>3000</td>
 * </tr>
 * </table>
 * <p>
 * <b>Metrics</b>
 * </p>
 * <p>
 * TODO
 * </p>
 */

/**
 * a1.sources = r1
 * a1.channels = c1
 * a1.sources.r1.type = exec
 * a1.sources.r1.command = tail -F /var/log/secure
 * a1.sources.r1.channels = c1
 *
 *
 * a1.sources.tailsource-1.type = exec
 * a1.sources.tailsource-1.shell = /bin/bash -c
 * a1.sources.tailsource-1.command = for i in /path/*.txt; do cat $i; done
 */
public class ExecSource extends AbstractSource implements EventDrivenSource, Configurable,
        BatchSizeSupported {

  private static final Logger logger = LoggerFactory.getLogger(ExecSource.class);

  private String shell;//exec执行的是shell
  private String command;//exec执行的是command
  private SourceCounter sourceCounter;//用于JMX查看的计数器
  private ExecutorService executor;//具体的Exec执行器
  private Future<?> runnerFuture;//Exec执行的结果Future
  private long restartThrottle;//重新执行的间隔
  private boolean restart;//判断是否命令一次执行成功后需要重新执行
  private boolean logStderr;//是否输出stderr
  private Integer bufferCount;//缓冲区数量
  private long batchTimeout;//批量flush的timeout
  private ExecRunnable runner;//具体命令的执行代码
  private Charset charset;//编码

  //重写了父类的start()方法
  @Override
  public void start() {
    logger.info("Exec source starting with command: {}", command);

    // Start the counter before starting any threads that may access it.
    sourceCounter.start();

    //单线程的执行器
    executor = Executors.newSingleThreadExecutor();
    runner = new ExecRunnable(shell, command, getChannelProcessor(), sourceCounter, restart,
                              restartThrottle, logStderr, bufferCount, batchTimeout, charset);

    // Start the runner thread.
    runnerFuture = executor.submit(runner);

    // Mark the Source as RUNNING.
    //调用父类start()方法更改Source的状态为started
    super.start();

    logger.debug("Exec source started");
  }

  @Override
  public void stop() {
    logger.info("Stopping exec source with command: {}", command);
    if (runner != null) {
      runner.setRestart(false);
      runner.kill();
    }

    if (runnerFuture != null) {
      logger.debug("Stopping exec runner");
      runnerFuture.cancel(true);
      logger.debug("Exec runner stopped");
    }
    executor.shutdown();

    while (!executor.isTerminated()) {
      logger.debug("Waiting for exec executor service to stop");
      try {
        executor.awaitTermination(500, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        logger.debug("Interrupted while waiting for exec executor service "
            + "to stop. Just exiting.");
        Thread.currentThread().interrupt();
      }
    }

    sourceCounter.stop();
    super.stop();

    logger.debug("Exec source with command:{} stopped. Metrics:{}", command,
        sourceCounter);
  }

  //获取用户的flume-ng启动是配置文件中配置的参数
  @Override
  public void configure(Context context) {
    command = context.getString("command");

    Preconditions.checkState(command != null,
        "The parameter command must be specified");

    restartThrottle = context.getLong(ExecSourceConfigurationConstants.CONFIG_RESTART_THROTTLE,
        ExecSourceConfigurationConstants.DEFAULT_RESTART_THROTTLE);

    restart = context.getBoolean(ExecSourceConfigurationConstants.CONFIG_RESTART,
        ExecSourceConfigurationConstants.DEFAULT_RESTART);

    logStderr = context.getBoolean(ExecSourceConfigurationConstants.CONFIG_LOG_STDERR,
        ExecSourceConfigurationConstants.DEFAULT_LOG_STDERR);

    bufferCount = context.getInteger(ExecSourceConfigurationConstants.CONFIG_BATCH_SIZE,
        ExecSourceConfigurationConstants.DEFAULT_BATCH_SIZE);

    batchTimeout = context.getLong(ExecSourceConfigurationConstants.CONFIG_BATCH_TIME_OUT,
        ExecSourceConfigurationConstants.DEFAULT_BATCH_TIME_OUT);

    charset = Charset.forName(context.getString(ExecSourceConfigurationConstants.CHARSET,
        ExecSourceConfigurationConstants.DEFAULT_CHARSET));

    shell = context.getString(ExecSourceConfigurationConstants.CONFIG_SHELL, null);

    if (sourceCounter == null) {
      sourceCounter = new SourceCounter(getName());
    }
  }

  @Override
  public long getBatchSize() {
    return bufferCount;
  }

  //这个类是重点，包含了Flume怎么执行命令
  private static class ExecRunnable implements Runnable {

    public ExecRunnable(String shell, String command, ChannelProcessor channelProcessor,
        SourceCounter sourceCounter, boolean restart, long restartThrottle,
        boolean logStderr, int bufferCount, long batchTimeout, Charset charset) {
      this.command = command;
      this.channelProcessor = channelProcessor;
      this.sourceCounter = sourceCounter;
      this.restartThrottle = restartThrottle;
      this.bufferCount = bufferCount;
      this.batchTimeout = batchTimeout;
      this.restart = restart;
      this.logStderr = logStderr;
      this.charset = charset;
      this.shell = shell;
    }

    private final String shell;
    private final String command;
    private final ChannelProcessor channelProcessor;
    private final SourceCounter sourceCounter;
    private volatile boolean restart;
    private final long restartThrottle;
    // 默认20
    private final int bufferCount;
    // default 3000 ms
    private long batchTimeout;
    private final boolean logStderr;
    private final Charset charset;
    private Process process = null;
    private SystemClock systemClock = new SystemClock();
    private Long lastPushToChannel = systemClock.currentTimeMillis();
    ScheduledExecutorService timedFlushService;
    ScheduledFuture<?> future;

    @Override
    public void run() {
      do {
        String exitCode = "unknown";
        BufferedReader reader = null;
        String line = null;
        //从shell/command读取的内容的缓冲队列，定时刷新到channel
        final List<Event> eventList = new ArrayList<Event>();

        //定时Flush从shell/command的输出到channel的executor
        timedFlushService = Executors.newSingleThreadScheduledExecutor(
                new ThreadFactoryBuilder().setNameFormat(
                "timedFlushExecService" +
                Thread.currentThread().getId() + "-%d").build());
        try {
          if (shell != null) {
            String[] commandArgs = formulateShellCommand(shell, command);
            //调用Runtime执行shell
            process = Runtime.getRuntime().exec(commandArgs);
          }  else {
            String[] commandArgs = command.split("\\s+");
            //调用ProcessBuilder启动进程
            process = new ProcessBuilder(commandArgs).start();
          }
          //获取进程的输出
          reader = new BufferedReader(
              new InputStreamReader(process.getInputStream(), charset));

          // StderrLogger dies as soon as the input stream is invalid
          // 读取数据打印
          StderrReader stderrReader = new StderrReader(new BufferedReader(
              new InputStreamReader(process.getErrorStream(), charset)), logStderr);
          stderrReader.setName("StderrReader-[" + command + "]");
          stderrReader.setDaemon(true);
          stderrReader.start();

          // 定时或者超时将数据写入channel
          future = timedFlushService.scheduleWithFixedDelay(new Runnable() {
              @Override
              public void run() {
                try {
                  synchronized (eventList) {
                    if (!eventList.isEmpty() && timeout()) {
                      //如果eventList不为空并且timeout，执行一次flush操作
                      flushEventBatch(eventList);
                    }
                  }
                } catch (Exception e) {
                  logger.error("Exception occurred when processing event batch", e);
                  if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                  }
                }
              }
          },
          batchTimeout, batchTimeout, TimeUnit.MILLISECONDS);

          // 读取数据写入列表
          while ((line = reader.readLine()) != null) {
            // src.events.received
            sourceCounter.incrementEventReceivedCount();
            synchronized (eventList) {
              // 将event数据放入列表
              eventList.add(EventBuilder.withBody(line.getBytes(charset)));
              //如果缓冲size大于等于最大的缓冲量或者timeout了，直接触发flush
              if (eventList.size() >= bufferCount || timeout()) {
                flushEventBatch(eventList);
              }
            }
          }

          //最后一次性把没有flush的内容写入channel
          synchronized (eventList) {
            if (!eventList.isEmpty()) {
              flushEventBatch(eventList);
            }
          }
        } catch (Exception e) {
          logger.error("Failed while running command: " + command, e);
          if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
          }
        } finally {
          if (reader != null) {
            try {
              reader.close();
            } catch (IOException ex) {
              logger.error("Failed to close reader for exec source", ex);
            }
          }
          // 做一些线程退出处理
          exitCode = String.valueOf(kill());
        }
        if (restart) {
          logger.info("Restarting in {}ms, exit code {}", restartThrottle,
              exitCode);
          try {
            Thread.sleep(restartThrottle);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        } else {
          logger.info("Command [" + command + "] exited with " + exitCode);
        }
      } while (restart);
    }

    private void flushEventBatch(List<Event> eventList) {
      // todo:批量写入channel
      channelProcessor.processEventBatch(eventList);
      // src.events.accepted
      sourceCounter.addToEventAcceptedCount(eventList.size());
      // 清空列表
      eventList.clear();
      // 最后推送到channel的时间
      lastPushToChannel = systemClock.currentTimeMillis();
    }

    //timeout 规则，当前时间距离上次flush时间毫秒数是否大于我们配置的batchTimeout
    private boolean timeout() {
      return (systemClock.currentTimeMillis() - lastPushToChannel) >= batchTimeout;
    }

    private static String[] formulateShellCommand(String shell, String command) {
      String[] shellArgs = shell.split("\\s+");
      String[] result = new String[shellArgs.length + 1];
      System.arraycopy(shellArgs, 0, result, 0, shellArgs.length);
      result[shellArgs.length] = command;
      return result;
    }

    public int kill() {
      if (process != null) {
        synchronized (process) {
          //杀死进程
          process.destroy();

          try {
            int exitValue = process.waitFor();

            // Stop the Thread that flushes periodically
            if (future != null) {
              //停止future
              future.cancel(true);
            }

            if (timedFlushService != null) {
              timedFlushService.shutdown();
              while (!timedFlushService.isTerminated()) {
                try {
                  //停止定时flush线程
                  timedFlushService.awaitTermination(500, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                  logger.debug("Interrupted while waiting for exec executor service "
                      + "to stop. Just exiting.");
                  Thread.currentThread().interrupt();
                }
              }
            }
            return exitValue;
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          }
        }
        return Integer.MIN_VALUE;
      }
      return Integer.MIN_VALUE / 2;
    }
    public void setRestart(boolean restart) {
      this.restart = restart;
    }
  }

  private static class StderrReader extends Thread {
    private BufferedReader input;
    private boolean logStderr;

    protected StderrReader(BufferedReader input, boolean logStderr) {
      this.input = input;
      this.logStderr = logStderr;
    }

    @Override
    public void run() {
      try {
        int i = 0;
        String line = null;
        while ((line = input.readLine()) != null) {
          if (logStderr) {
            // There is no need to read 'line' with a charset
            // as we do not to propagate it.
            // It is in UTF-16 and would be printed in UTF-8 format.
            //调用logger输出process的stderr
            logger.info("StderrLogger[{}] = '{}'", ++i, line);
          }
        }
      } catch (IOException e) {
        logger.info("StderrLogger exiting", e);
      } finally {
        try {
          if (input != null) {
            input.close();
          }
        } catch (IOException ex) {
          logger.error("Failed to close stderr reader for exec source", ex);
        }
      }
    }
  }
}
