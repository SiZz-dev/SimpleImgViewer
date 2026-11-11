package co.kr.satu.imagegate.maintenance.domain;
import java.time.Instant;
import java.util.List;
public record UploadResidueReport(int schemaVersion,Instant generatedAt,long scanned,long observedBytes,
        boolean limitReached,long publishedNameEntriesSkipped,int recognizedResidues,List<Item> items) {
    public record Item(String relativePath,String outcome,String layout,Long payloadBytes,Integer fileCount,Instant lastModifiedAt) {}
}
