package co.kr.satu.imagegate.maintenance.domain;
import java.time.Instant;
import java.util.List;
public record EditingReferenceReport(int schemaVersion,Instant generatedAt,long scanned,long metadataBytesRead,
        boolean limitReached,int validPages,int validStamps,int issues,List<Item> items) {
    public record Item(String area,String relativePath,String outcome,Integer stampReferences) {}
}
