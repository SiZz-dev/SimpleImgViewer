package co.kr.satu.imagegate.maintenance.domain;
import java.time.Instant;
import java.util.List;
/** Offline checks only; does not authorize production recovery or certify identity/ACL/rendering. */
public record RecoveryAcceptanceReport(int schemaVersion,Instant generatedAt,boolean offlineChecksPassed,List<Stage> stages) {
    public record Stage(String name,String status,long scanned,long bytes,int passedItems,int issues) {}
}
