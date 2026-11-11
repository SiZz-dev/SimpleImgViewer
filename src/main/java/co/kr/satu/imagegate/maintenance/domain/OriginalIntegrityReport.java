package co.kr.satu.imagegate.maintenance.domain;
import java.time.Instant;
import java.util.List;
public record OriginalIntegrityReport(int schemaVersion,Instant generatedAt,long scanned,long hashedBytes,
        boolean limitReached,int verified,int issues,List<Item> items) {
    public record Item(String mediaId,String outcome) {}
}
