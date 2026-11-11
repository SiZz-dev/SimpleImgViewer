package co.kr.satu.imagegate.conversion.util;

/** Trusted in-process diagnostics only: fixed stages and elapsed time, no paths or identities. */
@FunctionalInterface
public interface PreviewTiming {
    enum Stage {
        ACCESS_BEFORE, ORIGINAL_RESOLVE, ORIGINAL_HASH, WORK_CREATE, CONVERTER_SELECT,
        NATIVE_CONVERT, DECODER_OPEN, DECODER_SELECT, DECODER_METADATA, ORIENTATION,
        PIXEL_DECODE, RESIZE, PREVIEW_PNG, THUMBNAIL, OUTPUT_CHECK, ACCESS_AFTER,
        HASH_PREPARE, HASH_OPEN, HASH_READ, HASH_UPDATE, HASH_CLOSE, HASH_FINAL;

        public boolean hashDetail(){return switch(this){
            case HASH_PREPARE,HASH_OPEN,HASH_READ,HASH_UPDATE,HASH_CLOSE,HASH_FINAL -> true;
            default -> false;
        };}
    }
    void completed(Stage stage,long elapsedNanos);

    static Span start(PreviewTiming observer,Stage stage){
        return observer==null?Span.DISABLED:new Span(observer,stage);
    }
    /** Preserve try-with-resources close/suppression semantics; disabled diagnostics return the original stream. */
    static java.io.InputStream withTimedClose(java.io.InputStream source,PreviewTiming observer){
        if(observer==null)return source;
        return new java.io.FilterInputStream(source){
            @Override public int read() throws java.io.IOException{return in.read();}
            @Override public int read(byte[] bytes) throws java.io.IOException{return in.read(bytes);}
            @Override public int read(byte[] bytes,int offset,int length) throws java.io.IOException{return in.read(bytes,offset,length);}
            @Override public void close() throws java.io.IOException{
                try(var span=start(observer,Stage.HASH_CLOSE)){in.close();}
            }
        };
    }
    final class Span implements AutoCloseable {
        private static final Span DISABLED=new Span();
        private final PreviewTiming observer;
        private final Stage stage;
        private final long started;
        private boolean closed;
        private Span(){observer=null;stage=null;started=0;}
        private Span(PreviewTiming observer,Stage stage){
            this.observer=observer;this.stage=java.util.Objects.requireNonNull(stage);started=System.nanoTime();
        }
        @Override public void close(){
            if(observer==null||closed)return;
            closed=true;
            long elapsed=System.nanoTime()-started;
            boolean interrupted=Thread.currentThread().isInterrupted();
            try{observer.completed(stage,elapsed);}
            catch(RuntimeException ignored){/* Diagnostics must not replace a render result/failure. */}
            finally{if(interrupted)Thread.currentThread().interrupt();}
        }
    }
}
