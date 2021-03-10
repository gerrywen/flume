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
package org.apache.flume.instrumentation;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import javax.management.ObjectName;

import com.google.common.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Used for keeping track of internal metrics using atomic integers</p>
 *
 * This is used by a variety of component types such as Sources, Channels,
 * Sinks, SinkProcessors, ChannelProcessors, Interceptors and Serializers.
 */
public abstract class MonitoredCounterGroup {

  private static final Logger logger =
      LoggerFactory.getLogger(MonitoredCounterGroup.class);

  // Key for component's start time in MonitoredCounterGroup.counterMap
  private static final String COUNTER_GROUP_START_TIME = "start.time";

  // key for component's stop time in MonitoredCounterGroup.counterMap
  private static final String COUNTER_GROUP_STOP_TIME = "stop.time";

  private final Type type;
  private final String name;
  private final Map<String, AtomicLong> counterMap;

  private AtomicLong startTime;
  private AtomicLong stopTime;
  private volatile boolean registered = false;

  protected MonitoredCounterGroup(Type type, String name, String... attrs) {
    this.type = type;
    this.name = name;

    Map<String, AtomicLong> counterInitMap = new HashMap<String, AtomicLong>();

    // Initialize the counters
    for (String attribute : attrs) {
      counterInitMap.put(attribute, new AtomicLong(0L));
    }

    // 不可更改的map
    counterMap = Collections.unmodifiableMap(counterInitMap);

    startTime = new AtomicLong(0L);
    stopTime = new AtomicLong(0L);

  }

  /**
   * Starts the component
   *
   * Initializes the values for the stop time as well as all the keys in the
   * internal map to zero and sets the start time to the current time in
   * milliseconds since midnight January 1, 1970 UTC
   *
   * 启动组件
   *
   * 将停止时间以及内部映射中的所有键的值初始化为0，
   * 并将开始时间设置为UTC 1970年1月1日午夜以来的当前时间(以毫秒为单位)
   */
  public void start() {

    register();
    // 结束时间
    stopTime.set(0L);
    // 遍历初始化为0
    for (String counter : counterMap.keySet()) {
      counterMap.get(counter).set(0L);
    }
    // 开始时间
    startTime.set(System.currentTimeMillis());
    logger.info("Component type: " + type + ", name: " + name + " started");
  }

  /**
   * Registers the counter.
   * This method is exposed only for testing, and there should be no need for
   * any implementations to call this method directly.
   *
   * 注册统计。此方法仅在测试时公开，任何实现都不需要直接调用此方法。
   */
  @VisibleForTesting
  void register() {
    if (!registered) {
      try {
        ObjectName objName = new ObjectName("org.apache.flume."
                + type.name().toLowerCase(Locale.ENGLISH) + ":type=" + this.name);

        if (ManagementFactory.getPlatformMBeanServer().isRegistered(objName)) {
          logger.debug("Monitored counter group for type: " + type + ", name: "
              + name + ": Another MBean is already registered with this name. "
              + "Unregistering that pre-existing MBean now...");
          ManagementFactory.getPlatformMBeanServer().unregisterMBean(objName);
          logger.debug("Monitored counter group for type: " + type + ", name: "
              + name + ": Successfully unregistered pre-existing MBean.");
        }
        ManagementFactory.getPlatformMBeanServer().registerMBean(this, objName);
        logger.info("Monitored counter group for type: " + type + ", name: "
            + name + ": Successfully registered new MBean.");
        registered = true;
      } catch (Exception ex) {
        logger.error("Failed to register monitored counter group for type: "
                + type + ", name: " + name, ex);
      }
    }
  }

