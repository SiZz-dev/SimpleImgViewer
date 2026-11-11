package co.kr.satu.imagegate.maintenance.service;
import co.kr.satu.imagegate.maintenance.domain.*;
import co.kr.satu.imagegate.maintenance.repository.BackupManifestRepository;
import java.io.IOException;
import java.time.Clock;
public final class BackupManifestService {
    private final BackupManifestRepository repository;private final Clock clock;
    public BackupManifestService(BackupManifestRepository repository,Clock clock){this.repository=repository;this.clock=clock;}
    public BackupManifestReport run(BackupManifestOptions options) throws IOException {return repository.inspect(options,clock.instant());}
}
