package co.kr.satu.imagegate.maintenance.domain;
import java.nio.file.Path;
import java.util.List;
public record BackupManifestOptions(Path originalsRoot,Path annotationsRoot,Path stampsRoot,Path manifest,
        String manifestSha256,boolean workersStopped,int maxEntries,long maxBytes) {
    public BackupManifestOptions {
        var roots=List.of(originalsRoot,annotationsRoot,stampsRoot);
        for(Path p:List.of(originalsRoot,annotationsRoot,stampsRoot,manifest))
            if(!p.isAbsolute()||!p.equals(p.normalize()))throw new IllegalArgumentException("Invalid path");
        for(int i=0;i<roots.size();i++)for(int j=i+1;j<roots.size();j++)
            if(roots.get(i).startsWith(roots.get(j))||roots.get(j).startsWith(roots.get(i)))throw new IllegalArgumentException("Overlapping roots");
        for(Path p:roots)if(manifest.startsWith(p))throw new IllegalArgumentException("Manifest must be external");
        if(!workersStopped||manifestSha256==null||!manifestSha256.matches("[a-f0-9]{64}")
                ||maxEntries<1||maxEntries>100000||maxBytes<1||maxBytes>1099511627776L)
            throw new IllegalArgumentException("Invalid manifest options");
    }
}
