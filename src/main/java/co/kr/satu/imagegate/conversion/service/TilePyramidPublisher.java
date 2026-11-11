package co.kr.satu.imagegate.conversion.service;

import co.kr.satu.imagegate.conversion.domain.*;
import co.kr.satu.imagegate.conversion.repository.TileCache;
import co.kr.satu.imagegate.conversion.service.img2tile.Img2TilePyramidValidator;
import co.kr.satu.imagegate.conversion.util.*;
import java.io.*;
import java.nio.file.*;

/** Offline pipeline primitive; caller must retain exclusive source ownership and access/version policy. */
public final class TilePyramidPublisher {
    private final Img2TilePyramidValidator validator;
    private final TileCache cache;
    private final TileTiming timing;
    @FunctionalInterface interface Normalizer {byte[] normalize(byte[] bytes,int width,int height,TileTiming timing)throws IOException;}
    private final Normalizer normalizer;
    public TilePyramidPublisher(Img2TilePyramidValidator validator,TileCache cache){this(validator,cache,null);}
    public TilePyramidPublisher(Img2TilePyramidValidator validator,TileCache cache,TileTiming timing){this(validator,cache,timing,TilePngSanitizer::normalize);}
    /** Package-local trusted experiment seam. Production constructors always use the standard sanitizer. */
    TilePyramidPublisher(Img2TilePyramidValidator validator,TileCache cache,TileTiming timing,Normalizer normalizer){this.validator=validator;this.cache=cache;this.timing=timing;this.normalizer=java.util.Objects.requireNonNull(normalizer);}
    public TileCacheManifest publish(String key,Path output,Img2TilePyramidValidator.Source source) throws IOException {
        return publish(key,output,source,()->{},action->action.run());
    }
    @FunctionalInterface public interface Verification {void check() throws IOException;}
    @FunctionalInterface public interface CommitGate {void commit(TileCache.CommitAction action) throws IOException;}
    public TileCacheManifest publish(String key,Path output,Img2TilePyramidValidator.Source source,Verification verification,CommitGate gate) throws IOException {
        Img2TilePyramidValidator.PublicationProof proof;
        try(var span=TileTiming.start(timing,TileTiming.Stage.SOURCE_VALIDATE)){proof=validator.validateForPublication(output,source);}
        var manifest=proof.manifest();Path root=output.toAbsolutePath().normalize();
        return cache.publish(key,manifest,new TileCache.SourceTiles(){
            @Override public byte[] normalize(TilePyramidManifest.Tile tile) throws IOException {
                try(var span=TileTiming.start(timing,TileTiming.Stage.NORMALIZE_TILE)){
                PreviewCancellation.check();
                Path file=root.resolve("mipmap_files/"+tile.level()+"/"+tile.column()+"_"+tile.row()+".png");
                try(var boundary=TileTiming.start(timing,TileTiming.Stage.NORMALIZE_SOURCE_BOUNDARY)){
                    if(!file.toRealPath().equals(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)!=tile.bytes())throw new IOException("Tile source changed");
                }
                byte[] bytes;try(var read=TileTiming.start(timing,TileTiming.Stage.NORMALIZE_SOURCE_READ);var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){bytes=input.readNBytes(2_097_153);}
                if(bytes.length!=tile.bytes())throw new IOException("Tile source changed");
                try(var hash=TileTiming.start(timing,TileTiming.Stage.NORMALIZE_SOURCE_HASH)){if(!PreviewKeys.sha256(bytes).equals(tile.sha256()))throw new IOException("Tile source changed");}
                try(var boundary=TileTiming.start(timing,TileTiming.Stage.NORMALIZE_SOURCE_BOUNDARY)){if(!file.toRealPath().equals(file))throw new IOException("Tile source changed");}
                try(var png=TileTiming.start(timing,TileTiming.Stage.NORMALIZE_PNG)){return normalizer.normalize(bytes,tile.width(),tile.height(),timing);}
                }
            }
            @Override public void verifyBeforeCommit() throws IOException {try(var span=TileTiming.start(timing,TileTiming.Stage.SOURCE_RECHECK)){validator.verifyForPublication(root,source,proof);}}
            @Override public void commit(TileCache.CommitAction action) throws IOException {verification.check();gate.commit(action);}
        });
    }
}
