package co.kr.satu.imagegate.conversion.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import co.kr.satu.imagegate.conversion.repository.TileCache;
import co.kr.satu.imagegate.conversion.service.img2tile.Img2TilePyramidValidator;
import co.kr.satu.imagegate.media.repository.*;
import co.kr.satu.imagegate.media.domain.MediaRecord;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.system.config.ImageGateProperties.Tiles;
import co.kr.satu.imagegate.system.service.FeaturePolicy;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
class NativeTileRendererTests {
    @TempDir Path temp;
    @ParameterizedTest @ValueSource(strings={"revoked","original","version","module","feature","none"})
    void diagnosticFailuresDoNotAlterPublicationOrTrustedInputRejection(String kind)throws Exception{
        var local=new LocalMediaStore(temp.resolve("originals"),1000000);var data=new ByteArrayOutputStream();var image=new BufferedImage(1,1,BufferedImage.TYPE_INT_RGB);
        try{ImageIO.write(image,"TIFF",data);}finally{image.flush();}
        var media=local.register("tenant","alice","x.tif",new ByteArrayInputStream(data.toByteArray()));var current=new AtomicReference<>(media);
        var allowed=new AtomicBoolean(true);var feature=new AtomicBoolean(true);var identity=new AtomicReference<>(Img2TilePyramidValidator.MODULE);
        var store=mock(MediaStore.class);when(store.find("tenant",media.mediaId())).thenAnswer(call->Optional.of(current.get()));when(store.resolveOriginal(media)).thenReturn(local.resolveOriginal(media));
        var features=mock(FeaturePolicy.class);when(features.isEnabled(any())).thenAnswer(call->feature.get());
        var converter=new TileNativeConverter(){public String tileIdentity(){return identity.get();}
            public Path generateTiles(Path input,int page,Path work)throws IOException{
                Path output=Files.createDirectories(work.resolve("output/mipmap_files/0"));
                var tile=new BufferedImage(1,1,BufferedImage.TYPE_INT_RGB);try{ImageIO.write(tile,"PNG",output.resolve("0_0.png").toFile());}finally{tile.flush();}
                Files.writeString(work.resolve("output/mipmap.dzi"),"<Image xmlns=\"http://schemas.microsoft.com/deepzoom/2008\" Format=\"png\" Overlap=\"1\" TileSize=\"256\"><Size Width=\"1\" Height=\"1\"/></Image>");
                switch(kind){case "revoked"->allowed.set(false);case "feature"->feature.set(false);case "module"->identity.set("changed");
                    case "version"->current.set(new MediaRecord(media.mediaId(),media.tenantId(),media.ownerId(),"changed",media.fileName(),media.mediaType(),media.mimeType(),media.sizeBytes(),media.sha256(),media.storedFileName(),media.createdAt()));
                    case "original"->{byte[] bytes=Files.readAllBytes(input);bytes[bytes.length-1]^=1;Files.write(input,bytes);}}
                return work.resolve("output");
            }};
        Path converted=Files.createDirectory(temp.resolve("converted"));
        var observations=new AtomicInteger();co.kr.satu.imagegate.conversion.util.TileTiming timing=(s,n)->{observations.incrementAndGet();throw new IllegalStateException("observer failed");};
        try(var cache=new TileCache(converted,timing)){
            var work=new co.kr.satu.imagegate.conversion.repository.TileWorkStore(converted,4096);
            var validator=new Img2TilePyramidValidator(new Img2TilePyramidValidator.Limits(40000000,4096,2097152,268435456),timing);
            var renderer=new NativeTileRenderer(store,(a,m,action)->allowed.get(),features,converter,new TilePyramidPublisher(validator,cache,timing),Tiles.defaults(),converted,work,timing);
            if(kind.equals("none")){renderer.render(media,new ViewerPrincipal("tenant","alice"),1,"a".repeat(64),action->action.run());assertThat(cache.manifest("a".repeat(64)).page()).isEqualTo(1);}
            else{assertThatThrownBy(()->renderer.render(media,new ViewerPrincipal("tenant","alice"),1,"a".repeat(64),action->action.run())).isInstanceOf(IOException.class);
                assertThat(converted.resolve("tiles-v1/"+"a".repeat(64))).doesNotExist();}
            assertThat(observations.get()).isPositive();
            try(var files=Files.list(converted.resolve("tile-work-v1"))){assertThat(files.toList()).isEmpty();}
        }
    }
    @ParameterizedTest @ValueSource(ints={BufferedImage.TYPE_BYTE_GRAY,BufferedImage.TYPE_INT_ARGB})
    void rejectsUnsupportedOriginalProfileBeforeNativeExecution(int type)throws Exception{
        var local=new LocalMediaStore(temp.resolve("originals"),1000000);var data=new ByteArrayOutputStream();var image=new BufferedImage(1,1,type);
        try{ImageIO.write(image,"TIFF",data);}finally{image.flush();}
        var media=local.register("tenant","alice","x.tif",new ByteArrayInputStream(data.toByteArray()));
        var features=mock(FeaturePolicy.class);when(features.isEnabled(any())).thenReturn(true);var nativeConverter=mock(TileNativeConverter.class);
        when(nativeConverter.tileIdentity()).thenReturn(Img2TilePyramidValidator.MODULE);Path converted=Files.createDirectory(temp.resolve("converted"));
        try(var cache=new TileCache(converted)){
            var renderer=new NativeTileRenderer(local,(a,m,action)->true,features,nativeConverter,new TilePyramidPublisher(new Img2TilePyramidValidator(),cache),Tiles.defaults(),converted);
            assertThatThrownBy(()->renderer.render(media,new ViewerPrincipal("tenant","alice"),1,"a".repeat(64),action->action.run())).isInstanceOf(UnsupportedOperationException.class);
            verify(nativeConverter,never()).generateTiles(any(),anyInt(),any());
        }
    }
}
