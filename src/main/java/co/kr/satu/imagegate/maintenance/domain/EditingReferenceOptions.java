package co.kr.satu.imagegate.maintenance.domain;
import java.nio.file.Path;
import java.util.List;
public record EditingReferenceOptions(Path originalsRoot,Path annotationsRoot,Path stampsRoot,
        boolean workersStopped,int maxItems,int maxEntries,long maxBytes) {
    public EditingReferenceOptions {
        if(originalsRoot==null||annotationsRoot==null||stampsRoot==null||!workersStopped||maxItems<1||maxItems>10000
                ||maxEntries<1||maxEntries>100000||maxBytes<1||maxBytes>1099511627776L)throw new IllegalArgumentException("Invalid reference options");
        var paths=List.of(originalsRoot,annotationsRoot,stampsRoot);
        for(Path p:paths)if(!p.isAbsolute()||!p.equals(p.normalize()))throw new IllegalArgumentException("Invalid root");
        for(int i=0;i<paths.size();i++)for(int j=i+1;j<paths.size();j++)
            if(paths.get(i).startsWith(paths.get(j))||paths.get(j).startsWith(paths.get(i)))throw new IllegalArgumentException("Overlapping roots");
    }
}
