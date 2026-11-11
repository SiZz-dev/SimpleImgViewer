package co.kr.satu.imagegate.conversion.config;
import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.conversion.service.TileQueue;
import co.kr.satu.imagegate.conversion.repository.TileCache;
import co.kr.satu.imagegate.media.repository.*;
import co.kr.satu.imagegate.media.service.MediaAuthorizationPolicy;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.system.config.ImageGateProperties;
import co.kr.satu.imagegate.system.service.FeaturePolicy;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
@Tag("img2tile") @EnabledOnOs(OS.WINDOWS)
class TileConfigurationIntegrationTests {
    @TempDir Path temp;
    ApplicationContextRunner runner(){return new ApplicationContextRunner().withInitializer(context->{
        try{var loader=new org.springframework.boot.env.YamlPropertySourceLoader();for(var source:loader.load("defaults",new org.springframework.core.io.ClassPathResource("application.yml")))context.getEnvironment().getPropertySources().addLast(source);}
        catch(IOException e){throw new IllegalStateException(e);}
    }).withUserConfiguration(Config.class).withPropertyValues("imagegate.storage.root="+temp,"imagegate.tiles.min-free-bytes=0");}
    @Configuration(proxyBeanMethods=false) @EnableConfigurationProperties(ImageGateProperties.class)
    @Import({TileConfiguration.class,ConversionConfiguration.class,FeaturePolicy.class}) static class Config{}
    @Test void disabledPolicyDoesNotCreateTileStorage(){
        runner().run(context->{assertThat(context).hasNotFailed().doesNotHaveBean(TileQueue.class).doesNotHaveBean(TileCache.class);});
        assertThat(temp.resolve("derived/tiles-v1")).doesNotExist();
    }
    @Test @Timeout(120) void registeredOriginalUsesRealSharedConverterQueueAndGuardedPublication()throws Exception{
        var store=new LocalMediaStore(temp.resolve("originals"),1000000);var bytes=new ByteArrayOutputStream();var image=new BufferedImage(64,32,BufferedImage.TYPE_INT_RGB);
        try{image.setRGB(0,0,0xff123456);ImageIO.write(image,"TIFF",bytes);}finally{image.flush();}
        var media=store.register("tenant","alice","sample.tif",new ByteArrayInputStream(bytes.toByteArray()));var actor=new ViewerPrincipal("tenant","alice");var allowed=new AtomicBoolean(true);
        runner().withPropertyValues("imagegate.conversion.enabled=true","imagegate.tiles.enabled=true",
                "imagegate.conversion.modules.img2tile.executable="+System.getenv().getOrDefault("IMG2TILE_EXECUTABLE","D:/DG_module/img2tile/img2tile.exe"))
                .withBean(MediaStore.class,()->store).withBean(MediaAuthorizationPolicy.class,()->(a,m,action)->allowed.get()).run(context->{
                    assertThat(context).hasNotFailed().hasSingleBean(TileQueue.class);var queue=context.getBean(TileQueue.class);
                    assertThat(queue.status(media,actor,1).state()).isEqualTo("not_requested");
                    var accepted=queue.submit(media,actor,1,false);long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(90);
                    var state=accepted;while(System.nanoTime()<end){state=queue.status(media,actor,1);if(state.state().equals("ready")||state.state().equals("failed"))break;Thread.sleep(20);}
                    assertThat(state.state()).as("%s",state).isEqualTo("ready");var cache=context.getBean(TileCache.class);var manifest=cache.manifest(accepted.key());
                    assertThat(manifest.layout().width()).isEqualTo(64);assertThat(manifest.tiles()).hasSize(7);
                    cache.withTile(accepted.key(),manifest.generation(),manifest.layout().maxLevel(),0,0,(in,tile)->{
                        var decoded=ImageIO.read(in);try{assertThat(decoded.getRGB(0,0)).isEqualTo(0xff123456);}finally{decoded.flush();}
                    });
                    allowed.set(false);assertThatThrownBy(()->queue.status(media,actor,1)).isInstanceOf(IOException.class);
                    assertThatThrownBy(()->queue.submit(media,actor,1,true)).isInstanceOf(IOException.class);
                    try(var work=Files.list(temp.resolve("derived/tile-work-v1"))){assertThat(work.toList()).isEmpty();}
                });
    }
}
