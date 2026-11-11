package co.kr.satu.imagegate.media.service;
import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.media.exception.MediaException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
class TenantOriginalQuotaTests {
    @Test void sharedReservationsCommitReleaseAndIndependentTenants() throws Exception {
        var q=new TenantOriginalQuota(100,t->10);
        try(var a=q.acquire("t");var b=q.acquire("t");var other=q.acquire("other")){
            a.grow(60);b.grow(30);other.grow(90);
            assertThatThrownBy(()->b.grow(1)).isInstanceOfSatisfying(MediaException.class,e->assertThat(e.code()).isEqualTo(MediaException.Code.TENANT_STORAGE_QUOTA_EXCEEDED));
            a.commit();a.close();
            assertThatThrownBy(()->b.grow(1)).isInstanceOf(MediaException.class);
            b.close(); // failed upload releases its reservation; committed bytes remain.
        }
        q.stop();
    }
    @Test void failedReservationBecomesAvailableToAnotherActiveUploadExactlyOnce() throws Exception {
        var q=new TenantOriginalQuota(100,t->10);
        try(var a=q.acquire("t");var b=q.acquire("t")){
            a.grow(60);b.grow(30);a.close();a.close();b.grow(60);b.commit();
            try(var c=q.acquire("t")){assertThatThrownBy(()->c.grow(1)).isInstanceOf(MediaException.class);}
        }
    }
    @Test void disabledQuotaNeverScansAndKeepsOriginalSizePolicySeparate() throws Exception {
        var q=new TenantOriginalQuota(0,t->{throw new IOException("must not scan");});
        try(var a=q.acquire("t")){a.grow(Integer.MAX_VALUE);a.commit();}
    }
    @Test void idleTenantIsRescannedAndLimitReductionDoesNotDeleteOrOverflow() throws Exception {
        var used=new AtomicLong(5);var q=new TenantOriginalQuota(10,t->used.get());
        try(var a=q.acquire("t")){a.grow(5);}
        used.set(Long.MAX_VALUE);
        try(var a=q.acquire("t")){assertThatThrownBy(()->a.grow(1)).isInstanceOf(MediaException.class);}
    }
    @Test void uncertainCleanupBlocksAllFurtherWritesAndActiveLeasePreventsStop() throws Exception {
        var q=new TenantOriginalQuota(10,t->0);
        try(var a=q.acquire("t")){
            assertThatThrownBy(q::stop).isInstanceOf(IOException.class);
            a.invalidate();assertThatThrownBy(()->a.grow(1)).isInstanceOf(IOException.class);
        }
        assertThatThrownBy(()->q.acquire("other")).isInstanceOf(IOException.class);
    }
}
