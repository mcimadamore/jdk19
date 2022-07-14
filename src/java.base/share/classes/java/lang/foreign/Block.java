/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package java.lang.foreign;

/**
 * A helper class to manage slice-based allocators.
 * @see SegmentAllocator#newNativeArena(MemorySession)
 * @see StackArena
 */
class Block {

    static final long DEFAULT_BLOCK_SIZE = 4 * 1024;

    private final long rem, blockSize;
    private final MemorySegment segment;
    private final MemorySession session;
    private Block next;

    public Block(long size, long rem, MemorySession arena) {
        this.blockSize = size;
        if (rem - size < 0) {
            this.rem = 0;
            size = rem;
        } else {
            this.rem = rem - size;
        }
        this.session = arena;
        this.segment = MemorySegment.allocateNative(size, 1, arena);
    }

    Cursor start() {
        return new Cursor(this, 0, 0);
    }

    private Block nextOrAllocate() {
        if (next == null) {
            if (rem == 0) {
                throw new OutOfMemoryError();
            }
            next = new Block(blockSize, rem, session);
        }
        return next;
    }

    static class Cursor {
        Block block;
        long offset;
        long size;

        Cursor(Block block, long offset, long size) {
            this.block = block;
            this.offset = offset;
            this.size = size;
        }

        private void trySlice(long bytesSize, long bytesAlignment) {
            long min = block.segment.address().toRawLongValue();
            long start = alignUp(min + offset + size, bytesAlignment) - min;
            if (block.segment.byteSize() - start < bytesSize) {
                offset = -1;
            } else {
                offset = start;
                size = bytesSize;
            }
        }

        MemoryAddress nextSlice(long bytesSize, long bytesAlignment) {
            // try to slice from current segment first...
            trySlice(bytesSize, bytesAlignment);
            if (offset != -1) {
                return block.segment.address().addOffset(offset);
            } else {
                long maxPossibleAllocationSize = bytesSize + bytesAlignment - 1;
                if (maxPossibleAllocationSize > block.blockSize) {
                    // too big
                    throw new IllegalArgumentException("Allocation size > block size");
                } else {
                    // allocate a new segment and slice from there
                    block = block.nextOrAllocate();
                    offset = 0;
                    size = 0;
                    return nextSlice(bytesSize, bytesAlignment);
                }
            }
        }

        Cursor dup() {
            return new Cursor(block, offset, size);
        }
    }

    static long alignUp(long n, long alignment) {
        return (n + alignment - 1) & -alignment;
    }
}