  /**
   * Shuts Down the Component
   * 关闭组件
   *
   * Used to indicate that the component is shutting down.
   * 用于指示组件正在关闭。
   *
   * Sets the stop time and then prints out the metrics from
   * the internal map of keys to values for the following components:
   *
   * 设置停止时间，然后打印出以下组件的键到值的内部映射的度量:
   *
   * - ChannelCounter
   * - ChannelProcessorCounter
   * - SinkCounter
   * - SinkProcessorCounter
   * - SourceCounter
   */
  public void stop() {

    // Sets the stopTime for the component as the current time in milliseconds
    // 将组件的stopTime设置为当前时间，以毫秒为单位
    stopTime.set(System.currentTimeMillis());

    // Prints out a message indicating that this component has been stopped
    logger.info("Component type: " + type + ", name: " + name + " stopped");

    // Retrieve the type for this counter group
    // 检索此计数器组的类型
    final String typePrefix = type.name().toLowerCase(Locale.ENGLISH);

    // Print out the startTime for this component
    logger.info("Shutdown Metric for type: " + type + ", "
        + "name: " + name + ". "
        + typePrefix + "." + COUNTER_GROUP_START_TIME
        + " == " + startTime);

    // Print out the stopTime for this component
    logger.info("Shutdown Metric for type: " + type + ", "
        + "name: " + name + ". "
        + typePrefix + "." + COUNTER_GROUP_STOP_TIME
        + " == " + stopTime);

    // Retrieve and sort counter group map keys
    // 检索和排序计数器组映射键
    final List<String> mapKeys = new ArrayList<String>(counterMap.keySet());

    Collections.sort(mapKeys);

    // Cycle through and print out all the key value pairs in counterMap
    for (final String counterMapKey : mapKeys) {

      // Retrieves the value from the original counterMap.
      // 从原始反映射中检索值。
      final long counterMapValue = get(counterMapKey);

      logger.info("Shutdown Metric for type: " + type + ", "
          + "name: " + name + ". "
          + counterMapKey + " == " + counterMapValue);
    }
  }

  /**
   * Returns when this component was first started
   *
   * 当这个组件第一次启动时返回
   *
   * @return
   */
  public long getStartTime() {
    return startTime.get();
  }

  /**
   * Returns when this component was stopped
   *
   * 当组件停止时返回
   *
   * @return
   */
  public long getStopTime() {
    return stopTime.get();
  }

  @Override
  public final String toString() {
    StringBuilder sb = new StringBuilder(type.name()).append(":");
    sb.append(name).append("{");
    boolean first = true;
    Iterator<String> counterIterator = counterMap.keySet().iterator();
    while (counterIterator.hasNext()) {
      if (first) {
        first = false;
      } else {
        sb.append(", ");
      }
      String counterName = counterIterator.next();
      sb.append(counterName).append("=").append(get(counterName));
    }
    sb.append("}");

    return sb.toString();
  }


  /**
   * Retrieves the current value for this key
   * 检索此键的当前值
   *
   * @param counter The key for this metric
   * @return The current value for this key
   */
  protected long get(String counter) {
    return counterMap.get(counter).get();
  }

  /**
   * Sets the value for this key to the given value
   *
   * 将此键的值设置为给定的值
   *
   * @param counter The key for this metric
   * @param value The new value for this key
   */
  protected void set(String counter, long value) {
    counterMap.get(counter).set(value);
  }

  /**
   * Atomically adds the delta to the current value for this key
   *
   * 原子将增量添加到该键的当前值
   *
   * @param counter The key for this metric
   * @param delta
   * @return The updated value for this key
   */
  protected long addAndGet(String counter, long delta) {
    return counterMap.get(counter).addAndGet(delta);
  }

  /**
   * Atomically increments the current value for this key by one
   *
   * 原子地将该键的当前值加1
   *
   * @param counter The key for this metric
   * @return The updated value for this key
   */
  protected long increment(String counter) {
    return counterMap.get(counter).incrementAndGet();
  }

  /**
   * Component Enum Constants
   *
   * 组件枚举常量
   *
   * Used by each component's constructor to distinguish which type the
   * component is.
   *
   * 由每个组件的构造函数来区分组件的类型。
   */
  public static enum Type {
    SOURCE,
    CHANNEL_PROCESSOR,
    CHANNEL,
    SINK_PROCESSOR,
    SINK,
    INTERCEPTOR,
    SERIALIZER,
    OTHER
  }

  public String getType() {
    return type.name();
  }
}
