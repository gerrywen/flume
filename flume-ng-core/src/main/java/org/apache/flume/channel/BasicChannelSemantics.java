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

package org.apache.flume.channel;

import org.apache.flume.Channel;
import org.apache.flume.ChannelException;
import org.apache.flume.Event;
import org.apache.flume.Transaction;
import org.apache.flume.annotations.InterfaceAudience;
import org.apache.flume.annotations.InterfaceStability;

import com.google.common.base.Preconditions;

/**
 * <p>
 * An implementation of basic {@link Channel} semantics, including the
 * implied thread-local semantics of the {@link Transaction} class,
 * which is required to extend {@link BasicTransactionSemantics}.
 * </p>
 */
@InterfaceAudience.Public
@InterfaceStability.Stable
public abstract class BasicChannelSemantics extends AbstractChannel {

  private ThreadLocal<BasicTransactionSemantics> currentTransaction
      = new ThreadLocal<BasicTransactionSemantics>();

  private boolean initialized = false;

  /**
   * <p>
   * Called upon first getTransaction() request, while synchronized on
   * this {@link Channel} instance.  Use this method to delay the
   * initializization resources until just before the first
   * transaction begins.
   * </p>
   *
   * 在第一个getTransaction()请求时调用，同时在这个{@link Channel}实例上同步。
   * 使用此方法可将初始化资源延迟到第一个事务开始之前。
   */
  protected void initialize() {}

  /**
   * <p>
   * Called to create new {@link Transaction} objects, which must
   * extend {@link BasicTransactionSemantics}.  Each object is used
   * for only one transaction, but is stored in a thread-local and
   * retrieved by <code>getTransaction</code> for the duration of that
   * transaction.
   * </p>
   *
   * 用于创建新的{@link Transaction}对象，该对象必须扩展{@link BasicTransactionSemantics}。
   * 每个对象仅用于一个事务，但存储在线程本地，并在事务期间由<code>getTransaction</code>检索。
   */
  protected abstract BasicTransactionSemantics createTransaction();

  /**
   * <p>
   * Ensures that a transaction exists for this thread and then
   * delegates the <code>put</code> to the thread's {@link
   * BasicTransactionSemantics} instance.
   * </p>
   *
   * 确保该线程存在一个事务，然后将<code>put</code>委托给该线程的{@link BasicTransactionSemantics}实例。
   */
  @Override
  public void put(Event event) throws ChannelException {
    BasicTransactionSemantics transaction = currentTransaction.get();
    Preconditions.checkState(transaction != null,
        "No transaction exists for this thread");
    transaction.put(event);
  }

  /**
   * <p>
   * Ensures that a transaction exists for this thread and then
   * delegates the <code>take</code> to the thread's {@link
   * BasicTransactionSemantics} instance.
   * </p>
   *
   * 确保该线程存在一个事务，然后将<code>take</code>委托给该线程的{@link BasicTransactionSemantics}实例。
   */
  @Override
  public Event take() throws ChannelException {
    BasicTransactionSemantics transaction = currentTransaction.get();
    Preconditions.checkState(transaction != null,
        "No transaction exists for this thread");
    return transaction.take();
  }

  /**
   * <p>
   * Initializes the channel if it is not already, then checks to see
   * if there is an open transaction for this thread, creating a new
   * one via <code>createTransaction</code> if not.
   * @return the current <code>Transaction</code> object for the
   *     calling thread
   * </p>
   *
   * 初始化通道(如果通道还没有打开)，然后检查该线程是否有一个打开的事务，
   * 如果没有，则通过<code>createTransaction</code>创建一个新的事务。
   * @return当前调用线程的<code>事务</code>对象
   */
  @Override
  public Transaction getTransaction() {

    if (!initialized) {
      synchronized (this) {
        if (!initialized) {
          initialize();
          initialized = true;
        }
      }
    }

    BasicTransactionSemantics transaction = currentTransaction.get();
    if (transaction == null || transaction.getState().equals(
            BasicTransactionSemantics.State.CLOSED)) {
      transaction = createTransaction();
      currentTransaction.set(transaction);
    }
    return transaction;
  }
}
