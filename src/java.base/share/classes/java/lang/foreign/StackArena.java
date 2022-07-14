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

import jdk.internal.foreign.NativeMemorySegmentImpl;
import jdk.internal.javac.PreviewFeature;
import jdk.internal.vm.annotation.ForceInline;

/**
 * A thread-confined arena that can be reused by multiple clients to speed up allocation.
 * <p>
 * A stack arena is characterized by two properties: a block size {@code B} and a capacity {@code C}.
 * A stack arena contains a list of native memory segments {@code SS} and an index {@code I}
 * which points to a segment in this list. When the allocator is first initialized, {@code SS} contains a single native
 * memory segment of size {@code B} and {@code I = 0}. As the allocator responds to allocation requests (see below),
 * the contents of {@code SS} and {@code I} will be updated accordingly.
 * <p>
 * Clients can <em>reuse</em> a stack arena by calling the {@link #push()} method. This method returns a new
 * <em>nested</em> stack arena, which will be backed by the same segment list {@code SS} as the parent arena.
 * Clients cannot interact with a stack arena that has a nested arena that has not yet been closed.
 * When a nested stack arena is {@link #close() closed} the memory allocated within it are returned to the parent arena, so that
 * it can be efficiently recycled. When the outermost stack arena is closed, the memory associated with the stack
 * arena (e.g. the memory segments in {@code SS}) will be released.
 * <p>
 * A stack arena implements the {@link SegmentAllocator} interface, and (whether nested or not) responds to allocation
 * requests in a similar way as a {@link SegmentAllocator#newNativeArena(MemorySession) slicing allocator}, as shown below:
 * <ul>
 *     <li>if the size of the allocation requests is smaller than {@code B}, and {@code SS[I]} has a <em>free</em>
 *     slice {@code S} which fits that allocation request, return that {@code S};
 *     <li>if the size of the allocation requests is smaller than {@code B}, and {@code SS[I]} has no <em>free</em>
 *     slices which fits that allocation request, {@code I} is set to {@code I + 1}. Then, if {@code SS[I] == null},
 *     a new segment {@code S}, with size {@code B}, is allocated and added to {@code SS}. The arena then tries to respond
 *     to the same allocation request again;
 *     <li>if the size of the allocation requests is bigger than {@code B}, an {@link IllegalArgumentException}
 *     is thrown.</li>
 * </ul>
 * A stack arena might throw an {@link OutOfMemoryError} if, during its use, the total memory allocated by all the
 * active arenas nested in the outermost arena exceeds the arena capacity {@code C}, or the system capacity.
 * <p>
 * This segment allocator can be useful when clients want to perform multiple allocation requests while avoiding the
 * cost associated with allocating a new off-heap memory region upon each allocation request:
 *
 * {@snippet lang=java :
 * try (StackArena stack = StackArena.newStack()) {
 *     ...
 *     for (int i = 0 ; i < 1000 ; i++) {
 *         try (StackArena localArena = stack.push()) {
 *             ...
 *             MemorySegment.allocateNative(100, localArena);
 *             ...
 *         } // arena memory recycled
 *     }
 *     ...
 *  } // arena memory released
 * }
 *
 * The above code creates a stack arena with unbounded capacity. It then allocates memory in a loop; at each iteration,
 * a new nested arena is obtained by calling the {@link #push()} method. When the nested arena is closed, the allocated memory
 * is returned to the parent arena and then recycled on the subsequent iteration. When the outermost arena is closed,
 * the off-heap memory associated with the stack arena is released.
 */
@PreviewFeature(feature=PreviewFeature.Feature.FOREIGN)
public sealed class StackArena extends Arena {

    Block.Cursor currentBlock;
    final StackArena parent;
    boolean isTop = true;

    /* package */ StackArena(StackArena parent) {
        super(MemorySession.openConfined());
        this.parent = parent;
    }

    void checkTop() {
        if (!isTop) {
            throw new IllegalStateException();
        }
    }

