package co.kr.satu.imagegate.playback.config;

import co.kr.satu.imagegate.playback.repository.PlaybackCache;
import co.kr.satu.imagegate.playback.service.*;
import co.kr.satu.imagegate.playback.util.PlaybackFiles;
import co.kr.satu.imagegate.system.config.ImageGateProperties;
import co.kr.satu.imagegate.conversion.util.process.BoundedProcessExecutor;
import java.io.IOException;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.*;

@Configuration(proxyBeanMethods=false)
public class PlaybackConfiguration {
    @Bean(destroyMethod="") PlaybackCache playbackCache(ImageGateProperties p) throws IOException {
        return new PlaybackCache(p.storage().convertedPath().resolve("playback-v1"),p.playback().maxOutputBytes());
    }
    @Bean @ConditionalOnMissingBean(PlaybackEngine.class) @ConditionalOnProperty(name="imagegate.playback.enabled",havingValue="true")
    PlaybackEngine playbackEngine(ImageGateProperties p) throws IOException {
        var work=PlaybackFiles.root(p.storage().convertedPath().resolve("playback-tool-check"));
        return new FfmpegEngine(p.playback(),new BoundedProcessExecutor(p.playback().workers()),work);
    }
}
