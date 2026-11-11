package co.kr.satu.imagegate.maintenance.domain;
import java.nio.file.Path;
public record UploadResidueOptions(Path originalsRoot,boolean workersStopped,int maxItems,int maxEntries,long maxBytes) {
    public UploadResidueOptions {
        if(originalsRoot==null||!originalsRoot.isAbsolute()||!originalsRoot.equals(originalsRoot.normalize())
                ||!workersStopped||maxItems<1||maxItems>10000||maxEntries<1||maxEntries>100000
                ||maxBytes<1||maxBytes>1125899906842624L)throw new IllegalArgumentException("Invalid residue options");
    }
}
