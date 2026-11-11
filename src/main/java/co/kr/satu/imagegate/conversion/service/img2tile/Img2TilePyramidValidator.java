package co.kr.satu.imagegate.conversion.service.img2tile;

import co.kr.satu.imagegate.conversion.domain.TilePyramidLayout;
import co.kr.satu.imagegate.conversion.domain.TilePyramidManifest;
import co.kr.satu.imagegate.conversion.exception.ConversionException;
import co.kr.satu.imagegate.conversion.util.PreviewCancellation;
import co.kr.satu.imagegate.conversion.util.PreviewKeys;
import co.kr.satu.imagegate.conversion.util.TilePngVerifier;
import co.kr.satu.imagegate.conversion.util.TileTiming;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.xml.sax.*;

/** Not a Spring bean. Requires an exclusively owned output directory and a stopped producer.
 * Source facts must come from trusted original inspection, never from the native descriptor.
 * This result does not authorize access, attest source pixels, sanitize PNG metadata, or publish files.
 */
public final class Img2TilePyramidValidator {
    public static final String MODULE = "native-module-v1/c03da1c62f601ad1ac57b575f0a34d740fbd89ab969cd2a9b5c650a416bd3082";
    private static final String NS="http://schemas.microsoft.com/deepzoom/2008";
    private static final int DZI_LIMIT=8192;
    private final Limits limits;
    private final TileTiming timing;
    public Img2TilePyramidValidator() { this(new Limits(40_000_000,4096,2_097_152,268_435_456)); }
    public Img2TilePyramidValidator(Limits limits) { this(limits,null); }
    public Img2TilePyramidValidator(Limits limits,TileTiming timing) { this.limits=Objects.requireNonNull(limits);this.timing=timing; }
    public record Limits(long sourcePixels, int tiles, int tileBytes, long pageBytes) {
        public Limits {
            if(sourcePixels<1 || sourcePixels>40_000_000 || tiles<1 || tiles>4096
                    || tileBytes<64 || tileBytes>2_097_152 || pageBytes<64 || pageBytes>268_435_456)
                throw new IllegalArgumentException("Invalid tile validation budget");
        }
    }
    public record Source(int width, int height, int page, String format, int orientation,
                         int bitsPerSample, int samplesPerPixel, String moduleIdentity) {}

