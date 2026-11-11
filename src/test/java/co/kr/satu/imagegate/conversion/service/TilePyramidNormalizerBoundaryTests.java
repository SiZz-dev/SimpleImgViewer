package co.kr.satu.imagegate.conversion.service;

import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.conversion.repository.TileCache;
import co.kr.satu.imagegate.conversion.service.img2tile.Img2TilePyramidValidator;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.atomic.*;
import javax.imageio.*;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TilePyramidNormalizerBoundaryTests {
    @TempDir Path temp;
    final String key="a".repeat(64);
    final Img2TilePyramidValidator.Source source=new Img2TilePyramidValidator.Source(1,1,1,"TIFF_RGB",1,8,3,Img2TilePyramidValidator.MODULE);
    Path output()throws Exception{
        Path root=Files.createDirectory(temp.resolve("native")),level=Files.createDirectories(root.resolve("mipmap_files/0"));
        Files.writeString(root.resolve("mipmap.dzi"),"<Image xmlns=\"http://schemas.microsoft.com/deepzoom/2008\" Format=\"png\" Overlap=\"1\" TileSize=\"256\"><Size Width=\"1\" Height=\"1\"/></Image>");
        var image=new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB);var writer=ImageIO.getImageWritersByFormatName("PNG").next();
        try(var file=Files.newOutputStream(level.resolve("0_0.png"));var stream=new MemoryCacheImageOutputStream(file)){
            writer.setOutput(stream);var param=writer.getDefaultWriteParam();var metadata=writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image),param);
            var tree=(IIOMetadataNode)metadata.getAsTree("javax_imageio_png_1.0");var text=new IIOMetadataNode("tEXt");var entry=new IIOMetadataNode("tEXtEntry");entry.setAttribute("keyword","Comment");entry.setAttribute("value","synthetic-private-note");text.appendChild(entry);tree.appendChild(text);metadata.setFromTree("javax_imageio_png_1.0",tree);writer.write(null,new IIOImage(image,null,metadata),param);
        }finally{writer.dispose();image.flush();}return root;
    }
    @ParameterizedTest @ValueSource(strings={"corrupt","ancillary","oversize","null"})
    void alternateNormalizerCannotBypassPublicCacheValidation(String kind)throws Exception{
        Path root=output();var called=new AtomicInteger();var committed=new AtomicBoolean();
        try(var cache=new TileCache(temp.resolve("derived"))){
            var publisher=new TilePyramidPublisher(new Img2TilePyramidValidator(),cache,null,(bytes,w,h,timing)->{
                called.incrementAndGet();return switch(kind){case "corrupt"->new byte[100];case "oversize"->new byte[2097153];case "null"->null;default->bytes;};
            });
            assertThatThrownBy(()->publisher.publish(key,root,source,()->{},action->{committed.set(true);action.run();})).isInstanceOf(IOException.class);
            assertThat(called).hasValue(1);assertThat(committed).isFalse();assertThat(cache.find(key)).isEmpty();
        }
    }
    @Test void nativeValidationStillPrecedesAnyNormalizer()throws Exception{
        Path root=output();Path tile=root.resolve("mipmap_files/0/0_0.png");byte[] bytes=Files.readAllBytes(tile);bytes[bytes.length-1]^=1;Files.write(tile,bytes);var called=new AtomicBoolean();
        try(var cache=new TileCache(temp.resolve("derived"))){var publisher=new TilePyramidPublisher(new Img2TilePyramidValidator(),cache,null,(input,w,h,timing)->{called.set(true);return input;});
            assertThatThrownBy(()->publisher.publish(key,root,source)).isInstanceOf(IOException.class);assertThat(called).isFalse();assertThat(cache.find(key)).isEmpty();}
    }
}
