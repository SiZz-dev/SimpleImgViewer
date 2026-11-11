package co.kr.satu.imagegate.conversion.service;
import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.conversion.repository.*;
import co.kr.satu.imagegate.conversion.domain.TileJobStatus;
import co.kr.satu.imagegate.media.domain.*;
import co.kr.satu.imagegate.media.domain.MediaType;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.system.config.ImageGateProperties.Tiles;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
class TileQueueTests {
    @TempDir Path temp;
    ViewerPrincipal actor=new ViewerPrincipal("tenant","alice");
    MediaRecord media(String id){return new MediaRecord(id,"tenant","alice","v1","x.tif",MediaType.IMAGE,"image/tiff",100,"a".repeat(64),"x",Instant.EPOCH);}
    Tiles policy(Duration timeout){return new Tiles(true,1,1,16,timeout,1000,20,1000,10000,0,"v1");}
    TileJobStatus await(TileQueue queue,MediaRecord media,String state)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<end){var status=queue.status(media,actor,1);if(status.state().equals(state))return status;Thread.sleep(10);}
        throw new AssertionError("Expected "+state);
    }
    @Test void nativeBusyIsPersistentAndOnlyExplicitRetryReentersTheWorker()throws Exception{
        var calls=new AtomicInteger();try(var cache=new TileCache(temp)){
            var states=new TileJobStore(temp,16);var renderer=new TileRenderer(){public void verifyAccess(MediaRecord m,ViewerPrincipal a){}
                public void render(MediaRecord m,ViewerPrincipal a,int p,String key,TilePyramidPublisher.CommitGate gate)throws IOException{
                    calls.incrementAndGet();throw new co.kr.satu.imagegate.conversion.exception.ConversionException(
                        co.kr.satu.imagegate.conversion.exception.ConversionException.Code.BUSY,"private-native-path-and-output");
                }};
            var one=media("one");try(var queue=new TileQueue(policy(Duration.ofSeconds(10)),"module",cache,states,renderer,temp)){
                queue.submit(one,actor,1,false);assertThat(await(queue,one,"failed").error()).isEqualTo("CONVERSION_BUSY");
                assertThat(new TileJobStore(temp,16).read(queue.key(one,actor,1),1).error()).isEqualTo("CONVERSION_BUSY");
                for(int i=0;i<3;i++){assertThat(queue.submit(one,actor,1,false).error()).isEqualTo("CONVERSION_BUSY");queue.view(one,actor,1);}
                assertThat(calls).hasValue(1);queue.submit(one,actor,1,true);assertThat(await(queue,one,"failed").error()).isEqualTo("CONVERSION_BUSY");assertThat(calls).hasValue(2);
            }
        }
    }
    @Test void executorRejectionReleasesAdmissionAndNeverLeavesAnOrphanQueuedState()throws Exception{
        try(var cache=new TileCache(temp)){
            var states=new TileJobStore(temp,16);var renderer=org.mockito.Mockito.mock(TileRenderer.class);
            try(var queue=new TileQueue(policy(Duration.ofSeconds(10)),"module",cache,states,renderer,temp)){
                // Inject executor rejection without changing the production queue API or racing a real worker.
                var field=TileQueue.class.getDeclaredField("workers");field.setAccessible(true);((ThreadPoolExecutor)field.get(queue)).shutdown();
                var one=media("one");assertThatThrownBy(()->queue.submit(one,actor,1,false)).isInstanceOfSatisfying(
                    co.kr.satu.imagegate.conversion.exception.TileAdmissionException.class,e->assertThat(e.code()).isEqualTo(co.kr.satu.imagegate.conversion.exception.TileAdmissionException.Code.CAPACITY));
                var failed=states.read(queue.key(one,actor,1),1);assertThat(failed.state()).isEqualTo("failed");assertThat(failed.error()).isEqualTo("TILE_QUEUE_FULL");
                assertThat(queue.submit(one,actor,1,false)).isEqualTo(failed);org.mockito.Mockito.verify(renderer,org.mockito.Mockito.never()).render(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
            }
        }
        try(var reopened=new TileCache(temp)){assertThat(reopened.find("a".repeat(64))).isEmpty();}
    }
    @Test void boundsAndDeduplicatesCancelsQueuedAndRequiresRetry()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        try(var cache=new TileCache(temp)){
            var states=new TileJobStore(temp,16);var renderer=new TileRenderer(){
                public void verifyAccess(MediaRecord m,ViewerPrincipal a){}
                public void render(MediaRecord m,ViewerPrincipal a,int p,String key,TilePyramidPublisher.CommitGate gate)throws IOException{
                    calls.incrementAndGet();entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){throw new InterruptedIOException();}
                    throw new IOException("fixture failure");
                }
            };
            try(var queue=new TileQueue(policy(Duration.ofSeconds(10)),"module",cache,states,renderer,temp)){
                var one=media("one");var two=media("two");queue.submit(one,actor,1,false);assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
                assertThat(queue.submit(one,actor,1,false).state()).isEqualTo("running");queue.submit(two,actor,1,false);
                assertThatThrownBy(()->queue.submit(media("three"),actor,1,false)).isInstanceOf(IOException.class);
                queue.cancel(two,actor,1);assertThat(queue.status(two,actor,1).error()).isEqualTo("INTERRUPTED");
                assertThat(queue.submit(two,actor,1,false).state()).isEqualTo("failed");
                release.countDown();await(queue,one,"failed");assertThat(calls).hasValue(1);
                queue.submit(two,actor,1,true);await(queue,two,"failed");assertThat(calls).hasValue(2);
            }finally{release.countDown();}
        }
    }
    @Test void deadlineAndCancellationBlockLateCommitEvenWhenWorkerIgnoresInterrupt()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var committed=new AtomicBoolean();
        try(var cache=new TileCache(temp)){
            var renderer=new TileRenderer(){public void verifyAccess(MediaRecord m,ViewerPrincipal a){}
                public void render(MediaRecord m,ViewerPrincipal a,int p,String key,TilePyramidPublisher.CommitGate gate)throws IOException{
                    entered.countDown();boolean done=false;while(!done)try{done=release.await(10,TimeUnit.SECONDS);}catch(InterruptedException ignored){}
                    gate.commit(()->committed.set(true));
                }};
            try(var queue=new TileQueue(policy(Duration.ofMillis(100)),"module",cache,new TileJobStore(temp,16),renderer,temp)){
                var m=media("timeout");queue.submit(m,actor,1,false);assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();Thread.sleep(180);release.countDown();
                assertThat(await(queue,m,"failed").error()).isEqualTo("TIMEOUT");assertThat(committed).isFalse();
            }finally{release.countDown();}
        }
    }
    @Test void closeRetainsWriterUntilUncooperativeWorkerReleasesLease()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var exited=new CountDownLatch(1);var cache=new TileCache(temp);
        var renderer=new TileRenderer(){public void verifyAccess(MediaRecord m,ViewerPrincipal a){}
            public void render(MediaRecord m,ViewerPrincipal a,int p,String key,TilePyramidPublisher.CommitGate gate)throws IOException{
                entered.countDown();boolean done=false;while(!done)try{done=release.await(10,TimeUnit.SECONDS);}catch(InterruptedException ignored){}
                try{gate.commit(()->{throw new AssertionError("late commit");});}finally{exited.countDown();}
            }};
        var queue=new TileQueue(policy(Duration.ofSeconds(10)),"module",cache,new TileJobStore(temp,16),renderer,temp);
        try{
            queue.submit(media("closing"),actor,1,false);assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();queue.close();
            assertThatThrownBy(()->new TileCache(temp)).isInstanceOf(IOException.class);release.countDown();assertThat(exited.await(10,TimeUnit.SECONDS)).isTrue();
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);boolean reopened=false;
            while(!reopened&&System.nanoTime()<end){try(var next=new TileCache(temp)){reopened=true;}catch(IOException busy){Thread.sleep(10);}}
            assertThat(reopened).isTrue();
        }finally{release.countDown();queue.close();cache.close();}
    }
    @Test void restartRequiresExplicitRetryAndAuthorizationPrecedesStateReads()throws Exception{
        try(var cache=new TileCache(temp)){
            var allowed=new AtomicBoolean(true);var calls=new AtomicInteger();
            var renderer=new TileRenderer(){public void verifyAccess(MediaRecord m,ViewerPrincipal a)throws IOException{if(!allowed.get())throw new IOException("denied");}
                public void render(MediaRecord m,ViewerPrincipal a,int p,String key,TilePyramidPublisher.CommitGate gate)throws IOException{calls.incrementAndGet();throw new IOException();}};
            var states=new TileJobStore(temp,16);var m=media("restart");
            try(var queue=new TileQueue(policy(Duration.ofSeconds(10)),"module",cache,states,renderer,temp)){
                states.write(new TileJobStatus(queue.key(m,actor,1),1,"running",null));
                assertThat(queue.submit(m,actor,1,false).error()).isEqualTo("INTERRUPTED");assertThat(calls).hasValue(0);
                allowed.set(false);assertThatThrownBy(()->queue.status(m,actor,1)).isInstanceOf(IOException.class);
                assertThatThrownBy(()->queue.submit(m,actor,1,true)).isInstanceOf(IOException.class);allowed.set(true);
                queue.submit(m,actor,1,true);await(queue,m,"failed");assertThat(calls).hasValue(1);
            }
        }
    }
    @Test @Timeout(30) void publicationAndCollidingSubmissionCannotInvertQueueAndCacheLocks()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var attempting=new CountDownLatch(1);
        try(var cache=new TileCache(temp)){
            var image=new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_ARGB);var output=new ByteArrayOutputStream();
            try{javax.imageio.ImageIO.write(image,"PNG",output);}finally{image.flush();}byte[] png=output.toByteArray();
            var tile=new co.kr.satu.imagegate.conversion.domain.TilePyramidManifest.Tile(0,0,0,1,1,png.length,co.kr.satu.imagegate.conversion.util.PreviewKeys.sha256(png));
            var source=new co.kr.satu.imagegate.conversion.domain.TilePyramidManifest(1,"img2tile-tiff-rgb8-o1-v1",co.kr.satu.imagegate.conversion.service.img2tile.Img2TilePyramidValidator.MODULE,1,new co.kr.satu.imagegate.conversion.domain.TilePyramidLayout(1,1,256,0),"original-upright-pixel-edges","b".repeat(64),png.length+100,java.util.List.of(tile));
            var renderer=new TileRenderer(){public void verifyAccess(MediaRecord m,ViewerPrincipal a){}
                public void render(MediaRecord m,ViewerPrincipal a,int page,String key,TilePyramidPublisher.CommitGate gate)throws IOException{
                    if(!m.mediaId().equals("first"))throw new IOException("fixture");
                    cache.publish(key,source,new TileCache.SourceTiles(){public byte[] normalize(co.kr.satu.imagegate.conversion.domain.TilePyramidManifest.Tile t){return png;}
                        public void verifyBeforeCommit()throws IOException{entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IOException("timeout");}catch(InterruptedException e){throw new IOException(e);}}
                        public void commit(TileCache.CommitAction action)throws IOException{gate.commit(action);}});
                }};
            try(var queue=new TileQueue(policy(Duration.ofSeconds(20)),"module",cache,new TileJobStore(temp,16),renderer,temp)){
                var first=media("first");int bucket=queue.key(first,actor,1).hashCode()&63;MediaRecord collision=null;
                for(int i=0;i<10000;i++){var candidate=media("other"+i);if((queue.key(candidate,actor,1).hashCode()&63)==bucket){collision=candidate;break;}}
                assertThat(collision).isNotNull();var second=collision;queue.submit(first,actor,1,false);assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
                var executor=Executors.newSingleThreadExecutor();
                try{var submitted=executor.submit(()->{attempting.countDown();return queue.submit(second,actor,1,false);});assertThat(attempting.await(10,TimeUnit.SECONDS)).isTrue();
                    assertThat(queue.status(first,actor,1).state()).isEqualTo("running");release.countDown();submitted.get(10,TimeUnit.SECONDS);
                    assertThat(await(queue,first,"ready").state()).isEqualTo("ready");
                }finally{release.countDown();executor.shutdownNow();}
            }
        }finally{release.countDown();}
    }
}
