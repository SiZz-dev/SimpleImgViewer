package co.kr.satu.imagegate.maintenance.domain;
import java.time.Instant;
import java.util.List;
public record BackupManifestReport(int schemaVersion,Instant generatedAt,long scanned,long hashedBytes,
        boolean limitReached,int matched,int issues,List<Item> items) {
    // No paths, filenames, expected/actual hashes or tenant/user identifiers.
    public record Item(Integer manifestLine,String outcome) {}
}
