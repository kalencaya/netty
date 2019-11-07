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

package io.netty.buffer;

final class PoolSubpage<T> implements PoolSubpageMetric {

    final PoolChunk<T> chunk;
    private final int memoryMapIdx;
    private final int runOffset;
    private final int pageSize;
    private final long[] bitmap;

    PoolSubpage<T> prev;
    PoolSubpage<T> next;

    boolean doNotDestroy;
    int elemSize;
    private int maxNumElems;
    private int bitmapLength;
    private int nextAvail;
    private int numAvail;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /** Special constructor that creates a linked list head */
    PoolSubpage(int pageSize) {
        chunk = null;
        memoryMapIdx = -1;
        runOffset = -1;
        elemSize = -1;
        this.pageSize = pageSize;
        bitmap = null;
    }

    PoolSubpage(PoolSubpage<T> head, PoolChunk<T> chunk, int memoryMapIdx, int runOffset, int pageSize, int elemSize) {
        this.chunk = chunk;
        this.memoryMapIdx = memoryMapIdx;
        this.runOffset = runOffset;
        this.pageSize = pageSize;
        // subPageSize最小为16B，pageSize = 8KB，每个long类型占64位，则需要长度为 8 * 1024 / 16 / 64 = 8 * 1024 / 2 ^ 10 = 8 的long[]数组
        bitmap = new long[pageSize >>> 10]; // pageSize / 16 / 64
        init(head, elemSize);
    }

    void init(PoolSubpage<T> head, int elemSize) {
        doNotDestroy = true;
        this.elemSize = elemSize;
        if (elemSize != 0) {
            maxNumElems = numAvail = pageSize / elemSize;
            nextAvail = 0;
            //每个long类型占64位，则需要用到 maxNumElems / 64 = maxNumElems / 2 ^ 6 = maxNumElems >>> 6
            bitmapLength = maxNumElems >>> 6;
            //如果maxNumElems数量少于 64 个，则 maxNumElems >>> 6 的结果则为 0 ，或者maxNumElems 为65，65 / 64 也会等于1。
            //这里要对最后的bitmapLength进行 + 1操作
            if ((maxNumElems & 63) != 0) {
                bitmapLength ++;
            }

            for (int i = 0; i < bitmapLength; i ++) {
                bitmap[i] = 0;
            }
        }
        addToPool(head);
    }

    /**
     * Returns the bitmap index of the subpage allocation.
     */
    long allocate() {
        //防御性编程，实际情况中不会有这种情况。因为最小的elemSize = 16B
        if (elemSize == 0) {
            return toHandle(0);
        }

        //完全耗尽资源或已经销毁，直接返回
        if (numAvail == 0 || !doNotDestroy) {
            return -1;
        }

        //获得下一个可用的 Subpage 在 bitmap 中的总体位置。每个bitmap[]长度为bitmapLength，可表示的总的bit长度是小于 64 * bitmapLength的
        //这里的bitmapId代表的是65这类长度
        final int bitmapIdx = getNextAvail();
        //获得下一个可用的 Subpage 在 bitmap 中数组的位置
        //将65转换为bitmap[]中的索引，q = 65 / 64 = 1
        int q = bitmapIdx >>> 6;
        //获得下一个可用的 Subpage 在 bitmap 中数组的位置的第几 bits
        //计算128超出2个long多少。 65 & 63 = 1
        int r = bitmapIdx & 63;
        //最后就是将65，转换为完全占据了1个long，第2(q + 1)个long占据了1(r)个bit
        assert (bitmap[q] >>> r & 1) == 0;
        //修改 Subpage 在 bitmap 中不可分配
        //q是第几个long，q = 1，代表第一个，则表示第一个即bitmap[0]完全分配完毕，则需要在bitmap[q]分配，很巧妙
        //至于|操作，则和bit权限的表示同样的原理。
        bitmap[q] |= 1L << r;

        //如果已经完全分配完毕，则从池子中移除
        //为什么要是从双向链表中移除呢？如果移除了，不是会被垃圾回收吗？？？
        if (-- numAvail == 0) {
            removeFromPool();
        }

        return toHandle(bitmapIdx);
    }

    /**
     * @return {@code true} if this subpage is in use.
     *         {@code false} if this subpage is not used by its chunk and thus it's OK to be released.
     */
    boolean free(PoolSubpage<T> head, int bitmapIdx) {
        if (elemSize == 0) {
            return true;
        }
        int q = bitmapIdx >>> 6;
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) != 0;
        bitmap[q] ^= 1L << r;

