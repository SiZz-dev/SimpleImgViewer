package co.kr.satu.imagegate.media.service;

import co.kr.satu.imagegate.media.exception.MediaException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Upload-only accounting. Idle tenants retain no cache; reads never acquire this monitor. */
public final class TenantOriginalQuota {
    @FunctionalInterface public interface UsageSource { long measure(String tenant) throws IOException; }
    private final long limit;
    private final UsageSource source;
    private final Map<String, State> active = new HashMap<>();
    private boolean healthy = true;
    private static final class State { long used, reserved; int leases; State(long used) { this.used=used; } }

    public TenantOriginalQuota(long limit, UsageSource source) {
        if (limit<0) throw new IllegalArgumentException("Invalid tenant quota");
        this.limit=limit; this.source=source;
    }
    public synchronized Lease acquire(String tenant) throws IOException {
        if (!healthy) throw new IOException("Original quota accounting unavailable");
        if (limit==0) return new Lease(tenant,null);
        State state=active.get(tenant);
        if (state==null) {
            long used=source.measure(tenant);
            if (used<0) throw new IOException("Invalid original usage");
            state=new State(used); active.put(tenant,state);
        }
        state.leases++;
        return new Lease(tenant,state);
    }
    public synchronized void stop() throws IOException {
        if(!active.isEmpty())throw new IOException("Original uploads still active");
        healthy=false;
    }
    public final class Lease implements AutoCloseable {
        private final String tenant;
        private final State state;
        private long bytes;
        private boolean committed, closed;
        private Lease(String tenant,State state) { this.tenant=tenant;this.state=state; }
        public void grow(int amount) throws IOException {
            synchronized(TenantOriginalQuota.this) {
                if (closed||committed||!healthy) throw new IOException("Original quota lease unavailable");
                if (state==null) return;
                if (amount<0||state.used>limit||state.reserved>limit-state.used||amount>limit-state.used-state.reserved)
                    throw new MediaException(MediaException.Code.TENANT_STORAGE_QUOTA_EXCEEDED);
                state.reserved+=amount; bytes+=amount;
            }
        }
        public void commit() {
            synchronized(TenantOriginalQuota.this) {
                if (closed||committed) throw new IllegalStateException("Invalid quota commit");
                if(state!=null){state.reserved-=bytes;state.used+=bytes;}
                committed=true;
            }
        }
        /** Cleanup uncertainty must never release space for another upload. Restart requires a rescan. */
        public void invalidate() { synchronized(TenantOriginalQuota.this) { if(limit>0)healthy=false; } }
        @Override public void close() {
            synchronized(TenantOriginalQuota.this) {
                if(closed)return;
                closed=true;
                if(state!=null){if(!committed)state.reserved-=bytes;if(--state.leases==0)active.remove(tenant);}
            }
        }
    }
}