    public TilePyramidManifest validate(Path output, Source source) throws IOException {
        return validateForPublication(output,source).manifest();
    }
    /** Opaque in-process evidence tied to this validator, canonical directory, source and exact verified bytes. */
    public static final class PublicationProof {
        private final Img2TilePyramidValidator owner;private final Path root;private final Source source;
        private final TilePyramidManifest manifest;private final Map<String,Stamp> inventory;
        private PublicationProof(Img2TilePyramidValidator owner,Path root,Source source,TilePyramidManifest manifest,Map<String,Stamp> inventory){
            this.owner=owner;this.root=root;this.source=source;this.manifest=manifest;this.inventory=Map.copyOf(inventory);
        }
        public TilePyramidManifest manifest(){return manifest;}
    }
    public PublicationProof validateForPublication(Path output, Source source) throws IOException {
        if(source==null || !MODULE.equals(source.moduleIdentity()) || !"TIFF_RGB".equals(source.format())
                || source.orientation()!=1 || source.bitsPerSample()!=8 || source.samplesPerPixel()!=3)
            throw failure(ConversionException.Code.UNAVAILABLE);
        if(source.width()<1 || source.height()<1 || source.page()<1
                || (long)source.width()*source.height()>limits.sourcePixels())
            throw failure(ConversionException.Code.INVALID_INPUT);
        var layout=new TilePyramidLayout(source.width(),source.height(),256,0);
        if(layout.totalTiles()>limits.tiles()) throw failure(ConversionException.Code.INVALID_INPUT);
        Path root=output.toAbsolutePath().normalize();
        try {
            PreviewCancellation.check();
            var before=inventory(root,layout);
            byte[] descriptor=read(root.resolve("mipmap.dzi"),DZI_LIMIT,before.get("mipmap.dzi"));
            descriptor(descriptor,source);
            long total=descriptor.length;
            var tiles=new ArrayList<TilePyramidManifest.Tile>();
            for(int z=0;z<=layout.maxLevel();z++) {
                var grid=layout.level(z);
                for(int col=0;col<grid.columns();col++) for(int row=0;row<grid.rows();row++) {
                    PreviewCancellation.check();
                    String name="mipmap_files/"+z+"/"+col+"_"+row+".png";
                    long size=before.get(name).size();
                    if(size>limits.pageBytes()-total) throw failure(ConversionException.Code.INVALID_OUTPUT);
                    byte[] bytes=read(root.resolve(name),limits.tileBytes(),before.get(name));
                    var bounds=layout.tile(z,col,row).stored();
                    try(var span=TileTiming.start(timing,TileTiming.Stage.NATIVE_PNG_CHECK)){TilePngVerifier.verify(bytes,bounds.width(),bounds.height());}
                    total+=bytes.length;
                    tiles.add(new TilePyramidManifest.Tile(z,col,row,bounds.width(),bounds.height(),bytes.length,hash(bytes)));
                }
            }
            if(total>limits.pageBytes() || !before.equals(inventory(root,layout))) throw failure(ConversionException.Code.INVALID_OUTPUT);
            // Re-read hashes, as same-size writes need not reliably change filesystem timestamps.
            if(!hash(descriptor).equals(hash(read(root.resolve("mipmap.dzi"),DZI_LIMIT,before.get("mipmap.dzi")))))
                throw failure(ConversionException.Code.INVALID_OUTPUT);
            for(var tile:tiles) {
                String name="mipmap_files/"+tile.level()+"/"+tile.column()+"_"+tile.row()+".png";
                if(!tile.sha256().equals(hash(read(root.resolve(name),limits.tileBytes(),before.get(name)))))
                    throw failure(ConversionException.Code.INVALID_OUTPUT);
            }
            if(!before.equals(inventory(root,layout))) throw failure(ConversionException.Code.INVALID_OUTPUT);
            var manifest=new TilePyramidManifest(1,"img2tile-tiff-rgb8-o1-v1",MODULE,source.page(),layout,
                    "original-upright-pixel-edges",hash(descriptor),total,tiles);
            return new PublicationProof(this,root,source,manifest,before);
        } catch(InterruptedIOException e) { throw e; }
        catch(ConversionException e) { throw e; }
        catch(IOException | RuntimeException e) {
            var rejected=failure(ConversionException.Code.INVALID_OUTPUT); rejected.initCause(e); throw rejected;
        }
    }

    /** Re-checks all bytes and inventory; caller must retain private ownership throughout use. */
    public void verifyUnchanged(Path output, Source source, TilePyramidManifest expected) throws IOException {
        if(!validate(output,source).equals(expected)) throw failure(ConversionException.Code.INVALID_OUTPUT);
    }
    /** Full path/identity/inventory and SHA recheck. Exact bytes have already passed CRC and decode in this proof. */
    public void verifyForPublication(Path output,Source source,PublicationProof proof)throws IOException{
        Path root=output.toAbsolutePath().normalize();
        if(proof==null||proof.owner!=this||!root.equals(proof.root)||!Objects.equals(source,proof.source))throw failure(ConversionException.Code.INVALID_OUTPUT);
        var before=inventory(root,proof.manifest.layout());
        if(!before.equals(proof.inventory))throw failure(ConversionException.Code.INVALID_OUTPUT);
        if(!proof.manifest.descriptorSha256().equals(hash(read(root.resolve("mipmap.dzi"),DZI_LIMIT,before.get("mipmap.dzi")))))throw failure(ConversionException.Code.INVALID_OUTPUT);
        for(var tile:proof.manifest.tiles()){
            PreviewCancellation.check();String name="mipmap_files/"+tile.level()+"/"+tile.column()+"_"+tile.row()+".png";
            if(!tile.sha256().equals(hash(read(root.resolve(name),limits.tileBytes(),before.get(name)))))throw failure(ConversionException.Code.INVALID_OUTPUT);
        }
        if(!before.equals(inventory(root,proof.manifest.layout())))throw failure(ConversionException.Code.INVALID_OUTPUT);
    }

