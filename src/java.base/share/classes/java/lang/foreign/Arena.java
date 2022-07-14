package java.lang.foreign;

import jdk.internal.vm.annotation.ForceInline;

/**
 * What a beautiful class!
 */
public abstract class Arena implements SegmentAllocator, AutoCloseable {
    private final MemorySession publicSession;
    private final MemorySession session;

    /**
     * Constructor     *
     * @param session session
     */
    @ForceInline
    protected Arena(MemorySession session) {
        this.publicSession = session.asNonCloseable();
        this.session = session;
    }

    /**
     * Session
     * @return session
     */
    @ForceInline
    public MemorySession session() {
        return publicSession;
    }

    @Override
    @ForceInline
    public void close() {
        session.close();
    }
}
