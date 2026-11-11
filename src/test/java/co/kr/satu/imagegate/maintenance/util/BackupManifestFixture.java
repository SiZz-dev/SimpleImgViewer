package co.kr.satu.imagegate.maintenance.util;
import co.kr.satu.imagegate.maintenance.domain.BackupManifestOptions;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
/** Tiny synthetic data only. Manifest creation is not a production API. */
public final class BackupManifestFixture {
    public final EditingReferenceFixture stores;public final Path manifest;
    public BackupManifestFixture(Path root) throws Exception {
        stores=new EditingReferenceFixture(root);manifest=root.resolve("backup.tsv");
        refreshManifest();
    }
    public void refreshManifest() throws Exception {
        Path root=manifest.getParent();
        var text=new StringBuilder("IMAGEGATE-MANIFEST\t1\n");
        for(String area:List.of("originals","annotations","stamps")){
            Path directory=root.resolve(area);
            try(var paths=Files.walk(directory)){
                for(Path path:paths.sorted().toList()){
                    String relative=path.equals(directory)?".":directory.relativize(path).toString().replace('\\','/');
                    boolean dir=Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS);
                    text.append(dir?"D":"F").append('\t').append(area).append('\t').append(relative)
                            .append('\t').append(dir?0:Files.size(path)).append('\t')
                            .append(dir?"-":hash(Files.readAllBytes(path))).append('\n');
                }
            }
        }
        Files.writeString(manifest,text);
    }
    public static String hash(byte[] bytes) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    public BackupManifestOptions options() throws Exception {return options(10000,10485760);}
    public BackupManifestOptions options(int entries,long bytes) throws Exception {
        return new BackupManifestOptions(stores.originals,stores.annotations,stores.stamps,manifest,
                hash(Files.readAllBytes(manifest)),true,entries,bytes);
    }
}
