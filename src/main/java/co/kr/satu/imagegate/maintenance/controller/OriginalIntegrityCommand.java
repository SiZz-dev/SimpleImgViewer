package co.kr.satu.imagegate.maintenance.controller;
import co.kr.satu.imagegate.maintenance.domain.OriginalIntegrityOptions;
import co.kr.satu.imagegate.maintenance.repository.OriginalIntegrityRepository;
import co.kr.satu.imagegate.maintenance.service.OriginalIntegrityService;
import co.kr.satu.imagegate.maintenance.util.ReadOnlyStorageLock;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;
/** Offline, read-only original inspection. No Spring or installation config import. */
public final class OriginalIntegrityCommand {
    private OriginalIntegrityCommand() {}
    public static int run(String[] args,PrintStream out,PrintStream error) {
        if(Arrays.equals(args,new String[]{"--help"})) {
            out.println("ImageGate original integrity verification (read-only, stopped workers required)");
            out.println("--originals-root ABSOLUTE_PATH --workers-stopped [--max-files 1000] [--max-entries 10000] [--max-bytes 1073741824]");return 0;
        }
        OriginalIntegrityOptions options;
        try {
            var values=new HashMap<String,String>();var seen=new HashSet<String>();boolean stopped=false;
            for(int i=0;i<args.length;i++) {
                String flag=args[i];if(!seen.add(flag))throw new IllegalArgumentException();
                if(flag.equals("--workers-stopped")){stopped=true;continue;}
                if(!Set.of("--originals-root","--max-files","--max-entries","--max-bytes").contains(flag)||++i>=args.length)throw new IllegalArgumentException();
                values.put(flag,args[i]);
            }
            options=new OriginalIntegrityOptions(Path.of(Objects.requireNonNull(values.get("--originals-root"))),stopped,
                    Integer.parseInt(values.getOrDefault("--max-files","1000")),Integer.parseInt(values.getOrDefault("--max-entries","10000")),
                    Long.parseLong(values.getOrDefault("--max-bytes","1073741824")));
        }catch(RuntimeException invalid){error.println("INVALID_OPTIONS: use --maintenance --verify-originals --help");return 2;}
        try {
            var report=new OriginalIntegrityService(new OriginalIntegrityRepository(),Clock.systemUTC()).run(options);
            out.println(JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(report));
            return report.limitReached()||report.issues()>0?3:0;
        }catch(ReadOnlyStorageLock.StorageInUseException busy){error.println("STORAGE_IN_USE: stop ImageGate and all original writers");return 4;}
        catch(Exception unavailable){error.println("INTEGRITY_UNAVAILABLE: check the explicit originals root, permissions and stopped workers");return 5;}
    }
}
