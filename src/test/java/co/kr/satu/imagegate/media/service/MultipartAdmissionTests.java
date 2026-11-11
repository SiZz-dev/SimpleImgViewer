package co.kr.satu.imagegate.media.service;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
class MultipartAdmissionTests {
    @Test void boundedAdmissionRejectsWithoutWaitingAndCloseIsIdempotent(){
        var gate=new MultipartAdmission(2);var a=gate.tryAcquire();var b=gate.tryAcquire();assertThat(a).isNotNull();assertThat(b).isNotNull();assertThat(gate.tryAcquire()).isNull();
        a.close();a.close();var c=gate.tryAcquire();assertThat(c).isNotNull();assertThat(gate.tryAcquire()).isNull();b.close();c.close();
        try(var next=gate.tryAcquire()){assertThat(next).isNotNull();}
    }
    @Test void racingRequestsCannotExceedCapacity() throws Exception {
        var gate=new MultipartAdmission(3);var ready=new CountDownLatch(12);var go=new CountDownLatch(1);
        try(var threads=Executors.newFixedThreadPool(12)){
            var results=new ArrayList<Future<MultipartAdmission.Lease>>();for(int i=0;i<12;i++)results.add(threads.submit(()->{ready.countDown();go.await();return gate.tryAcquire();}));
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();go.countDown();var admitted=new ArrayList<MultipartAdmission.Lease>();
            for(var f:results){var lease=f.get(5,TimeUnit.SECONDS);if(lease!=null)admitted.add(lease);}
            assertThat(admitted).hasSize(3);admitted.forEach(MultipartAdmission.Lease::close);
            try(var next=gate.tryAcquire()){assertThat(next).isNotNull();}
        }
    }
}
