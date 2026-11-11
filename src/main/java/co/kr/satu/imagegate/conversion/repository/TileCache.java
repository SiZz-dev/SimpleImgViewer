package co.kr.satu.imagegate.conversion.repository;

import co.kr.satu.imagegate.conversion.domain.*;
import co.kr.satu.imagegate.conversion.util.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Separate private cache. Atomic directory rename is required; never falls back to partial copying.
 * HTTP callers still own authorization; the preparation queue scopes keys and reservations.
 */
public final class TileCache implements AutoCloseable {
    private static final String POLICY="png-rgba8-o1-v1";
    private static final int TILE_LIMIT=2_097_152,MANIFEST_LIMIT=2_097_152;
    private static final long PAGE_LIMIT=268_435_456;
    private final Path root;
    private final JsonMapper json=JsonMapper.builder(tools.jackson.core.json.JsonFactory.builder()
            .streamReadConstraints(tools.jackson.core.StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(256).maxNumberLength(20).build())
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    tools.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                    tools.jackson.databind.DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                    tools.jackson.databind.DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES).build();
    private final Object[] locks=java.util.stream.IntStream.range(0,64).mapToObj(i->new Object()).toArray();
    private final Map<String,Integer> readers=new HashMap<>();
    // Bound parsed metadata retention; every read still checks canonical files and hashes the bounded JSON.
    private final Map<String,TileCacheManifest> parsed=new LinkedHashMap<>(16,.75f,true);
    private final Set<Path> stages=Collections.synchronizedSet(new HashSet<>());
    private final Map<String,Long> lastUsed=Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String,Long> persistedUse=Collections.synchronizedMap(new LinkedHashMap<>());
    private final java.util.concurrent.atomic.AtomicBoolean touchWarning=new java.util.concurrent.atomic.AtomicBoolean();
    private void touch(String key){long now=System.currentTimeMillis();Long previous;
        synchronized(lastUsed){lastUsed.remove(key);lastUsed.put(key,now);if(lastUsed.size()>4096)lastUsed.remove(lastUsed.keySet().iterator().next());}
        synchronized(persistedUse){previous=persistedUse.get(key);if(previous==null||now-previous>=60000){persistedUse.put(key,now);if(persistedUse.size()>4096)persistedUse.remove(persistedUse.keySet().iterator().next());}}
        if(previous==null||now-previous>=60000)try{Path entry=root.resolve(key);directory(entry);Files.setLastModifiedTime(entry,java.nio.file.attribute.FileTime.fromMillis(now));}
        catch(IOException failure){if(touchWarning.compareAndSet(false,true))org.slf4j.LoggerFactory.getLogger(TileCache.class).warn("Tile access timestamp could not be persisted");}
    }
    private final FileChannel channel;
    private final FileLock writer;
    private int operations;
    private boolean closing,closed;
    private final TileTiming timing;
    public TileCache(Path converted) throws IOException {
        this(converted,null);
    }
    public TileCache(Path converted,TileTiming timing) throws IOException {
        this.timing=timing;
        root=converted.toAbsolutePath().normalize().resolve("tiles-v1");
        createDirectories(root);directory(root);
        Path marker=root.resolve("worker.lock");
        if(Files.exists(marker,LinkOption.NOFOLLOW_LINKS))regular(marker,1024);
        channel=FileChannel.open(marker,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
        try { writer=channel.tryLock();if(writer==null)throw new IOException("Tile cache writer unavailable");directory(root);regular(marker,1024); }
        catch(IOException|RuntimeException e) { channel.close();throw new IOException("Tile cache writer unavailable",e); }
    }
    public interface SourceTiles {
        byte[] normalize(TilePyramidManifest.Tile tile) throws IOException;
        void verifyBeforeCommit() throws IOException;
        default void commit(CommitAction action) throws IOException {action.run();}
    }
    @FunctionalInterface public interface CommitAction {void run() throws IOException;}
    public interface Lease extends AutoCloseable {@Override void close() throws IOException;}
    public Lease lease() throws IOException {var operation=enter();return operation::close;}
    /** Pins queued/running work as well as transfers. Idempotent release. */
    public Lease lease(String key)throws IOException{
        var operation=enter();boolean accepted=false;
        try{synchronized(stripe(key)){synchronized(readers){readers.merge(key,1,Integer::sum);}}accepted=true;}
        finally{if(!accepted)operation.close();}
        var released=new java.util.concurrent.atomic.AtomicBoolean();
        return ()->{if(released.compareAndSet(false,true)){synchronized(readers){readers.compute(key,(k,n)->n==1?null:n-1);}operation.close();}};
    }
    @FunctionalInterface public interface TileConsumer { void accept(InputStream input,TilePyramidManifest.Tile tile) throws IOException; }
    private Object stripe(String key) throws IOException { key(key);return locks[key.hashCode()&63]; }
    private synchronized Operation enter() throws IOException {
        if(closing)throw new IOException("Tile cache closed");operations++;return new Operation();
    }
    private final class Operation implements AutoCloseable {
        @Override public void close() throws IOException { synchronized(TileCache.this){operations--;releaseIfIdle();} }
    }
    @Override public synchronized void close() throws IOException { closing=true;releaseIfIdle(); }
    private void releaseIfIdle() throws IOException {
        if(closing&&!closed&&operations==0){closed=true;try{writer.release();}finally{channel.close();}}
    }
    public TileCacheManifest publish(String key,TilePyramidManifest source,SourceTiles input) throws IOException {
        try(var operation=enter()) { synchronized(stripe(key)) {
            directory(root);validateSource(source);
            String sourceHash=contentHash(json.writeValueAsBytes(source));
            if(Files.exists(root.resolve(key),LinkOption.NOFOLLOW_LINKS)) {
                var existing=load(key);
                if(!existing.sourceManifestSha256().equals(sourceHash))throw new IOException("Tile source conflict");
                verifyGeneration(root.resolve(key),existing);
                input.verifyBeforeCommit();input.commit(()->{});
                return existing;
            }
            Path stage=root.resolve(".work-"+UUID.randomUUID());stages.add(stage);
            var owned=new ArrayList<Path>();boolean published=false;
            try {
                Files.createDirectory(stage);
                var tiles=new ArrayList<TilePyramidManifest.Tile>();long total=0;
                for(var tile:source.tiles()) {
                    PreviewCancellation.check();byte[] bytes=input.normalize(tile);
                    if(bytes==null||bytes.length>TILE_LIMIT||bytes.length>PAGE_LIMIT-total)throw new IOException("Tile output limit");
                    try(var span=TileTiming.start(timing,TileTiming.Stage.PUBLIC_TILE_CHECK)){TilePngVerifier.verify(bytes,tile.width(),tile.height());TilePngSanitizer.verifyClean(bytes);}
                    Path file=stage.resolve(name(tile));
                    try(var span=TileTiming.start(timing,TileTiming.Stage.TILE_WRITE)){write(file,bytes,owned);}total+=bytes.length;
                    tiles.add(new TilePyramidManifest.Tile(tile.level(),tile.column(),tile.row(),tile.width(),tile.height(),bytes.length,contentHash(bytes)));
                }
                var manifest=new TileCacheManifest(1,POLICY,key,UUID.randomUUID().toString(),sourceHash,source.page(),source.layout(),total,tiles);
                validate(manifest,key);byte[] metadata=json.writeValueAsBytes(manifest);
                if(metadata.length>MANIFEST_LIMIT)throw new IOException("Tile manifest limit");
                Path descriptor=stage.resolve("manifest.json");write(descriptor,metadata,owned);
                input.verifyBeforeCommit();PreviewCancellation.check();
                try(var span=TileTiming.start(timing,TileTiming.Stage.PUBLIC_GENERATION_CHECK)){verifyGeneration(stage,manifest);}directory(root);directory(stage);
                if(Files.exists(root.resolve(key),LinkOption.NOFOLLOW_LINKS))throw new IOException("Tile generation conflict");
                try(var span=TileTiming.start(timing,TileTiming.Stage.COMMIT)){input.commit(()->Files.move(stage,root.resolve(key),StandardCopyOption.ATOMIC_MOVE));}published=true;
                touch(key);return manifest;
            } finally {
                try{if(!published&&Files.exists(stage,LinkOption.NOFOLLOW_LINKS))cleanup(stage,owned);}finally{stages.remove(stage);}
            }
        } }
    }
    public TileCacheManifest manifest(String key) throws IOException {
        try(var operation=enter()){synchronized(stripe(key)){return load(key);}}
    }
    public Optional<TileCacheManifest> find(String key)throws IOException{
        try(var operation=enter()){synchronized(stripe(key)){directory(root);if(!Files.exists(root.resolve(key),LinkOption.NOFOLLOW_LINKS))return Optional.empty();return Optional.of(load(key));}}
    }
    public void checkCapacity(int maxEntries,int reserve)throws IOException{
        try(var operation=enter()){directory(root);int count=0;try(var children=Files.newDirectoryStream(root)){for(var ignored:children)if(++count>=maxEntries-reserve)throw new co.kr.satu.imagegate.conversion.exception.TileAdmissionException(co.kr.satu.imagegate.conversion.exception.TileAdmissionException.Code.LIMIT);}}
    }
    /** Delivers verified bytes within a lease. No filesystem paths escape this repository. */
    public void withTile(String key,String generation,int level,int column,int row,TileConsumer consumer) throws IOException {
        withTile(key,generation,0,level,column,row,consumer);
    }
    public void withTile(String key,String generation,int expectedPage,int level,int column,int row,TileConsumer consumer) throws IOException {
        try(var operation=enter()) {
            TileCacheManifest manifest;TilePyramidManifest.Tile tile;
            synchronized(stripe(key)) {
                directory(root);if(!Files.exists(root.resolve(key),LinkOption.NOFOLLOW_LINKS))throw new co.kr.satu.imagegate.conversion.exception.TileUnavailableException();
                manifest=load(key);
                if(!manifest.generation().equals(generation)||(expectedPage>0&&manifest.page()!=expectedPage))throw new co.kr.satu.imagegate.conversion.exception.TileUnavailableException();
                try{manifest.layout().tile(level,column,row);}catch(IllegalArgumentException invalid){throw new co.kr.satu.imagegate.conversion.exception.TileUnavailableException();}
                int index=0;for(int z=0;z<level;z++)index=Math.addExact(index,(int)manifest.layout().level(z).tileCount());
                index+=column*manifest.layout().level(level).rows()+row;tile=manifest.tiles().get(index);
                synchronized(readers){readers.merge(key,1,Integer::sum);}
                touch(key);
            }
            try {
                Path entry=root.resolve(key);directory(root);directory(entry);
                byte[] bytes=read(entry.resolve(name(tile)),TILE_LIMIT);
                if(bytes.length!=tile.bytes()||!contentHash(bytes).equals(tile.sha256()))throw new IOException("Tile content changed");
                try(var stream=new ByteArrayInputStream(bytes)){consumer.accept(stream,tile);}
            } finally { synchronized(readers){readers.compute(key,(k,n)->n==1?null:n-1);} }
        }
    }
    /** Explicit eviction only. Active readers are skipped; unknown contents are preserved. */
    public boolean evict(String key) throws IOException {
        try(var operation=enter()){synchronized(stripe(key)){
            directory(root);
            if(!Files.exists(root.resolve(key),LinkOption.NOFOLLOW_LINKS))return false;
            synchronized(readers){if(readers.containsKey(key))return false;}
            var manifest=load(key);Path entry=root.resolve(key);inventory(entry,manifest);
            Path detached=root.resolve(".delete-"+UUID.randomUUID());
            Files.move(entry,detached,StandardCopyOption.ATOMIC_MOVE);
            var paths=new ArrayList<Path>();for(var tile:manifest.tiles())paths.add(detached.resolve(name(tile)));
            paths.add(detached.resolve("manifest.json"));cleanup(detached,paths);lastUsed.remove(key);persistedUse.remove(key);return true;
        }}
    }
    /** No raster decode. Only expired/pressure-selected generations are hashed before removal. */
    public TileMaintenanceReport maintain(co.kr.satu.imagegate.system.config.ImageGateProperties.TileMaintenance policy,long now,boolean delete)throws IOException{
        try(var operation=enter()){
            directory(root);var paths=new ArrayList<Path>();
            try(var children=Files.newDirectoryStream(root)){for(var path:children){if(paths.size()>=policy.maxScanEntries())throw new IOException("Tile cache scan limit");paths.add(path);}}
            record Entry(String key,String generation,long bytes,long used){}
            var entries=new ArrayList<Entry>();int inspected=0,skipped=0,removed=0;long total=0,removedBytes=0;
            for(Path path:paths){PreviewCancellation.check();String name=path.getFileName().toString();if(name.equals("worker.lock")){regular(path,1024);continue;}
                if(!name.matches("[a-f0-9]{64}")){
                    if(stages.contains(path)){skipped++;continue;}
                    if(name.matches("[.]work-[a-f0-9-]{36}|[.]delete-[a-f0-9-]{36}")){
                        try{
                            directory(path);var m=json.readValue(read(path.resolve("manifest.json"),MANIFEST_LIMIT),TileCacheManifest.class);validate(m,m.key());
                            inventory(path,m);long bytes=generationBytes(path,m);total+=bytes;inspected++;
                            if(delete&&now-Files.getLastModifiedTime(path,LinkOption.NOFOLLOW_LINKS).toMillis()>=policy.workRetention().toMillis()){
                                verifyGeneration(path,m);var owned=new ArrayList<Path>();for(var t:m.tiles())owned.add(path.resolve(name(t)));owned.add(path.resolve("manifest.json"));
                                cleanup(path,owned);total-=bytes;removedBytes+=bytes;removed++;
                            }
                        }catch(IOException|RuntimeException invalid){skipped++;}
                    }else skipped++;
                    continue;
                }
                synchronized(stripe(name)){
                    try{
                        var m=load(name);inventory(path,m);long bytes=generationBytes(path,m);total+=bytes;inspected++;
                        long used=lastUsed.getOrDefault(name,Files.getLastModifiedTime(path,LinkOption.NOFOLLOW_LINKS).toMillis());
                        entries.add(new Entry(name,m.generation(),bytes,used));
                    }catch(IOException|RuntimeException invalid){skipped++;}
                }
            }
            entries.sort(Comparator.comparingLong(Entry::used).thenComparing(Entry::key));
            if(delete)for(var entry:entries){
                if(now-entry.used()<policy.retention().toMillis()&&total<=policy.maxBytes()&&paths.size()-removed<=policy.maxScanEntries()-8)continue;
                synchronized(stripe(entry.key())){
                    synchronized(readers){if(readers.containsKey(entry.key()))continue;}
                    if(lastUsed.getOrDefault(entry.key(),entry.used())>entry.used())continue;
                    try{
                        var current=load(entry.key());if(!current.generation().equals(entry.generation()))continue;
                        verifyGeneration(root.resolve(entry.key()),current);
                        if(evict(entry.key())){removed++;removedBytes+=entry.bytes();total-=entry.bytes();}
                    }catch(IOException|RuntimeException invalid){skipped++;}
                }
            }
            return new TileMaintenanceReport(inspected,removed,removedBytes,total,skipped);
        }
    }
    private long generationBytes(Path entry,TileCacheManifest manifest)throws IOException{
        long total=Files.size(entry.resolve("manifest.json"));
        for(var tile:manifest.tiles()){Path path=entry.resolve(name(tile));regular(path,TILE_LIMIT);long bytes=Files.size(path);
            if(bytes!=tile.bytes())throw new IOException("Tile size changed");total=Math.addExact(total,bytes);}
        return total;
    }
    private TileCacheManifest load(String key) throws IOException {
        key(key);directory(root);Path entry=root.resolve(key);directory(entry);
        try {
            byte[] bytes=read(entry.resolve("manifest.json"),MANIFEST_LIMIT);String hash=contentHash(bytes);
            synchronized(parsed){var hit=parsed.get(hash);if(hit!=null){if(!key.equals(hit.key()))throw new IOException("Tile key mismatch");return hit;}}
            var manifest=json.readValue(bytes,TileCacheManifest.class);validate(manifest,key);
            synchronized(parsed){parsed.put(hash,manifest);if(parsed.size()>16)parsed.remove(parsed.keySet().iterator().next());}
            return manifest;
        }catch(RuntimeException e){throw new IOException("Invalid tile cache manifest",e);}
    }
    private static void validateSource(TilePyramidManifest source) throws IOException {
        if(source==null||source.schemaVersion()!=1||!"img2tile-tiff-rgb8-o1-v1".equals(source.profile())
                ||!"original-upright-pixel-edges".equals(source.coordinateSpace())
                ||source.moduleIdentity()==null||!source.moduleIdentity().matches("native-module-v1/[0-9a-f]{64}"))
            throw new IOException("Unsupported tile source");
        validate(new TileCacheManifest(1,POLICY,"0".repeat(64),UUID.randomUUID().toString(),source.descriptorSha256(),source.page(),source.layout(),
                source.tiles().stream().mapToLong(TilePyramidManifest.Tile::bytes).sum(),source.tiles()),"0".repeat(64));
    }
    private static void validate(TileCacheManifest m,String key) throws IOException {
        try {
            if(m==null||m.schemaVersion()!=1||!POLICY.equals(m.policy())||!key.equals(m.key())
                    ||!UUID.fromString(m.generation()).toString().equals(m.generation())||!sha(m.sourceManifestSha256())||m.page()<1)throw new IllegalArgumentException();
            var layout=m.layout();
            if(layout.tileSize()!=256||layout.overlap()!=0||(long)layout.width()*layout.height()>40_000_000
                    ||layout.totalTiles()>4096||m.tiles().size()!=layout.totalTiles())throw new IllegalArgumentException();
            int i=0;long total=0;
            for(int z=0;z<=layout.maxLevel();z++){var grid=layout.level(z);
                for(int col=0;col<grid.columns();col++)for(int row=0;row<grid.rows();row++){
                    var tile=m.tiles().get(i++);var bounds=layout.tile(z,col,row).stored();
                    if(tile.level()!=z||tile.column()!=col||tile.row()!=row||tile.width()!=bounds.width()||tile.height()!=bounds.height()
                            ||tile.bytes()<1||tile.bytes()>TILE_LIMIT||!sha(tile.sha256())||tile.bytes()>PAGE_LIMIT-total)throw new IllegalArgumentException();
                    total+=tile.bytes();
                }
            }
            if(total!=m.totalBytes())throw new IllegalArgumentException();
        }catch(RuntimeException e){throw new IOException("Invalid tile cache manifest",e);}
    }
    private static boolean sha(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
    private static void key(String key) throws IOException {if(!sha(key))throw new IOException("Invalid tile key");}
    private static String name(TilePyramidManifest.Tile tile){return tile.level()+"_"+tile.column()+"_"+tile.row()+".png";}
    private boolean canonical(Path p)throws IOException{try(var span=TileTiming.start(timing,TileTiming.Stage.CACHE_CANONICAL)){return p.toRealPath().equals(p);}}
    private String contentHash(byte[] bytes){try(var span=TileTiming.start(timing,TileTiming.Stage.CACHE_CONTENT_HASH)){return PreviewKeys.sha256(bytes);}}
    private void directory(Path p) throws IOException {
        try(var span=TileTiming.start(timing,TileTiming.Stage.CACHE_DIRECTORY_BOUNDARY)){
            if(!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)||!canonical(p))throw new IOException("Invalid tile directory");
        }
    }
    private void createDirectories(Path path) throws IOException {
        var missing=new ArrayDeque<Path>();Path existing=path;
        while(!Files.exists(existing,LinkOption.NOFOLLOW_LINKS)){missing.addFirst(existing);existing=existing.getParent();if(existing==null)throw new IOException("Invalid tile storage");}
        directory(existing);
        for(Path next:missing){directory(next.getParent());try{Files.createDirectory(next);}catch(FileAlreadyExistsException race){/* verify below */}directory(next);}
    }
    private void regular(Path p,long limit) throws IOException {
        try(var span=TileTiming.start(timing,TileTiming.Stage.CACHE_REGULAR_BOUNDARY)){
            if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||!canonical(p)||Files.size(p)>limit)throw new IOException("Invalid tile file");
        }
    }
    private byte[] read(Path file,int limit) throws IOException {
        PreviewCancellation.check();regular(file,limit);
        InputStream in;try(var span=TileTiming.start(timing,TileTiming.Stage.CACHE_READ_OPEN)){in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS);}
        byte[] bytes;try(var close=TileTiming.withTimedClose(in,timing,TileTiming.Stage.CACHE_READ_CLOSE)){
            try(var span=TileTiming.start(timing,TileTiming.Stage.CACHE_READ_BYTES)){bytes=in.readNBytes(limit+1);}
        }
        regular(file,limit);if(bytes.length>limit||bytes.length!=Files.size(file))throw new IOException("Tile file changed");return bytes;
    }
    private void write(Path file,byte[] bytes,List<Path> owned) throws IOException {
        FileChannel out;try(var span=TileTiming.start(timing,TileTiming.Stage.CACHE_CREATE_NEW)){out=FileChannel.open(file,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);}
        try(var close=TileTiming.withTimedClose(out,timing,TileTiming.Stage.CACHE_WRITE_CLOSE)){
            // Ownership starts only after CREATE_NEW succeeds. A colliding unknown file must be preserved.
            owned.add(file);
            try(var span=TileTiming.start(timing,TileTiming.Stage.CACHE_WRITE_BYTES)){
                var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining()){PreviewCancellation.check();out.write(buffer);}
            }
            try(var span=TileTiming.start(timing,TileTiming.Stage.CACHE_FORCE)){out.force(true);}
        }
    }
    private void inventory(Path entry,TileCacheManifest manifest) throws IOException {
        directory(entry);var expected=new HashSet<String>();expected.add("manifest.json");
        for(var tile:manifest.tiles())expected.add(name(tile));
        try(var children=Files.newDirectoryStream(entry)){for(Path child:children){
            PreviewCancellation.check();if(!expected.remove(child.getFileName().toString()))throw new IOException("Unknown tile cache entry");regular(child,MANIFEST_LIMIT);
        }}
        if(!expected.isEmpty())throw new IOException("Incomplete tile generation");
    }
    private void verifyGeneration(Path entry,TileCacheManifest manifest) throws IOException {
        inventory(entry,manifest);
        if(!manifest.equals(json.readValue(read(entry.resolve("manifest.json"),MANIFEST_LIMIT),TileCacheManifest.class)))throw new IOException("Tile manifest changed");
        for(var tile:manifest.tiles()) {
            byte[] bytes=read(entry.resolve(name(tile)),TILE_LIMIT);
            if(bytes.length!=tile.bytes()||!contentHash(bytes).equals(tile.sha256()))throw new IOException("Tile content changed");
        }
        inventory(entry,manifest);
    }
    private void cleanup(Path entry,List<Path> owned) throws IOException {
        directory(entry);
        // Verify every child before deleting anything; never recurse through unknown files/directories.
        var expected=new HashSet<Path>(owned);
        try(var children=Files.newDirectoryStream(entry)){for(var child:children){if(!expected.contains(child))throw new IOException("Unknown tile staging entry");regular(child,MANIFEST_LIMIT);}}
        for(var child:owned)if(Files.exists(child,LinkOption.NOFOLLOW_LINKS)){directory(entry);regular(child,MANIFEST_LIMIT);Files.delete(child);}
        directory(entry);Files.delete(entry);
    }
}
