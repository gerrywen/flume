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
package org.apache.flume;

import java.util.List;

import org.apache.flume.conf.Configurable;

/**
 * <p>
 * Allows the selection of a subset of channels from the given set based on
 * its implementation policy. Different implementations of this interface
 * embody different policies that affect the choice of channels that a source
 * will push the incoming events to.
 * </p>
 *
 * 允许基于其实现策略从给定的通道集合中选择通道子集。
 * 该接口的不同实现包含不同的策略，这些策略影响源将传入事件推入的通道的选择。
 */
public interface ChannelSelector extends NamedComponent, Configurable {

  /**
   * @param channels all channels the selector could select from.
   *
   *                 通道选择器可以选择的所有通道。
   */
  public void setChannels(List<Channel> channels);

  /**
   * Returns a list of required channels. A failure in writing the event to
   * these channels must be communicated back to the source that received this
   * event.
   *
   * 返回所需通道的列表。向这些通道写入事件失败时，必须向接收此事件的源通信。
   *
   * @param event
   * @return the list of required channels that this selector has selected for
   * the given event.
   *
   * 此选择器为给定事件选择的所需通道的列表。
   */
  public List<Channel> getRequiredChannels(Event event);


  /**
   * Returns a list of optional channels. A failure in writing the event to
   * these channels must be ignored.
   *
   * 返回可选通道列表。向这些通道写入事件的失败必须被忽略。
   *
   * @param event
   * @return the list of optional channels that this selector has selected for
   * the given event.
   *
   * 该选择器为给定事件选择的可选通道列表。
   */
  public List<Channel> getOptionalChannels(Event event);

  /**
   * @return the list of all channels that this selector is configured to work
   * with.
   *
   * 配置此选择器可使用的所有通道的列表。
   */
  public List<Channel> getAllChannels();

}
