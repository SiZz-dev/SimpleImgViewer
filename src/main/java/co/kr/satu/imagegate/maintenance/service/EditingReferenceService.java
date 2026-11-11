package co.kr.satu.imagegate.maintenance.service;
import co.kr.satu.imagegate.maintenance.domain.*;
import co.kr.satu.imagegate.maintenance.repository.EditingReferenceRepository;
import java.io.IOException;
import java.time.Clock;
public final class EditingReferenceService {
    private final EditingReferenceRepository repository;private final Clock clock;
    public EditingReferenceService(EditingReferenceRepository repository,Clock clock){this.repository=repository;this.clock=clock;}
    public EditingReferenceReport run(EditingReferenceOptions options) throws IOException {return repository.inspect(options,clock.instant());}
}
