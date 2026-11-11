package co.kr.satu.imagegate.conversion.util;

/** Explicit trusted in-process diagnostics; no paths, identities, logging, API or installation switch. */
@FunctionalInterface
public interface TileTiming {
    enum Stage { ACCESS_BEFORE, ORIGINAL_HASH_BEFORE, SOURCE_INSPECT, MODULE_BEFORE, WORK_CREATE,
        NATIVE_CONVERT, SOURCE_VALIDATE, NORMALIZE_TILE, PUBLIC_TILE_CHECK, TILE_WRITE,
        SOURCE_RECHECK, PUBLIC_GENERATION_CHECK, COMMIT, ORIGINAL_HASH_AFTER, MODULE_AFTER, ACCESS_AFTER, WORK_CLEANUP,
        NATIVE_INVENTORY, NATIVE_ATTRIBUTES, NATIVE_CANONICAL, NATIVE_READ_BYTES, NATIVE_CONTENT_HASH, NATIVE_PNG_CHECK,
        NORMALIZE_SOURCE_BOUNDARY, NORMALIZE_SOURCE_READ, NORMALIZE_SOURCE_HASH, NORMALIZE_PNG,
        CACHE_DIRECTORY_BOUNDARY, CACHE_REGULAR_BOUNDARY, CACHE_CANONICAL, CACHE_READ_OPEN, CACHE_READ_BYTES, CACHE_READ_CLOSE,
        CACHE_CREATE_NEW, CACHE_WRITE_BYTES, CACHE_FORCE, CACHE_WRITE_CLOSE, CACHE_CONTENT_HASH,
        PNG_DECODE, PNG_COPY, PNG_ENCODE, PNG_OUTPUT_CHECK }
    void completed(Stage stage,long elapsedNanos);
    static Span start(TileTiming observer,Stage stage){return observer==null?Span.DISABLED:new Span(observer,stage);}
    /** Retains normal try-with-resources close/suppression semantics; no wrapper when diagnostics are disabled. */
    static java.io.Closeable withTimedClose(java.io.Closeable source,TileTiming observer,Stage stage){
        if(observer==null)return source;
        return ()->{try(var span=start(observer,stage)){source.close();}};
    }
    final class Span implements AutoCloseable {
        private static final Span DISABLED=new Span();
        private final TileTiming observer;private final Stage stage;private final long started;private boolean closed;
        private Span(){observer=null;stage=null;started=0;}
        private Span(TileTiming observer,Stage stage){this.observer=observer;this.stage=java.util.Objects.requireNonNull(stage);started=System.nanoTime();}
        public void close(){if(observer==null||closed)return;closed=true;long elapsed=System.nanoTime()-started;boolean interrupted=Thread.currentThread().isInterrupted();
            try{observer.completed(stage,elapsed);}catch(RuntimeException ignored){/* Diagnostics cannot replace a conversion result or failure. */}
            finally{if(interrupted)Thread.currentThread().interrupt();}}
    }
}
