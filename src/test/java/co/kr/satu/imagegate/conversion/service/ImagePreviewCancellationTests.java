package co.kr.satu.imagegate.conversion.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import co.kr.satu.imagegate.conversion.util.PreviewKeys;
import co.kr.satu.imagegate.conversion.repository.PreviewCache;
import co.kr.satu.imagegate.media.domain.*;
import co.kr.satu.imagegate.media.repository.MediaStore;
import co.kr.satu.imagegate.media.service.MediaAuthorizationPolicy;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.system.config.ImageGateProperties.Preview;
import co.kr.satu.imagegate.system.service.FeaturePolicy;
import java.awt.image.*;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.imageio.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ImagePreviewCancellationTests {
    @TempDir Path root;
    final MediaStore store=mock(MediaStore.class);
    final MediaAuthorizationPolicy access=mock(MediaAuthorizationPolicy.class);
    final FeaturePolicy features=mock(FeaturePolicy.class);
    final ViewerPrincipal actor=new ViewerPrincipal("tenant","alice");
    final Preview profile=new Preview(true,1,1,32,32,10000,"v1");
    MediaRecord media;Path input,work;
    @BeforeEach void setup() throws Exception {
        input=root.resolve("source.png");work=root.resolve("work");
        ImageIO.write(new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB),"png",input.toFile());
        media=new MediaRecord(UUID.randomUUID().toString(),"tenant","alice","version","source.png",MediaType.IMAGE,
                "image/png",Files.size(input),HexFormat.of().formatHex(PreviewKeys.digest().digest(Files.readAllBytes(input))),"source.png",Instant.EPOCH);
        when(store.find("tenant",media.mediaId())).thenReturn(Optional.of(media));
        when(store.resolveOriginal(media)).thenReturn(input);
        when(access.permits(any(),any(),any())).thenReturn(true);
        when(features.isEnabled(any())).thenReturn(true);
    }
    @AfterEach void clearInterrupt(){Thread.interrupted();}
    ImagePreviewRenderer renderer(ImageConverter converter){return new ImagePreviewRenderer(store,access,features,profile,converter);}
    void cancelled(ImagePreviewRenderer renderer) {
        assertThatThrownBy(()->renderer.render(media,actor,1,work)).isInstanceOf(InterruptedIOException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }
    @Test void alreadyInterruptedRequestDoesNotReadStorageOrAllocateWork() {
        Thread.currentThread().interrupt();cancelled(renderer(null));
        verifyNoInteractions(store,access,features);assertThat(work).doesNotExist();
    }
    @Test void cancellationDuringAccessCheckStopsOriginalRead() throws Exception {
        when(access.permits(any(),any(),any())).thenAnswer(c->{Thread.currentThread().interrupt();return true;});
        cancelled(renderer(null));verify(store,never()).resolveOriginal(any());assertThat(work).doesNotExist();
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void cancellationDuringConverterSelectionOrConversionStopsNextStage(boolean duringConversion) throws Exception {
        var converter=mock(ImageConverter.class);
        when(converter.supportedMimeTypes()).thenAnswer(c->{
            if(!duringConversion)Thread.currentThread().interrupt();return Set.of("image/png");
        });
        when(converter.convert(any())).thenAnswer(c->{
            Thread.currentThread().interrupt();return new ImageConverter.ConversionResult(root.resolve("not-created.png"),16,16,"image/png");
        });
        cancelled(renderer(converter));
        if(duringConversion)verify(converter).convert(any());else verify(converter,never()).convert(any());
        assertThat(work.resolve("preview.png")).doesNotExist();assertThat(work.resolve("thumbnail.png")).doesNotExist();
    }

    ImageReader reader(BufferedImage raster) throws Exception {
        var reader=mock(ImageReader.class);when(reader.getWidth(0)).thenReturn(16);when(reader.getHeight(0)).thenReturn(16);
        when(reader.read(0)).thenReturn(raster);return reader;
    }
    void supply(org.mockito.MockedStatic<ImageIO> io,ImageReader reader){
        io.when(()->ImageIO.getImageReaders(any())).thenReturn(List.of(reader).iterator());
    }
    static final class Raster extends BufferedImage {
        int flushes;boolean cancelOnFlush;
        Raster(int size){super(size,size,BufferedImage.TYPE_INT_ARGB);}
        @Override public void flush(){flushes++;super.flush();if(cancelOnFlush)Thread.currentThread().interrupt();}
    }

    @Test void metadataCancellationStopsPixelDecodeAndDisposesReader() throws Exception {
        var reader=reader(new Raster(16));
        when(reader.getWidth(0)).thenAnswer(c->{Thread.currentThread().interrupt();return 16;});
        try(var io=mockStatic(ImageIO.class)){
            supply(io,reader);cancelled(renderer(null));verify(reader,never()).read(anyInt());verify(reader).dispose();
        }
        Thread.interrupted();Files.delete(input);assertThat(work.resolve("preview.png")).doesNotExist();
    }
    @Test void cancellationAfterPixelDecodeFlushesRasterAndDoesNotEncode() throws Exception {
        var raster=new Raster(16);var reader=reader(raster);
        when(reader.read(0)).thenAnswer(c->{Thread.currentThread().interrupt();return raster;});
        try(var io=mockStatic(ImageIO.class)){
            supply(io,reader);cancelled(renderer(null));verify(reader).dispose();
            io.verify(()->ImageIO.write(any(RenderedImage.class),anyString(),any(File.class)),never());
        }
        assertThat(raster.flushes).isEqualTo(1);
    }
    @Test void cancellationAfterScalingStopsPngAndThumbnailEncoding() throws Exception {
        var raster=new Raster(16);raster.cancelOnFlush=true;var reader=reader(raster);
        try(var io=mockStatic(ImageIO.class)){
            supply(io,reader);cancelled(renderer(null));
            io.verify(()->ImageIO.write(any(RenderedImage.class),anyString(),any(File.class)),never());
        }
        assertThat(raster.flushes).isEqualTo(1);
        assertThat(work.resolve("preview.png")).doesNotExist();
    }
    @Test void cancellationAfterPngWriteStopsThumbnailAndArtifactReturn() throws Exception {
        var raster=new Raster(16);var reader=reader(raster);var writes=new AtomicInteger();
        try(var io=mockStatic(ImageIO.class)){
            supply(io,reader);
            io.when(()->ImageIO.write(any(RenderedImage.class),eq("png"),any(File.class))).thenAnswer(c->{
                writes.incrementAndGet();Files.write(((File)c.getArgument(2)).toPath(),new byte[]{1,2,3});
                Thread.currentThread().interrupt();return true;
            });
            cancelled(renderer(null));assertThat(writes).hasValue(1);
        }
        assertThat(raster.flushes).isEqualTo(1);
        assertThat(work.resolve("preview.png")).exists();assertThat(work.resolve("thumbnail.png")).doesNotExist();
    }
    @Test void invalidDecodedRasterIsFlushedOnValidationFailure() throws Exception {
        var raster=new Raster(8);var reader=reader(raster);
        try(var io=mockStatic(ImageIO.class)){
            supply(io,reader);
            assertThatThrownBy(()->renderer(null).render(media,actor,1,work)).isInstanceOf(IOException.class).hasMessage("Unexpected decoder raster");
            verify(reader).dispose();
        }
        assertThat(raster.flushes).isEqualTo(1);assertThat(work.resolve("preview.png")).doesNotExist();
    }
    @Test void finalAccessCancellationCannotReturnCompletedFiles() throws Exception {
        var calls=new AtomicInteger();
        when(access.permits(any(),any(),any())).thenAnswer(c->{if(calls.incrementAndGet()==2)Thread.currentThread().interrupt();return true;});
        cancelled(renderer(null));assertThat(work.resolve("preview.png")).exists();
    }
    @Test void queueCleansCancelledNativeWorkAndAnExplicitRetrySucceeds() throws Exception {
        var converter=mock(ImageConverter.class);when(converter.supportedMimeTypes()).thenReturn(Set.of("image/png"));
        var first=new AtomicBoolean(true);var observedFlag=new AtomicBoolean();
        when(converter.convert(any())).thenAnswer(c->{
            ImageConverter.ConversionRequest request=c.getArgument(0);Files.createDirectory(request.outputDirectory());
            Path png=Files.copy(input,request.outputDirectory().resolve("converted.png"));
            if(first.getAndSet(false))Thread.currentThread().interrupt();
            return new ImageConverter.ConversionResult(png,16,16,"image/png");
        });
        var renderer=renderer(converter);String key;
        try(var cache=new PreviewCache(root.resolve("converted"));
                var queue=new PreviewQueue(profile,"test",cache,(m,a,p,w)->{
                    try{return renderer.render(m,a,p,w);}finally{observedFlag.set(observedFlag.get()||Thread.currentThread().isInterrupted());}
                })){
            key=queue.submit(media,actor,1,false).sourceId();
            await(queue,"failed");assertThat(queue.status(media,actor,1).errorCode()).isEqualTo("INTERRUPTED");
            assertThat(observedFlag).isTrue();
            long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<end){
                queue.submit(media,actor,1,true);if(queue.status(media,actor,1).ready())break;Thread.sleep(10);
            }
            assertThat(queue.status(media,actor,1).ready()).isTrue();
        }
        try(var files=Files.walk(root.resolve("converted/previews-v1"))){
            assertThat(files.noneMatch(p->p.getFileName().toString().startsWith("work-"))).isTrue();
        }
        assertThat(HexFormat.of().formatHex(PreviewKeys.digest().digest(Files.readAllBytes(input)))).isEqualTo(media.sha256());
    }
    void await(PreviewQueue queue,String state) throws Exception {
        long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<end){if(queue.status(media,actor,1).state().equals(state))return;Thread.sleep(10);}
        throw new AssertionError("Missing state "+state);
    }
}
