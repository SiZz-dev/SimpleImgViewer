package co.kr.satu.imagegate.maintenance.service;
import co.kr.satu.imagegate.maintenance.domain.*;
import co.kr.satu.imagegate.maintenance.repository.OriginalIntegrityRepository;
import java.io.IOException;
import java.time.Clock;
public final class OriginalIntegrityService {
    private final OriginalIntegrityRepository repository;private final Clock clock;
    public OriginalIntegrityService(OriginalIntegrityRepository repository,Clock clock){this.repository=repository;this.clock=clock;}
    public OriginalIntegrityReport run(OriginalIntegrityOptions options) throws IOException {return repository.inspect(options,clock.instant());}
}
