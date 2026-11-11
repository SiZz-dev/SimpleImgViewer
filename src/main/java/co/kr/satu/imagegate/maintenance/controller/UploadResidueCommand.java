package co.kr.satu.imagegate.maintenance.controller;
import co.kr.satu.imagegate.maintenance.domain.UploadResidueOptions;
import co.kr.satu.imagegate.maintenance.repository.UploadResidueRepository;
import co.kr.satu.imagegate.maintenance.service.UploadResidueService;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;
/** Offline inventory only. No Spring, config import, payload reads or delete option. */
public final class UploadResidueCommand {
    private UploadResidueCommand() {}
    public static int run(String[] args,PrintStream out,PrintStream error){
        if(Arrays.equals(args,new String[]{"--help"})){
            out.println("ImageGate upload residue inventory (read-only, stopped workers required; not deletion approval)");
            out.println("--originals-root ABSOLUTE_PATH --workers-stopped [--max-items 1000] [--max-entries 10000] [--max-bytes 1073741824]");return 0;
        }
        UploadResidueOptions options;
        try{
            var values=new HashMap<String,String>();var seen=new HashSet<String>();boolean stopped=false;
            for(int i=0;i<args.length;i++){
                String flag=args[i];if(!seen.add(flag))throw new IllegalArgumentException();
                if(flag.equals("--workers-stopped")){stopped=true;continue;}
                if(!Set.of("--originals-root","--max-items","--max-entries","--max-bytes").contains(flag)||++i>=args.length)throw new IllegalArgumentException();
                values.put(flag,args[i]);
            }
            options=new UploadResidueOptions(Path.of(Objects.requireNonNull(values.get("--originals-root"))),stopped,
                    Integer.parseInt(values.getOrDefault("--max-items","1000")),Integer.parseInt(values.getOrDefault("--max-entries","10000")),
                    Long.parseLong(values.getOrDefault("--max-bytes","1073741824")));
        }catch(RuntimeException invalid){error.println("INVALID_OPTIONS: use --maintenance --inspect-upload-residue --help");return 2;}
        try{
            var report=new UploadResidueService(new UploadResidueRepository(),Clock.systemUTC()).run(options);
            out.println(JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(report));
            return report.limitReached()||!report.items().isEmpty()?3:0;
        }catch(UploadResidueRepository.StorageInUseException busy){error.println("STORAGE_IN_USE: stop ImageGate and external writers before inspection");return 4;}
        catch(Exception unavailable){error.println("RESIDUE_INSPECTION_UNAVAILABLE: check explicit originals root, permissions, stable storage and stopped workers");return 5;}
    }
}