        setNextAvail(bitmapIdx);

        if (numAvail ++ == 0) {
            addToPool(head);
            return true;
        }

        if (numAvail != maxNumElems) {
            return true;
        } else {
            // Subpage not in use (numAvail == maxNumElems)
            if (prev == next) {
                // Do not remove if this subpage is the only one left in the pool.
                return true;
            }

            // Remove this subpage from the pool if there are other subpages left in the pool.
            doNotDestroy = false;
            removeFromPool();
            return false;
        }
    }

    private void addToPool(PoolSubpage<T> head) {
        assert prev == null && next == null;
        prev = head;
        next = head.next;
        next.prev = this;
        head.next = this;
    }

    private void removeFromPool() {
        assert prev != null && next != null;
        prev.next = next;
        next.prev = prev;
        next = null;
        prev = null;
    }

    private void setNextAvail(int bitmapIdx) {
        nextAvail = bitmapIdx;
    }

    private int getNextAvail() {
        int nextAvail = this.nextAvail;
        if (nextAvail >= 0) {
            this.nextAvail = -1;
            return nextAvail;
        }
        return findNextAvail();
    }

    private int findNextAvail() {
        final long[] bitmap = this.bitmap;
        final int bitmapLength = this.bitmapLength;
        for (int i = 0; i < bitmapLength; i ++) {
            long bits = bitmap[i];
            if (~bits != 0) {
                return findNextAvail0(i, bits);
            }
        }
        return -1;
    }

    private int findNextAvail0(int i, long bits) {
        final int maxNumElems = this.maxNumElems;
        final int baseVal = i << 6;

        for (int j = 0; j < 64; j ++) {
            //如果当前bits最后一位是0，则代表当前位未被分配
            if ((bits & 1) == 0) {
                //使用低64位，表示分配 bitmap 数组的元素的第几个bit
                int val = baseVal | j;
                if (val < maxNumElems) {
                    return val;
                } else {
                    break;
                }
            }
            //从0位开始一直到最后一位，如果检测到bits & 1 == 0，就代表当前位未被分配
            bits >>>= 1;
        }
        return -1;
    }

    //计算handle

    /**
     * (long) bitmapIdx << 32，表示使用后32位。但是bitmapIdx本身就是32位的，位移操作后就是相当于在bitmapIdx后加了个32个0。
     * memoryMapIdx 是int类型，长度为32位。那么将bitmapIdx位移32位后进行或操作，真是含义就是将bitmapIdx + memoryMapIdx拼接起来。
     * 这样就将高32位表示bitmapIdx，低32位表示为memoryMapIdx。低32位memoryMapIdx表示是PoolChunk的哪个Page，高32位bitmapIdx
     * 表示是Page中的哪个SubPage。
     * 0x4000000000000000L是为了区分{@link PoolChunk#allocateRun(int)}中返回的可能是Page或SubPage。
     */
    private long toHandle(int bitmapIdx) {
        return 0x4000000000000000L | (long) bitmapIdx << 32 | memoryMapIdx;
    }

    @Override
    public String toString() {
        final boolean doNotDestroy;
        final int maxNumElems;
        final int numAvail;
        final int elemSize;
        synchronized (chunk.arena) {
            if (!this.doNotDestroy) {
                doNotDestroy = false;
                // Not used for creating the String.
                maxNumElems = numAvail = elemSize = -1;
            } else {
                doNotDestroy = true;
                maxNumElems = this.maxNumElems;
                numAvail = this.numAvail;
                elemSize = this.elemSize;
            }
        }

        if (!doNotDestroy) {
            return "(" + memoryMapIdx + ": not in use)";
        }

        return "(" + memoryMapIdx + ": " + (maxNumElems - numAvail) + '/' + maxNumElems +
                ", offset: " + runOffset + ", length: " + pageSize + ", elemSize: " + elemSize + ')';
    }

    @Override
    public int maxNumElements() {
        synchronized (chunk.arena) {
            return maxNumElems;
        }
    }

    @Override
    public int numAvailable() {
        synchronized (chunk.arena) {
            return numAvail;
        }
    }

    @Override
    public int elementSize() {
        synchronized (chunk.arena) {
            return elemSize;
        }
    }

    @Override
    public int pageSize() {
        return pageSize;
    }

    void destroy() {
        if (chunk != null) {
            chunk.destroy();
        }
    }
}
