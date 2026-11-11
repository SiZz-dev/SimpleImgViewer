package co.kr.satu.imagegate.maintenance.repository;

import co.kr.satu.imagegate.maintenance.domain.*;
import co.kr.satu.imagegate.maintenance.util.ReadOnlyStorageScope;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** Offline metadata/reference inspection; no runtime stores, image decoders, writes or repairs. */
public final class EditingReferenceRepository {
    private static final String UUID="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final String TENANT="tenant-[a-f0-9]{64}",USER="user-[a-f0-9]{64}";
    private static final Set<String> ORIGINAL_FIELDS=Set.of("mediaId","tenantId","ownerId","version","fileName","type","mime","size","sha256","storedFileName","createdAt");
    private final Runnable beforeRecheck;
    public EditingReferenceRepository(){this(()->{});}
    EditingReferenceRepository(Runnable beforeRecheck){this.beforeRecheck=beforeRecheck;}
    private static final class Limit extends IOException {}
    private static final class Changed extends IOException {}
    private static final class Finding extends IOException {final String outcome;Finding(String outcome){this.outcome=outcome;}}
    private record Stamp(long size,Object key,FileTime modified,FileTime created,boolean directory) {}
    private static Stamp stamp(Path path,boolean directory) throws IOException {
        var a=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if((directory?!a.isDirectory():!a.isRegularFile())||!path.toRealPath().equals(path))throw new IOException("Invalid inspection boundary");
        return new Stamp(a.size(),a.fileKey(),a.lastModifiedTime(),a.creationTime(),a.isDirectory());
    }
    private static boolean uuid(String id){return id!=null&&id.matches(UUID);}
    private static String identity(String id){
        if(id==null||!id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))throw new IllegalArgumentException();
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(id.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static void name(String value){if(value==null||value.isBlank()||value.length()>255||value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException();}
    private static final class Budget {
        final EditingReferenceOptions options;long scanned,bytes;boolean limited;
        final List<EditingReferenceReport.Item> items=new ArrayList<>();
        Budget(EditingReferenceOptions options){this.options=options;}
        void next() throws Limit {if(scanned>=options.maxEntries()){limited=true;throw new Limit();}scanned++;}
        void room() throws Limit {if(items.size()>=options.maxItems()){limited=true;throw new Limit();}}
        void allowance(long size) throws Limit {if(size>options.maxBytes()-bytes){limited=true;throw new Limit();}}
        void item(String area,String path,String outcome,Integer references) throws Limit {room();items.add(new EditingReferenceReport.Item(area,path,outcome,references));}
    }
    private static final class Snapshot {
        final Budget budget;final Map<Path,Stamp> paths=new LinkedHashMap<>();final Map<Path,byte[]> metadata=new LinkedHashMap<>();
        final Set<Path> absent=new HashSet<>();
        Snapshot(Budget budget){this.budget=budget;}
        Stamp touch(Path p,boolean directory) throws IOException {
            budget.next();var value=stamp(p,directory);var old=paths.putIfAbsent(p,value);
            if(old!=null&&!old.equals(value))throw new Changed();return value;
        }
        boolean exists(Path p) throws IOException {budget.next();boolean exists=Files.exists(p,LinkOption.NOFOLLOW_LINKS);if(!exists)absent.add(p);return exists;}
        Path directory(Path parent,String name,String missing) throws IOException {
            Path p=parent.resolve(name);if(!exists(p))throw new Finding(missing);touch(p,true);return p;
        }
        Stamp regular(Path p,long max) throws IOException {
            if(!exists(p))throw new IOException("Missing private file");
            var value=touch(p,false);if(value.size()<1||value.size()>max)throw new IOException("Invalid private file size");return value;
        }
        byte[] raw(Path p,long size) throws IOException {
            budget.allowance(size);
            try(var input=Files.newInputStream(p,LinkOption.NOFOLLOW_LINKS)){
                byte[] data=input.readNBytes(Math.toIntExact(size));budget.bytes+=data.length;
                if(data.length!=size)throw new Changed();return data;
            }
        }
        Properties properties(Path p,long max) throws IOException {
            var a=regular(p,max);byte[] bytes=raw(p,a.size());
            if(!a.equals(touch(p,false)))throw new Changed();
            byte[] prior=metadata.putIfAbsent(p,bytes);if(prior!=null&&!Arrays.equals(prior,bytes))throw new Changed();
            var result=new Properties(){
                @Override public synchronized Object put(Object key,Object value){
                    if(size()>=2048&&!containsKey(key))throw new IllegalArgumentException("Private metadata property limit");
                    return super.put(key,value);
                }
            };
            result.load(new ByteArrayInputStream(bytes));return result;
        }
        void verify() throws IOException {
            for(var e:paths.entrySet())if(!e.getValue().equals(touch(e.getKey(),e.getValue().directory())))throw new Changed();
            for(var e:metadata.entrySet())if(!Arrays.equals(e.getValue(),raw(e.getKey(),e.getValue().length)))throw new Changed();
            for(Path p:absent){budget.next();if(Files.exists(p,LinkOption.NOFOLLOW_LINKS))throw new Changed();}
            for(var e:paths.entrySet())if(!e.getValue().equals(touch(e.getKey(),e.getValue().directory())))throw new Changed();
        }
    }
    public EditingReferenceReport inspect(EditingReferenceOptions options,Instant now) throws IOException {
        try(var scope=new ReadOnlyStorageScope(options.originalsRoot(),options.annotationsRoot(),options.stampsRoot(),options.workersStopped())){
            return inspectWithinScope(options,now,scope);
        }
    }
    /** Reuses a live, root-bound scope; callers cannot bypass marker validation. */
    public EditingReferenceReport inspectWithinScope(EditingReferenceOptions options,Instant now,ReadOnlyStorageScope scope) throws IOException {
        scope.verify(options.originalsRoot(),options.annotationsRoot(),options.stampsRoot());
        var roots=List.of(options.originalsRoot(),options.annotationsRoot(),options.stampsRoot());
        var rootStamps=new ArrayList<Stamp>();for(Path root:roots)rootStamps.add(stamp(root,true));
        var b=new Budget(options);
        {
            try{
                walk(options.stampsRoot(),"stamps",new String[]{TENANT,USER,UUID},new ArrayList<>(),b);
                walk(options.annotationsRoot(),"annotations",new String[]{TENANT,UUID,UUID,"page-([1-9][0-9]{0,3}|10000)[.]properties"},new ArrayList<>(),b);
            }catch(Limit limit){/* Explicit bounded partial report. */}
            scope.verify();
            for(int i=0;i<roots.size();i++)if(!rootStamps.get(i).equals(stamp(roots.get(i),true)))throw new Changed();
        }
        int pages=(int)b.items.stream().filter(i->i.outcome().equals("ANNOTATION_REFERENCES_VALID")).count();
        int stamps=(int)b.items.stream().filter(i->i.outcome().equals("STAMP_STRUCTURE_VALID")).count();
        return new EditingReferenceReport(1,now,b.scanned,b.bytes,b.limited,pages,stamps,b.items.size()-pages-stamps,List.copyOf(b.items));
    }
    private void walk(Path directory,String area,String[] patterns,List<String> parts,Budget b) throws IOException {
        var before=stamp(directory,true);int depth=parts.size();
        try(var children=Files.newDirectoryStream(directory)){
            for(Path child:children){
                b.next();String name=child.getFileName().toString();
                if(depth==0&&name.equals("writer.lock"))continue;
                if(!name.matches(patterns[depth])){b.item(area,null,"UNEXPECTED_ENTRY",null);continue;}
                var next=new ArrayList<>(parts);next.add(name);
                if(depth==patterns.length-1){b.room();inspectCandidate(child,area,next,b);}
                else{
                    try{walk(child,area,patterns,next,b);}
                    catch(Limit|Changed special){throw special;}
                    catch(IOException invalid){b.item(area,null,"INVALID_OR_UNREADABLE_PATH",null);}
                }
            }
        }
        if(!before.equals(stamp(directory,true)))throw new Changed();
    }
    private void inspectCandidate(Path path,String area,List<String> parts,Budget b) throws IOException {
        String relative=String.join("/",parts);var w=new Snapshot(b);
        try{
            Path root=area.equals("stamps")?b.options.stampsRoot():b.options.annotationsRoot();w.touch(root,true);
            Path parent=root;for(int i=0;i<parts.size()-1;i++){parent=parent.resolve(parts.get(i));w.touch(parent,true);}
            String outcome;Integer references=null;
            try{
                if(area.equals("stamps")){validateStamp(w,parts.get(0),parts.get(1),parts.get(2));outcome="STAMP_STRUCTURE_VALID";}
                else{references=validatePage(w,path,parts);outcome="ANNOTATION_REFERENCES_VALID";}
            }catch(Limit|Changed special){throw special;}
            catch(Finding finding){outcome=finding.outcome;}
            catch(IOException|IllegalArgumentException|NullPointerException invalid){outcome=area.equals("stamps")?"INVALID_STAMP":"INVALID_ANNOTATION";}
            beforeRecheck.run();w.verify();b.item(area,relative,outcome,references);
        }catch(Limit limit){throw limit;}
        catch(Changed changed){b.item(area,relative,"CHANGED_DURING_SCAN",null);}
        catch(IOException|IllegalArgumentException|NullPointerException invalid){b.item(area,relative,"INVALID_OR_UNREADABLE",null);}
    }
    private void validateStamp(Snapshot w,String tenant,String owner,String id) throws IOException {
        Path root=w.budget.options.stampsRoot();w.touch(root,true);
        Path entry=w.directory(w.directory(w.directory(root,tenant,"MISSING_STAMP"),owner,"MISSING_STAMP"),id,"MISSING_STAMP");
        try{
            var p=w.properties(entry.resolve("metadata.properties"),8192);
            if(!p.stringPropertyNames().equals(Set.of("id","name","width","height","previewWidth","previewHeight"))||!id.equals(p.getProperty("id")))throw new IllegalArgumentException();
            name(p.getProperty("name"));
            int width=Integer.parseInt(p.getProperty("width")),height=Integer.parseInt(p.getProperty("height")),
                pw=Integer.parseInt(p.getProperty("previewWidth")),ph=Integer.parseInt(p.getProperty("previewHeight"));
            if(width<1||width>2048||height<1||height>2048||pw<1||pw>256||ph<1||ph>256)throw new IllegalArgumentException();
            w.regular(entry.resolve("stamp.png"),16777216);w.regular(entry.resolve("preview.png"),1048576);
            try(var files=Files.newDirectoryStream(entry)){
                for(Path file:files){w.budget.next();if(!Set.of("metadata.properties","stamp.png","preview.png").contains(file.getFileName().toString()))throw new Finding("UNEXPECTED_STAMP_CONTENT");}
            }
        }catch(Limit|Changed|Finding special){throw special;}
        catch(IOException|IllegalArgumentException|NullPointerException invalid){throw new Finding("INVALID_STAMP_REFERENCE");}
    }
    private int validatePage(Snapshot w,Path file,List<String> parts) throws IOException {
        String tenant=parts.get(0),id=parts.get(1),version=parts.get(2);String pageName=parts.get(3);
        int page=Integer.parseInt(pageName.substring(5,pageName.length()-11));
        var p=w.properties(file,2097152);var fields=new HashSet<>(Set.of("version","page","space","revision","count"));
        int count=Integer.parseInt(p.getProperty("count")),revision=Integer.parseInt(p.getProperty("revision"));
        if(count<0||count>100||revision<0||!version.equals(p.getProperty("version"))||page!=Integer.parseInt(p.getProperty("page")))throw new IllegalArgumentException();
        if(!("original:"+version+":"+page+":upright-v1").equals(p.getProperty("space")))throw new Finding("COORDINATE_SPACE_MISMATCH");
        var refs=new ArrayList<String[]>();var ids=new HashSet<String>();
        for(int i=0;i<count;i++){
            String k="item."+i+".",itemId=p.getProperty(k+"id"),type=p.getProperty(k+"type");
            if(!uuid(itemId)||!ids.add(itemId))throw new IllegalArgumentException();
            for(String f:List.of("id","type","x","y","width","height","created","updated"))fields.add(k+f);
            double x=Double.parseDouble(p.getProperty(k+"x")),y=Double.parseDouble(p.getProperty(k+"y")),width=Double.parseDouble(p.getProperty(k+"width")),height=Double.parseDouble(p.getProperty(k+"height"));
            if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(width)||!Double.isFinite(height)||x<0||y<0||width<1||height<1||x+width>40000000||y+height>40000000)throw new IllegalArgumentException();
            Instant.parse(p.getProperty(k+"created"));Instant.parse(p.getProperty(k+"updated"));
            if("NOTE".equals(type)){
                fields.add(k+"text");String text=p.getProperty(k+"text");
                if(text==null||text.isBlank()||text.length()>2000||text.codePoints().anyMatch(c->Character.isISOControl(c)&&c!=10&&c!=13&&c!=9))throw new IllegalArgumentException();
            }else if("STAMP".equals(type)){
                fields.add(k+"stamp");fields.add(k+"stampOwner");String stampId=p.getProperty(k+"stamp");
                if(!uuid(stampId))throw new IllegalArgumentException();refs.add(new String[]{"user-"+identity(p.getProperty(k+"stampOwner")),stampId});
            }else throw new IllegalArgumentException();
        }
        if(!p.stringPropertyNames().equals(fields))throw new IllegalArgumentException();
        validateOriginal(w,tenant,id,version);
        for(String[] ref:refs)validateStamp(w,tenant,ref[0],ref[1]);return refs.size();
    }
    private void validateOriginal(Snapshot w,String tenant,String id,String version) throws IOException {
        Path root=w.budget.options.originalsRoot();w.touch(root,true);
        Path entry=w.directory(w.directory(root,tenant,"MISSING_ORIGINAL"),id,"MISSING_ORIGINAL");
        if(!w.exists(entry.resolve("metadata.properties")))throw new Finding("MISSING_ORIGINAL_CATALOG");
        try{
            var p=w.properties(entry.resolve("metadata.properties"),16384);
            if(!p.stringPropertyNames().equals(ORIGINAL_FIELDS)||!id.equals(p.getProperty("mediaId"))
                    ||!tenant.equals("tenant-"+identity(p.getProperty("tenantId")))||!uuid(p.getProperty("version")))throw new IllegalArgumentException();
            identity(p.getProperty("ownerId"));name(p.getProperty("fileName"));Instant.parse(p.getProperty("createdAt"));
            long size=Long.parseLong(p.getProperty("size"));String stored=p.getProperty("storedFileName"),sha=p.getProperty("sha256"),mime=p.getProperty("mime");
            if(size<1||size>1099511627776L||stored==null||!stored.matches("original[.][a-z0-9]{1,8}")||sha==null||!sha.matches("[a-f0-9]{64}")||mime==null||mime.isBlank())throw new IllegalArgumentException();
            if(!"IMAGE".equals(p.getProperty("type")))throw new Finding("ORIGINAL_TYPE_MISMATCH");
            if(w.regular(entry.resolve(stored),1099511627776L).size()!=size)throw new IllegalArgumentException();
            if(!version.equals(p.getProperty("version")))throw new Finding("ORIGINAL_VERSION_MISMATCH");
        }catch(Limit|Changed|Finding special){throw special;}
        catch(IOException|IllegalArgumentException|NullPointerException invalid){throw new Finding("INVALID_ORIGINAL");}
    }
}