    /**
     * Obtains a new stack arena, nested in this arena. This arena cannot be closed, used to allocate more
     * segments, or to obtain new nested stack arenas until the returned arena is closed.
     * @return a new stack arena, nested in this arena.
     * @throws IllegalStateException if there is a stack arena nested in this arena that has not been closed.
     */
    public StackArena push() {
        checkTop();
        isTop = false;
        StackArena child = new StackArena(this);
        child.currentBlock = currentBlock.dup();
        return child;
    }

    /**
     * Closes this stack arena. All the segments allocated by this arena can no longer be accessed. If this arena
     * is the outermost stack arena, all the memory resources associated with the arena are released.
     * @throws IllegalStateException if there is a stack arena nested in this arena that has not been closed.
     */
    @ForceInline
    public void close() {
        checkTop();
        super.close();
        if (parent != null) {
            parent.isTop = true;
        }
    }

    /**
     * Allocates a new native segment in this stack arena. The returned segment is associated with this arena
     * and cannot be accessed after this arena is {@linkplain #close() closed}. Moreover, as this arena is thread-confined,
     * access to the returned segment is only possible from the thread {@linkplain MemorySession#ownerThread() owning} this arena.
     * @implNote This method might allocate new off-heap memory, or recycle memory allocated during previous allocation requests.
     * @param bytesSize the size (in bytes) of the block of memory to be allocated.
     * @param bytesAlignment the alignment (in bytes) of the block of memory to be allocated.
     * @return a new native segment associated with this stack arena.
     * @throws IllegalStateException if there is a stack arena nested in this arena that has not been closed.
     */
    @Override
    public MemorySegment allocate(long bytesSize, long bytesAlignment) {
        checkSizeAndAlign(bytesSize, bytesAlignment);
        checkTop();
        MemoryAddress address = currentBlock.nextSlice(bytesSize, bytesAlignment);
        return NativeMemorySegmentImpl.makeNativeSegmentUnchecked(address, bytesSize, session());
    }

    /**
     * Creates a new stack arena, with unbounded capacity and block size set to a predefined size.
     * Equivalent to the following code:
     * {@snippet lang=java :
     * StackArena.newStack(Long.MAX_VALUE, predefinedBlockSize);
     * }
     * @return a new stack arena, with unbounded capacity and block size set to a predefined size.
     */
    public static StackArena newStack() {
        return newStack(Long.MAX_VALUE, Block.DEFAULT_BLOCK_SIZE);
    }

    /**
     * Creates a new stack arena, with capacity and block size set to the provided value.
     * Equivalent to the following code:
     * {@snippet lang=java :
     * StackArena.newStack(capacity, capacity);
     * }
     * @param capacity the stack arena capacity (and block size).
     * @return a new stack arena, with unbounded capacity and block size set to a predefined size.
     */
    public static StackArena newStack(long capacity) {
        return newStack(capacity, capacity);
    }

    /**
     * {@return a new stack arena, with the given capacity and block size}.
     * @param capacity the stack arena capacity.
     * @param blockSize the stack arena block size.
     */
    public static StackArena newStack(long capacity, long blockSize) {
        return new Root(blockSize, capacity);
    }

    static final class Root extends StackArena {

        final Block blocks;

        public Root(long blockSize, long arenaSize) {
            super(null);
            blocks = new Block(blockSize, arenaSize, session());
            currentBlock = blocks.start();
        }
    }

    private static void checkSizeAndAlign(long bytesSize, long alignmentBytes) {
        // size should be >= 0
        if (bytesSize < 0) {
            throw new IllegalArgumentException("Invalid allocation size : " + bytesSize);
        }

        // alignment should be > 0, and power of two
        if (alignmentBytes <= 0 ||
                ((alignmentBytes & (alignmentBytes - 1)) != 0L)) {
            throw new IllegalArgumentException("Invalid alignment constraint : " + alignmentBytes);
        }
    }
}
