package co.kr.satu.imagegate.conversion.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import co.kr.satu.imagegate.conversion.repository.*;
import co.kr.satu.imagegate.conversion.service.img2tile.*;
import co.kr.satu.imagegate.conversion.util.process.*;
import co.kr.satu.imagegate.media.repository.LocalMediaStore;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.system.config.ImageGateProperties;
import co.kr.satu.imagegate.system.service.FeaturePolicy;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;

/** Real single shared process permit + installed img2tile; only the occupying process is a controlled Java fixture. */
@Tag("img2tile") @EnabledOnOs(OS.WINDOWS)
class NativeCapacityIntegrationTests {
    @TempDir Path temp;
    void waitUntil(java.util.function.BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(10);assertThat(condition.getAsBoolean()).isTrue();}
    @Test @Timeout(90) void bothQueuesReportBusyWithoutAutomaticRetryAndRecoverWithOneSharedPermit()throws Exception{
        Path derived=Files.createDirectory(temp.resolve("derived")),holder=Files.createDirectory(temp.resolve("holder")),pid=holder.resolve("holder.pid");
        var processes=new BoundedProcessExecutor(1);var features=mock(FeaturePolicy.class);when(features.isEnabled(any())).thenReturn(true);
        var actor=new ViewerPrincipal("tenant","owner");var previewPolicy=new ImageGateProperties.Preview(true,1,1,64,64,100000,"capacity-test");
        var tilePolicy=new ImageGateProperties.Tiles(true,1,1,16,Duration.ofSeconds(30),100000,64,2097152,1048576,0,"capacity-test");
        try(var store=new LocalMediaStore(temp.resolve("originals"),1048576)){
            var image=new BufferedImage(64,32,BufferedImage.TYPE_INT_RGB);var data=new ByteArrayOutputStream();try{ImageIO.write(image,"TIFF",data);}finally{image.flush();}
            var media=store.register("tenant","owner","synthetic.tif",new ByteArrayInputStream(data.toByteArray()));
            var converter=new Img2TileConverter(Path.of(System.getenv().getOrDefault("IMG2TILE_EXECUTABLE","D:/DG_module/img2tile/img2tile.exe")),temp.resolve("originals"),derived,Duration.ofSeconds(30),processes);
            try(var tileCache=new TileCache(derived);var previews=new PreviewQueue(previewPolicy,converter.cacheIdentity(),new PreviewCache(derived),new ImagePreviewRenderer(store,(a,m,action)->true,features,previewPolicy,converter));
                    var tiles=new TileQueue(tilePolicy,converter.tileIdentity(),tileCache,new TileJobStore(derived,16),
                        new NativeTileRenderer(store,(a,m,action)->true,features,converter,new TilePyramidPublisher(new Img2TilePyramidValidator(),tileCache),tilePolicy,derived),derived);
                    var worker=Executors.newSingleThreadExecutor()){
                String cp=Path.of(ProcessFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();var finished=new CountDownLatch(1);
                var occupied=worker.submit(()->{try{processes.execute(List.of(Path.of(System.getProperty("java.home"),"bin/java.exe").toString(),"-cp",cp,ProcessFixture.class.getName(),"sleep",pid.toString()),holder,Duration.ofSeconds(30));}
                    catch(IOException expected){if(!Thread.currentThread().isInterrupted())throw new UncheckedIOException(expected);}finally{finished.countDown();}});
                try{
                    waitUntil(()->Files.exists(pid));previews.submit(media,actor,1,false);tiles.submit(media,actor,1,false);
                    waitUntil(()->previews.status(media,actor,1).state().equals("failed"));
                    long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!tiles.status(media,actor,1).state().equals("failed")&&System.nanoTime()<end)Thread.sleep(10);
                    assertThat(previews.status(media,actor,1).errorCode()).isEqualTo("CONVERSION_BUSY");assertThat(tiles.status(media,actor,1).error()).isEqualTo("CONVERSION_BUSY");
                    assertThat(previews.submit(media,actor,1,false).errorCode()).isEqualTo("CONVERSION_BUSY");assertThat(tiles.submit(media,actor,1,false).error()).isEqualTo("CONVERSION_BUSY");
                }finally{occupied.cancel(true);assertThat(finished.await(10,TimeUnit.SECONDS)).isTrue();}
                assertThat(ProcessHandle.of(Long.parseLong(Files.readString(pid))).map(ProcessHandle::isAlive).orElse(false)).isFalse();
                previews.submit(media,actor,1,true);waitUntil(()->previews.status(media,actor,1).ready());
                tiles.submit(media,actor,1,true);long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(!tiles.status(media,actor,1).state().equals("ready")&&System.nanoTime()<end)Thread.sleep(10);
                assertThat(tiles.status(media,actor,1).state()).isEqualTo("ready");assertThat(tileCache.manifest(tiles.key(media,actor,1)).layout().width()).isEqualTo(64);
            }
        }
    }
}