    private Map<String,Stamp> inventory(Path root, TilePyramidLayout layout) throws IOException {
        try(var span=TileTiming.start(timing,TileTiming.Stage.NATIVE_INVENTORY)){return inventoryChecked(root,layout);}
    }
    private Map<String,Stamp> inventoryChecked(Path root, TilePyramidLayout layout) throws IOException {
        var expected=new HashMap<String,Boolean>(); expected.put("",true); expected.put("mipmap.dzi",false);
        expected.put("mipmap_files",true);
        for(int z=0;z<=layout.maxLevel();z++) {
            expected.put("mipmap_files/"+z,true); var grid=layout.level(z);
            for(int c=0;c<grid.columns();c++) for(int r=0;r<grid.rows();r++) expected.put("mipmap_files/"+z+"/"+c+"_"+r+".png",false);
        }
        var found=new HashMap<String,Stamp>(); var keys=new HashSet<Object>();
        // No FOLLOW_LINKS, fixed depth; every visited entry must be an exact expected path.
        Files.walkFileTree(root,EnumSet.noneOf(FileVisitOption.class),4,new SimpleFileVisitor<>() {
            private void entry(Path path,BasicFileAttributes attrs) throws IOException {
                PreviewCancellation.check();
                attrs=attributes(path);
                String name=root.relativize(path).toString().replace('\\','/'); Boolean directory=expected.get(name);
                if(!canonical(path)) throw new IOException("Non-canonical output entry");
                if(directory==null || attrs.isSymbolicLink() || attrs.isOther() || (directory?!attrs.isDirectory():!attrs.isRegularFile())
                        || !canonical(path) || (attrs.fileKey()!=null && !keys.add(attrs.fileKey())))
                    throw failure(ConversionException.Code.INVALID_OUTPUT);
                if(!directory && (attrs.size()<1 || attrs.size()>(name.equals("mipmap.dzi")?DZI_LIMIT:limits.tileBytes())))
                    throw failure(ConversionException.Code.INVALID_OUTPUT);
                found.put(name,Stamp.of(attrs));
                if(found.size()>expected.size()) throw failure(ConversionException.Code.INVALID_OUTPUT);
            }
            @Override public FileVisitResult preVisitDirectory(Path path,BasicFileAttributes attrs) throws IOException { entry(path,attrs); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult visitFile(Path path,BasicFileAttributes attrs) throws IOException { entry(path,attrs); return FileVisitResult.CONTINUE; }
        });
        if(!found.keySet().equals(expected.keySet())) throw failure(ConversionException.Code.INVALID_OUTPUT);
        long total=0;
        for(var item:found.entrySet()) if(!expected.get(item.getKey())) {
            if(item.getValue().size()>limits.pageBytes()-total) throw failure(ConversionException.Code.INVALID_OUTPUT);
            total+=item.getValue().size();
        }
        return found;
    }
    private record Stamp(Object key,long size,java.nio.file.attribute.FileTime modified,java.nio.file.attribute.FileTime created,boolean directory) {
        static Stamp of(BasicFileAttributes a) { return new Stamp(a.fileKey(),a.size(),a.lastModifiedTime(),a.creationTime(),a.isDirectory()); }
    }
    private boolean canonical(Path path)throws IOException{try(var span=TileTiming.start(timing,TileTiming.Stage.NATIVE_CANONICAL)){return path.toRealPath().equals(path);}}
    private BasicFileAttributes attributes(Path path)throws IOException{try(var span=TileTiming.start(timing,TileTiming.Stage.NATIVE_ATTRIBUTES)){return Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}}
    private String hash(byte[] bytes){try(var span=TileTiming.start(timing,TileTiming.Stage.NATIVE_CONTENT_HASH)){return PreviewKeys.sha256(bytes);}}
    private byte[] read(Path file,int limit,Stamp expected) throws IOException {
        PreviewCancellation.check();
        if(!canonical(file) || !Stamp.of(attributes(file)).equals(expected))
            throw failure(ConversionException.Code.INVALID_OUTPUT);
        byte[] bytes;
        try(var span=TileTiming.start(timing,TileTiming.Stage.NATIVE_READ_BYTES);var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) { bytes=input.readNBytes(limit+1); }
        if(bytes.length>limit || bytes.length!=expected.size()
                || !Stamp.of(attributes(file)).equals(expected))
            throw failure(ConversionException.Code.INVALID_OUTPUT);
        return bytes;
    }
    private static void descriptor(byte[] bytes,Source source) throws IOException {
        try {
            var factory=DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            factory.setAttribute("jdk.xml.maxElementDepth","4");
            factory.setAttribute("jdk.xml.elementAttributeLimit","8");
            factory.setAttribute("jdk.xml.maxXMLNameLimit","128");
            factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
            var builder=factory.newDocumentBuilder();
            builder.setEntityResolver((publicId,systemId)-> { throw new SAXException("External resource rejected"); });
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void error(SAXParseException e) throws SAXException { throw e; }
                @Override public void fatalError(SAXParseException e) throws SAXException { throw e; }
            });
            var document=builder.parse(new ByteArrayInputStream(bytes)); var image=document.getDocumentElement();
            element(image,"Image",Set.of("Format","Overlap","TileSize","xmlns"));
            if(!"png".equals(image.getAttribute("Format")) || !"1".equals(image.getAttribute("Overlap"))
                    || !"256".equals(image.getAttribute("TileSize")) || !NS.equals(image.getAttribute("xmlns"))) invalidDescriptor();
            var children=elements(image); if(children.size()!=1) invalidDescriptor(); var size=children.getFirst();
            element(size,"Size",Set.of("Width","Height"));
            if(!Integer.toString(source.width()).equals(size.getAttribute("Width"))
                    || !Integer.toString(source.height()).equals(size.getAttribute("Height")) || !elements(size).isEmpty()) invalidDescriptor();
            for(Node node=document.getFirstChild();node!=null;node=node.getNextSibling()) if(node!=image) invalidDescriptor();
        } catch(IOException e) { throw e; }
        catch(Exception e) { invalidDescriptor(); }
    }
    private static void element(Element e,String name,Set<String> attrs) throws IOException {
        if(!name.equals(e.getTagName()) || !NS.equals(e.getNamespaceURI()) || e.getAttributes().getLength()!=attrs.size()) invalidDescriptor();
        for(int i=0;i<e.getAttributes().getLength();i++) {
            Node a=e.getAttributes().item(i);
            if(!attrs.contains(a.getNodeName()) || (a.getNamespaceURI()!=null && !"xmlns".equals(a.getNodeName()))) invalidDescriptor();
        }
    }
    private static List<Element> elements(Element e) throws IOException {
        var result=new ArrayList<Element>();
        for(Node n=e.getFirstChild();n!=null;n=n.getNextSibling()) {
            if(n instanceof Element child) result.add(child);
            else if(n.getNodeType()!=Node.TEXT_NODE || !n.getTextContent().isBlank()) invalidDescriptor();
        }
        return result;
    }
    private static void invalidDescriptor() throws IOException { throw new IOException("Invalid tile descriptor"); }
    private static ConversionException failure(ConversionException.Code code) { return new ConversionException(code,"Tile validation rejected"); }
}
