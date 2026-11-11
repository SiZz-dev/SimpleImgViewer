package co.kr.satu.imagegate.conversion.config;
import co.kr.satu.imagegate.conversion.repository.*;
import co.kr.satu.imagegate.conversion.service.*;
import co.kr.satu.imagegate.conversion.service.img2tile.Img2TilePyramidValidator;
import co.kr.satu.imagegate.media.repository.MediaStore;
import co.kr.satu.imagegate.media.service.MediaAuthorizationPolicy;
import co.kr.satu.imagegate.system.config.ImageGateProperties;
import co.kr.satu.imagegate.system.service.FeaturePolicy;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="imagegate.tiles.enabled",havingValue="true")
public class TileConfiguration {
    @Bean(destroyMethod="close") TileCache tileCache(ImageGateProperties p)throws IOException{return new TileCache(p.storage().convertedPath());}
    @Bean TileJobStore tileJobStore(ImageGateProperties p,TileCache cache)throws IOException{return new TileJobStore(p.storage().convertedPath(),p.tiles().maxTrackedJobs());}
    @Bean TileWorkStore tileWorkStore(ImageGateProperties p,TileCache cache)throws IOException{return new TileWorkStore(p.storage().convertedPath(),p.tiles().maxTiles(),p.tiles().maintenance().maxScanEntries());}
    @Bean(initMethod="start",destroyMethod="close") TileMaintenanceService tileMaintenanceService(ImageGateProperties p,TileCache cache,TileJobStore states,TileWorkStore work)throws IOException{
        return new TileMaintenanceService(cache,states,work,p.tiles());
    }
    @Bean NativeTileRenderer nativeTileRenderer(ImageGateProperties p,MediaStore store,MediaAuthorizationPolicy authorization,FeaturePolicy features,TileNativeConverter converter,TileCache cache,TileWorkStore work)throws IOException{
        var t=p.tiles();var validator=new Img2TilePyramidValidator(new Img2TilePyramidValidator.Limits(t.maxSourcePixels(),t.maxTiles(),t.maxTileBytes(),t.maxPageBytes()));
        return new NativeTileRenderer(store,authorization,features,converter,new TilePyramidPublisher(validator,cache),t,p.storage().convertedPath(),work);
    }
    @Bean(destroyMethod="close") TileQueue tileQueue(ImageGateProperties p,TileNativeConverter converter,TileCache cache,TileJobStore states,NativeTileRenderer renderer,TileMaintenanceService maintenance)throws IOException{
        return new TileQueue(p.tiles(),converter.tileIdentity(),cache,states,renderer,p.storage().convertedPath(),p.audit().enabled());
    }
}
