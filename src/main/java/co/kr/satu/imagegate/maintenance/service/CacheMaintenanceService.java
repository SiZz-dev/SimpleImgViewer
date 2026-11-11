package co.kr.satu.imagegate.maintenance.service;
import co.kr.satu.imagegate.maintenance.domain.*;
import co.kr.satu.imagegate.maintenance.repository.CacheOrphanRepository;
import java.io.IOException;
import java.time.Clock;
/** Explicit offline command orchestration; never scheduled or registered as a web bean. */
public final class CacheMaintenanceService {
    private final CacheOrphanRepository repository;private final Clock clock;
    public CacheMaintenanceService(CacheOrphanRepository repository,Clock clock){this.repository=repository;this.clock=clock;}
    public MaintenanceReport run(MaintenanceOptions options) throws IOException {return repository.run(options,clock.instant());}
}
