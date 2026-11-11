package co.kr.satu.imagegate.maintenance.domain;
import java.nio.file.Path;
public record OriginalIntegrityOptions(Path originalsRoot,boolean workersStopped,int maxFiles,int maxEntries,long maxBytes) {
    public OriginalIntegrityOptions {
        if(originalsRoot==null||!originalsRoot.isAbsolute()||!originalsRoot.equals(originalsRoot.normalize())
                ||!workersStopped||maxFiles<1||maxFiles>10000||maxEntries<1||maxEntries>100000
                ||maxBytes<1||maxBytes>1099511627776L)throw new IllegalArgumentException("Invalid integrity options");
    }
}
