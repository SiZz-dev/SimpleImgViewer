package co.kr.satu.imagegate.maintenance.domain;
import java.util.List;
public record MaintenanceReport(int schemaVersion,String generatedAt,boolean apply,long scanned,boolean limitReached,
        long candidateBytes,int deleted,long deletedBytes,int skipped,List<Item> items) {
    public record Item(String cache,String relativePath,String kind,String outcome,long bytes,long deletedBytes) {}
}
