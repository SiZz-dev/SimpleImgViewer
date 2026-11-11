package co.kr.satu.imagegate.conversion.service;

import co.kr.satu.imagegate.media.repository.MediaStore;
import co.kr.satu.imagegate.media.service.MediaAuthorizationPolicy;
import co.kr.satu.imagegate.system.config.ImageGateProperties;
import co.kr.satu.imagegate.system.service.FeaturePolicy;
import java.io.*;
import java.nio.file.*;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Test-source-only control at the actual renderer's package-local opener. Never a runtime bean/API. */
public final class ControlledOriginalOpenFixture {
    private final AtomicReference<Hold> next=new AtomicReference<>();
    private final Set<Hold> holds=ConcurrentHashMap.newKeySet();
    public final AtomicInteger calls=new AtomicInteger();
    public PreviewRenderer renderer(MediaStore store,MediaAuthorizationPolicy authorization,FeaturePolicy features,ImageGateProperties properties){
        return renderer(store,authorization,features,properties,PreviewMetrics.NONE);
    }
    public PreviewRenderer renderer(MediaStore store,MediaAuthorizationPolicy authorization,FeaturePolicy features,ImageGateProperties properties,PreviewMetrics metrics){
        return new ImagePreviewRenderer(store,authorization,features,properties.preview(),null,null,this::open,metrics);
    }
    public Hold arm(boolean fail){
        var hold=new Hold(fail);if(!next.compareAndSet(null,hold))throw new IllegalStateException("Fixture already armed");
        holds.add(hold);return hold;
    }
    private InputStream open(Path path)throws IOException {
        calls.incrementAndGet();var hold=next.getAndSet(null);
        if(hold!=null){
            hold.worker=Thread.currentThread();hold.entered.countDown();
            try{if(!hold.release.await(20,TimeUnit.SECONDS))throw new IOException("Synthetic hold expired");}
            catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw new InterruptedIOException("Synthetic native open interrupted");}
            if(hold.fail)throw new IOException("private-native-detail: "+path);
        }
        return Files.newInputStream(path);
    }
    public void releaseAll(){next.set(null);for(var hold:holds)hold.close();holds.clear();}
    public static final class Hold implements AutoCloseable {
        private final boolean fail;
        private final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        private volatile Thread worker;
        private Hold(boolean fail){this.fail=fail;}
        public void awaitEntered()throws InterruptedException {
            if(!entered.await(10,TimeUnit.SECONDS))throw new AssertionError("Native opener not entered");
        }
        public void interrupt(){var target=worker;if(target==null)throw new IllegalStateException("Not entered");target.interrupt();}
        @Override public void close(){release.countDown();}
    }
}
