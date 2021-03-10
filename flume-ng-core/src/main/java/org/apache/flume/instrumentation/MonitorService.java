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

import org.apache.flume.conf.Configurable;

/**
 * Interface that any monitoring service should implement. If the monitor
 * service is to be started up when Flume starts, it should implement this
 * and the class name should be passed in during startup, with any additional
 * context it requires.
 */
// 在Application 类中，还定义了监控服务对象，用于监控agent，该服务也是根据配置文件信息进行初始化的。
// 如果不配置，则没有监控信息。Flume官方建议使用GangliaServer类，通过Ganglia进行监控。
public interface MonitorService extends Configurable {

  public void start();

  public void stop();

}
