package co.kr.satu.imagegate.conversion.util;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class OpenStackSamplerTests {
    @Test void acceptsOnlyBoundedUniqueSyntheticLabels() throws Exception {
        try(var sampler=new OpenStackSampler()){
            assertThatIllegalArgumentException().isThrownBy(()->sampler.marker("D:/private/file"));
            sampler.marker("one");
            assertThatIllegalArgumentException().isThrownBy(()->sampler.marker("one"));
            for(int i=1;i<64;i++)sampler.marker("label-"+i);
            assertThatIllegalArgumentException().isThrownBy(()->sampler.marker("overflow"));
        }
    }
    @Test void capsFramesAndDistinctStackKinds() throws Exception {
        try(var sampler=new OpenStackSampler()){
            sampler.marker("bounded");
            for(int i=0;i<17;i++){
                var frames=new ArrayList<String>();for(int j=0;j<20;j++)frames.add("example.Frame"+i+".method"+j);
                sampler.record("bounded","RUNNABLE",frames);
            }
            var report=sampler.report();
            var labels=(Map<?,?>)report.get("labels");var rows=(List<?>)labels.get("bounded");
            assertThat(rows).hasSize(16);assertThat(report.get("droppedSamples")).isEqualTo(1);
            assertThat(((List<?>)((Map<?,?>)rows.getFirst()).get("frames"))).hasSize(12);
        }
    }
    @Test void samplesOnlyMarkedThreadAndStopsAfterExit() throws Exception {
        try(var sampler=new OpenStackSampler()){
            var marker=sampler.marker("worker");var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            var thread=new Thread(()->{
                marker.entering();entered.countDown();
                try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
                finally{marker.exited();}
            },"synthetic-only");
            thread.setDaemon(true);thread.start();
            try{
                assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while(System.nanoTime()<end){
                    var rows=(List<?>)((Map<?,?>)sampler.report().get("labels")).get("worker");
                    if(!rows.isEmpty())break;Thread.sleep(10);
                }
                var labels=(Map<?,?>)sampler.report().get("labels");
                assertThat(labels).hasSize(1);assertThat((List<?>)labels.get("worker")).isNotEmpty();
            }finally{release.countDown();thread.join(2000);}
            assertThat(thread.isAlive()).isFalse();assertThat(sampler.activeCount()).isZero();
            assertThat(sampler.report().get("samplingErrors")).isEqualTo(0);
        }
    }

    @Test void disabledSamplerRetainsMarkerLifecycleWithoutCollectingStacks() throws Exception {
        try(var sampler=new OpenStackSampler(false)){
            var marker=sampler.marker("disabled");marker.entering();
            try{
                var report=sampler.report();assertThat(report.get("enabled")).isEqualTo(false);
                assertThat(report.get("activeAtReport")).isEqualTo(1);
                assertThat((List<?>)((Map<?,?>)report.get("labels")).get("disabled")).isEmpty();
            }finally{marker.exited();}
            assertThat(sampler.activeCount()).isZero();
        }
    }
}
