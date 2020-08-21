/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel;

import io.netty.util.concurrent.OrderedEventExecutor;

/**
 * Will handle all the I/O operations for a {@link Channel} once registered.
 *
 * One {@link EventLoop} instance will usually handle more than one {@link Channel} but this may depend on
 * implementation details and internals.
 *
 * 处理注册的Channel的所有I/O操作。
 * 一个EventLoop实例通常处理多个Channel，但这个要依赖具体的实现。
 */
public interface EventLoop extends OrderedEventExecutor, EventLoopGroup {

    /**
     * 覆写返回方法为EventLoopGroup，原先返回类型为EventExecutorGroup，EventExecutorGroup为EventLoopGroup的父接口
     * @return
     */
    @Override
    EventLoopGroup parent();
}
