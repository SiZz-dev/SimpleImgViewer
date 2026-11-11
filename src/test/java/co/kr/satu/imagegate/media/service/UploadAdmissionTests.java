package co.kr.satu.imagegate.media.service;
import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.system.config.ImageGateProperties.Upload;
import co.kr.satu.imagegate.media.exception.MediaException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
class UploadAdmissionTests {
    ViewerPrincipal actor(String t,String u){return new ViewerPrincipal(t,u);}
    void busy(UploadAdmission gate,ViewerPrincipal actor){assertThatThrownBy(()->gate.acquire(actor)).isInstanceOfSatisfying(MediaException.class,e->assertThat(e.code()).isEqualTo(MediaException.Code.UPLOAD_BUSY));}
    @Test void globalAndPerUserLimitsAreIndependentAndLeaseReleaseIsIdempotent(){
        var gate=new UploadAdmission(new Upload(100,3,1,0));
        var a=actor("Tenant","Alice");var one=gate.acquire(a);busy(gate,a);
        var two=gate.acquire(actor("Tenant","alice"));var three=gate.acquire(actor("tenant","Alice"));
        busy(gate,actor("other","u"));one.close();one.close();
        try(var replacement=gate.acquire(a)){busy(gate,a);}
        two.close();three.close();try(var next=gate.acquire(actor("other","u"))){}
    }
    @Test void concurrentAdmissionsNeverExceedGlobalCapacityAndRecover() throws Exception {
        var gate=new UploadAdmission(new Upload(100,4,1,0));var ready=new CountDownLatch(12);var go=new CountDownLatch(1);
        var release=new CountDownLatch(1);var decisions=new CountDownLatch(12);var accepted=new AtomicInteger();var busy=new AtomicInteger();
        var pool=Executors.newFixedThreadPool(12);var futures=new ArrayList<Future<?>>();
        try {
            for(int i=0;i<12;i++){int n=i;futures.add(pool.submit(()->{ready.countDown();try{go.await();
                UploadAdmission.Lease lease;
                try{lease=gate.acquire(actor("t","u"+n));}catch(MediaException e){busy.incrementAndGet();decisions.countDown();return;}
                accepted.incrementAndGet();decisions.countDown();try(lease){release.await();}
            }catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}}));}
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();go.countDown();assertThat(decisions.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(accepted.get()).isEqualTo(4);assertThat(busy.get()).isEqualTo(8);
        } finally {go.countDown();release.countDown();for(var f:futures)f.get(5,TimeUnit.SECONDS);pool.shutdownNow();}
        try(var next=gate.acquire(actor("t","later"))){}
    }
}
