package co.kr.satu.imagegate.maintenance.service;
import co.kr.satu.imagegate.maintenance.domain.*;
import co.kr.satu.imagegate.maintenance.repository.UploadResidueRepository;
import java.io.IOException;
import java.time.Clock;
public final class UploadResidueService {
    private final UploadResidueRepository repository;
    private final Clock clock;
    public UploadResidueService(UploadResidueRepository repository,Clock clock){this.repository=repository;this.clock=clock;}
    public UploadResidueReport run(UploadResidueOptions options) throws IOException {return repository.inspect(options,clock.instant());}
}
