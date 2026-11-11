package co.kr.satu.imagegate.system.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "imagegate", ignoreUnknownFields = false)
public record ImageGateProperties(
        @NotNull @Valid Storage storage,
        @NotNull @Valid Features features,
        @NotNull @Valid Conversion conversion,
        @NotNull @Valid Preview preview,
        @NotNull @Valid Viewer viewer,
        @NotNull @Valid Editing editing,
        @NotNull @Valid Crop crop,
        @NotNull @Valid PlaybackProcessing playback,
        @NotNull @Valid Audit audit,
        @NotNull @Valid Upload upload,
        @NotNull @Valid Tiles tiles) {
    @org.springframework.boot.context.properties.bind.ConstructorBinding public ImageGateProperties {if(tiles==null)tiles=Tiles.defaults();}
    public ImageGateProperties(Storage storage,Features features,Conversion conversion,Preview preview,Viewer viewer,Editing editing,Crop crop,PlaybackProcessing playback,Audit audit,Upload upload){
        this(storage,features,conversion,preview,viewer,editing,crop,playback,audit,upload,Tiles.defaults());
    }
    public ImageGateProperties(Storage storage,Features features,Conversion conversion,Preview preview,Viewer viewer,Editing editing,Crop crop,PlaybackProcessing playback){
        this(storage,features,conversion,preview,viewer,editing,crop,playback,new Audit(true),Upload.defaults());
    }
    public ImageGateProperties(Storage storage,Features features,Conversion conversion,Preview preview,Viewer viewer,Editing editing,Crop crop,PlaybackProcessing playback,Audit audit){
        this(storage,features,conversion,preview,viewer,editing,crop,playback,audit,Upload.defaults());
    }
    public record Tiles(boolean enabled,@Min(1) @Max(2) int workers,@Min(1) @Max(32) int queueCapacity,
            @Min(1) @Max(1024) int maxTrackedJobs,@NotNull Duration timeout,
            @Min(1) @Max(40000000) long maxSourcePixels,@Min(1) @Max(4096) int maxTiles,
            @Min(64) @Max(2097152) int maxTileBytes,@Min(64) @Max(268435456) long maxPageBytes,
            @Min(0) @Max(1099511627776L) long minFreeBytes,
            @NotBlank @jakarta.validation.constraints.Pattern(regexp="[A-Za-z0-9_-]{1,64}") String cacheVersion,
            @NotNull @Valid TileMaintenance maintenance) {
        @org.springframework.boot.context.properties.bind.ConstructorBinding public Tiles {if(maintenance==null)maintenance=TileMaintenance.defaults();}
        public Tiles(boolean enabled,int workers,int queueCapacity,int maxTrackedJobs,Duration timeout,long maxSourcePixels,int maxTiles,int maxTileBytes,long maxPageBytes,long minFreeBytes,String cacheVersion){
            this(enabled,workers,queueCapacity,maxTrackedJobs,timeout,maxSourcePixels,maxTiles,maxTileBytes,maxPageBytes,minFreeBytes,cacheVersion,TileMaintenance.defaults());
        }
        public static Tiles defaults(){return new Tiles(false,1,8,256,Duration.ofMinutes(2),40000000,4096,2097152,268435456,1073741824,"tiles-v1");}
        @AssertTrue(message="Tile timeout must be whole milliseconds between one second and five minutes")
        public boolean isTimeoutBounded(){return timeout==null||(timeout.compareTo(Duration.ofSeconds(1))>=0&&timeout.compareTo(Duration.ofMinutes(5))<=0&&timeout.getNano()%1000000==0);}
        @AssertTrue(message="Tracked tile job capacity must cover workers and queue")
        public boolean isCapacityBounded(){return maxTrackedJobs>=workers+queueCapacity;}
    }
    public record TileMaintenance(boolean enabled,@NotNull Duration retention,@NotNull Duration interval,
            @Min(1048576) @Max(1099511627776L) long maxBytes,@NotNull Duration historyRetention,
            @NotNull Duration workRetention,@Min(16) @Max(4096) int maxScanEntries) {
        public static TileMaintenance defaults(){return new TileMaintenance(true,Duration.ofDays(7),Duration.ofMinutes(5),8589934592L,Duration.ofDays(1),Duration.ofHours(1),2048);}
        @AssertTrue(message="Tile maintenance durations must be whole milliseconds within supported bounds")
        public boolean isTimeBounded(){return bounded(retention,Duration.ofHours(1),Duration.ofDays(365))&&bounded(historyRetention,Duration.ofHours(1),Duration.ofDays(365))
                &&bounded(workRetention,Duration.ofHours(1),Duration.ofDays(30))&&bounded(interval,Duration.ofMinutes(1),Duration.ofDays(1));}
        private static boolean bounded(Duration value,Duration min,Duration max){return value==null||(value.compareTo(min)>=0&&value.compareTo(max)<=0&&value.getNano()%1000000==0);}
    }
    @AssertTrue(message="Tiles require enabled img2tile conversion")
    public boolean isTileModuleAvailable(){return tiles==null||!tiles.enabled()||(conversion!=null&&conversion.enabled()&&conversion.module()==ImageModule.IMG2TILE);}
    public record Audit(boolean enabled) {}
    public record Upload(@Min(1) @Max(104857600) long maxFileBytes,
            @Min(1) @Max(16) int maxConcurrent,@Min(1) @Max(8) int maxConcurrentPerUser,
            @Min(0) @Max(1099511627776L) long minFreeBytes,
            @NotNull Path multipartDirectory,@Min(1024) @Max(1048576) long multipartOverheadBytes,
            @Min(1) @Max(16) int multipartMaxConcurrent,@NotNull Duration bodyReadIdleTimeout,
            @Min(0) @Max(1125899906842624L) long tenantOriginalMaxBytes) {
        @org.springframework.boot.context.properties.bind.ConstructorBinding public Upload {}
        public Upload(long maxFileBytes,int maxConcurrent,int maxConcurrentPerUser,long minFreeBytes){
            this(maxFileBytes,maxConcurrent,maxConcurrentPerUser,minFreeBytes,Path.of("./.runtime/multipart"),65536,4,Duration.ofSeconds(30),0);
        }
        public Upload(long maxFileBytes,int maxConcurrent,int maxConcurrentPerUser,long minFreeBytes,
                Path multipartDirectory,long multipartOverheadBytes,int multipartMaxConcurrent,Duration bodyReadIdleTimeout){
            this(maxFileBytes,maxConcurrent,maxConcurrentPerUser,minFreeBytes,multipartDirectory,multipartOverheadBytes,multipartMaxConcurrent,bodyReadIdleTimeout,0);
        }
        public static Upload defaults(){return new Upload(104857600,4,2,536870912);}
        @AssertTrue(message="Body read idle timeout must be whole milliseconds between one second and five minutes")
        public boolean isBodyReadTimeoutBounded(){return bodyReadIdleTimeout==null||(bodyReadIdleTimeout.compareTo(Duration.ofSeconds(1))>=0
                &&bodyReadIdleTimeout.compareTo(Duration.ofMinutes(5))<=0&&bodyReadIdleTimeout.getNano()%1000000==0); }
        @AssertTrue(message="Per-user upload limit must not exceed total upload limit")
        public boolean isCapacityConsistent(){return maxConcurrentPerUser<=maxConcurrent;}
    }

    public record Storage(
            @NotNull Path root,
            @NotNull Path originalsDirectory,
            @NotNull Path convertedDirectory,
            @NotNull Path annotationsDirectory,
            @NotNull Path stampsDirectory) {

        public Path originalsPath() { return resolve(originalsDirectory); }
        public Path convertedPath() { return resolve(convertedDirectory); }
        public Path annotationsPath() { return resolve(annotationsDirectory); }
        public Path stampsPath() { return resolve(stampsDirectory); }

        private Path resolve(Path directory) {
            return (directory.isAbsolute() ? directory : root.resolve(directory))
                    .toAbsolutePath().normalize();
        }

        @AssertTrue(message = "Original and converted directories must not overlap")
        public boolean isDirectoryLayoutValid() {
            if (root==null||originalsDirectory==null||convertedDirectory==null||annotationsDirectory==null||stampsDirectory==null) return true;
            var paths=java.util.List.of(originalsPath(),convertedPath(),annotationsPath(),stampsPath());
            for(int i=0;i<paths.size();i++)for(int j=i+1;j<paths.size();j++)
                if(paths.get(i).startsWith(paths.get(j))||paths.get(j).startsWith(paths.get(i)))return false;
            return true;
        }
    }

    public record Features(
            @NotNull @Valid Image image,
            @NotNull @Valid Playback video,
            @NotNull @Valid Playback audio) {}

    public record Image(boolean enabled, boolean rotation, boolean zoom,
            boolean cropAndSave, boolean brightness, boolean annotations,
            boolean magnifier, boolean stamps, boolean stampUpload, boolean exportEdited) {}

    public record Playback(boolean enabled, boolean seek, boolean playbackSpeed) {}

    public enum ImageModule { NONE, IMG2TILE, LIBVIPS, IMAGEMAGICK }

    public record Conversion(
            boolean enabled,
            @NotNull ImageModule module,
            @NotNull Duration timeout,
            @Min(1) @Max(8) int maxConcurrentJobs,
            @NotNull @Valid Modules modules) {
        @AssertTrue(message = "Timeout must be positive and no greater than five minutes")
        public boolean isTimeoutBounded() {
            return timeout == null || (!timeout.isZero() && !timeout.isNegative()
                    && timeout.compareTo(Duration.ofMinutes(5)) <= 0);
        }

        @AssertTrue(message = "Only img2tile is implemented; disable conversion for other module selections")
        public boolean isSelectedModuleAvailable() {
            return !enabled || module == ImageModule.IMG2TILE;
        }
    }

    public record Modules(
            @NotNull @Valid Tool img2tile,
            @NotNull @Valid Tool libvips,
            @NotNull @Valid Tool imagemagick) {}

    public record Preview(boolean enabled, @Min(1) @Max(4) int workers,
            @Min(1) @Max(256) int queueCapacity,
            @Min(1) @Max(4096) int maxWidth, @Min(1) @Max(4096) int maxHeight,
            @Min(1) @Max(40000000) long maxSourcePixels,
            @NotBlank @jakarta.validation.constraints.Pattern(regexp="[A-Za-z0-9_-]{1,64}") String cacheVersion,@NotNull @Valid PreviewCachePolicy cache,
            boolean thumbnailEnabled,@Min(64) @Max(512) int thumbnailMaxEdge,
            @NotNull @Valid OriginalOpenPolicy originalOpen,boolean metricsEnabled) {
        @org.springframework.boot.context.properties.bind.ConstructorBinding
        public Preview {if(originalOpen==null)originalOpen=OriginalOpenPolicy.defaults();}
        public Preview(boolean enabled,int workers,int queueCapacity,int maxWidth,int maxHeight,long maxSourcePixels,String cacheVersion,PreviewCachePolicy cache,boolean thumbnailEnabled,int thumbnailMaxEdge,OriginalOpenPolicy originalOpen){
            this(enabled,workers,queueCapacity,maxWidth,maxHeight,maxSourcePixels,cacheVersion,cache,thumbnailEnabled,thumbnailMaxEdge,originalOpen,false);
        }
        public Preview(boolean enabled,int workers,int queueCapacity,int maxWidth,int maxHeight,long maxSourcePixels,String cacheVersion,PreviewCachePolicy cache,boolean thumbnailEnabled,int thumbnailMaxEdge){
            this(enabled,workers,queueCapacity,maxWidth,maxHeight,maxSourcePixels,cacheVersion,cache,thumbnailEnabled,thumbnailMaxEdge,OriginalOpenPolicy.defaults());
        }
        public Preview(boolean enabled,int workers,int queueCapacity,int maxWidth,int maxHeight,long maxSourcePixels,String cacheVersion,PreviewCachePolicy cache){
            this(enabled,workers,queueCapacity,maxWidth,maxHeight,maxSourcePixels,cacheVersion,cache,true,256);
        }
        public Preview(boolean enabled,int workers,int queueCapacity,int maxWidth,int maxHeight,long maxSourcePixels,String cacheVersion){
            this(enabled,workers,queueCapacity,maxWidth,maxHeight,maxSourcePixels,cacheVersion,PreviewCachePolicy.defaults(),true,256);
        }
    }

    public record OriginalOpenPolicy(boolean enabled,@Min(1) @Max(4) int maxConcurrent,@NotNull Duration waitTimeout) {
        public static OriginalOpenPolicy defaults(){return new OriginalOpenPolicy(false,1,Duration.ofSeconds(10));}
        @AssertTrue(message="Original open wait timeout must be whole milliseconds between one millisecond and one minute")
        public boolean isWaitBounded(){return waitTimeout==null||(waitTimeout.compareTo(Duration.ofMillis(1))>=0
                &&waitTimeout.compareTo(Duration.ofMinutes(1))<=0&&waitTimeout.getNano()%1000000==0);}
    }

    public record PreviewCachePolicy(boolean enabled,@NotNull Duration retention,@NotNull Duration cleanupInterval,
            @Min(1048576) @Max(1099511627776L) long maxBytes) {
        public static PreviewCachePolicy defaults(){return new PreviewCachePolicy(true,Duration.ofDays(7),Duration.ofMinutes(5),5368709120L);}
        @AssertTrue(message="Preview cache retention must be between one hour and 365 days")
        public boolean isRetentionBounded(){return retention==null||(retention.compareTo(Duration.ofHours(1))>=0&&retention.compareTo(Duration.ofDays(365))<=0);}
        @AssertTrue(message="Preview cleanup interval must be between one minute and one day")
        public boolean isIntervalBounded(){return cleanupInterval==null||(cleanupInterval.compareTo(Duration.ofMinutes(1))>=0&&cleanupInterval.compareTo(Duration.ofDays(1))<=0);}
    }

    public record Viewer(@NotNull Duration sessionTtl, @Min(1) @Max(10000) int maxSessions,
            @Min(1) @Max(100) int maxSessionsPerUser) {
        @AssertTrue(message="Viewer session TTL must be between one minute and two hours")
        public boolean isTtlBounded() {
            return sessionTtl==null || (sessionTtl.compareTo(Duration.ofMinutes(1))>=0
                    && sessionTtl.compareTo(Duration.ofHours(2))<=0);
        }
        @AssertTrue(message="Per-user session limit must not exceed total session limit")
        public boolean isCapacityConsistent() { return maxSessionsPerUser<=maxSessions; }
    }

    public record Editing(@Min(1) @Max(100) int maxAnnotationsPerPage,
            @Min(1) @Max(2000) int maxNoteLength,@Min(1) @Max(100) int maxStampsPerUser,
            @Min(1024) @Max(5242880) long maxStampUploadBytes,@Min(1) @Max(4194304) long maxStampPixels,
            @NotNull @Valid EditHistory history) {
        @org.springframework.boot.context.properties.bind.ConstructorBinding public Editing {}
        public Editing(int maxAnnotationsPerPage,int maxNoteLength,int maxStampsPerUser,long maxStampUploadBytes,long maxStampPixels){
            this(maxAnnotationsPerPage,maxNoteLength,maxStampsPerUser,maxStampUploadBytes,maxStampPixels,EditHistory.defaults());
        }
    }
    public record EditHistory(boolean enabled,@Min(1) @Max(50) int maxSteps,@Min(1) @Max(256) int maxContexts,
            @Min(1) @Max(32) int maxContextsPerUser,@NotNull Duration ttl) {
        public static EditHistory defaults(){return new EditHistory(true,20,128,8,Duration.ofMinutes(30));}
        @AssertTrue(message="History TTL must be between one minute and two hours")
        public boolean isTtlBounded(){return ttl==null||(ttl.compareTo(Duration.ofMinutes(1))>=0&&ttl.compareTo(Duration.ofHours(2))<=0);}
        @AssertTrue(message="Per-user history contexts must not exceed total contexts")
        public boolean isCapacityConsistent(){return maxContextsPerUser<=maxContexts;}
    }

    public record Crop(@Min(1) @Max(40000000) long maxSourcePixels,
            @Min(1) @Max(8000000) long maxOutputPixels,@Min(1024) @Max(16777216) long maxOutputBytes,
            @Min(1) @Max(2) int maxConcurrentJobs) {}

    public record PlaybackProcessing(boolean enabled,@NotBlank String ffmpeg,@NotBlank String ffprobe,
            @NotNull @jakarta.validation.constraints.Pattern(regexp="(?:[a-f0-9]{64})?") String ffmpegSha256,
            @NotNull @jakarta.validation.constraints.Pattern(regexp="(?:[a-f0-9]{64})?") String ffprobeSha256,
            @NotNull Duration timeout,@Min(1) @Max(2) int workers,@Min(1) @Max(64) int queueCapacity,
            @Min(1) @Max(7200) int maxDurationSeconds,@Min(1) @Max(8294400) long maxSourcePixels,
            @Min(1024) @Max(536870912) long maxOutputBytes,@Min(1) @Max(256) int maxStreams,
            @NotBlank @jakarta.validation.constraints.Pattern(regexp="[A-Za-z0-9_-]{1,64}") String cacheVersion,
            @NotNull @Valid PlaybackCachePolicy cache) {
        @org.springframework.boot.context.properties.bind.ConstructorBinding
        public PlaybackProcessing {}
        public PlaybackProcessing(boolean enabled,String ffmpeg,String ffprobe,String ffmpegSha256,String ffprobeSha256,
                Duration timeout,int workers,int queueCapacity,int maxDurationSeconds,long maxSourcePixels,
                long maxOutputBytes,int maxStreams,String cacheVersion) {
            this(enabled,ffmpeg,ffprobe,ffmpegSha256,ffprobeSha256,timeout,workers,queueCapacity,maxDurationSeconds,
                    maxSourcePixels,maxOutputBytes,maxStreams,cacheVersion,PlaybackCachePolicy.defaults());
        }
        @AssertTrue(message="Playback timeout must be positive and at most five minutes")
        public boolean isTimeoutBounded(){return timeout==null||(!timeout.isNegative()&&!timeout.isZero()&&timeout.compareTo(Duration.ofMinutes(5))<=0);}
        @AssertTrue(message="Enabled playback requires pinned FFmpeg and FFprobe SHA-256 values")
        public boolean isPinned(){return !enabled||(ffmpegSha256!=null&&ffprobeSha256!=null&&ffmpegSha256.length()==64&&ffprobeSha256.length()==64);}
    }

    public record PlaybackCachePolicy(boolean enabled,@NotNull Duration retention,@NotNull Duration cleanupInterval,
            @Min(1048576) @Max(1099511627776L) long maxBytes) {
        public static PlaybackCachePolicy defaults(){return new PlaybackCachePolicy(true,Duration.ofDays(7),Duration.ofMinutes(5),10737418240L);}
        @AssertTrue(message="Playback cache retention must be between one hour and 365 days")
        public boolean isRetentionBounded(){return retention==null||(retention.compareTo(Duration.ofHours(1))>=0&&retention.compareTo(Duration.ofDays(365))<=0);}
        @AssertTrue(message="Playback cache cleanup interval must be between one minute and one day")
        public boolean isIntervalBounded(){return cleanupInterval==null||(cleanupInterval.compareTo(Duration.ofMinutes(1))>=0&&cleanupInterval.compareTo(Duration.ofDays(1))<=0);}
    }

    public record Tool(@NotBlank String executable) {}
}
