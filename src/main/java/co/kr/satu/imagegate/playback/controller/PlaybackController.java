package co.kr.satu.imagegate.playback.controller;

import co.kr.satu.imagegate.playback.domain.PlaybackInfo;
import co.kr.satu.imagegate.playback.controller.dto.PlaybackResponse;
import co.kr.satu.imagegate.playback.service.PlaybackService;
import co.kr.satu.imagegate.playback.util.ByteRange;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/media/{mediaId}/playback")
public final class PlaybackController {
    private final PlaybackService service;
    private final co.kr.satu.imagegate.media.service.MediaService media;
    public PlaybackController(PlaybackService service,co.kr.satu.imagegate.media.service.MediaService media){this.service=service;this.media=media;}
    @GetMapping ResponseEntity<PlaybackResponse> get(@PathVariable String mediaId){return result(mediaId,service.status(mediaId),200);}
    @PostMapping ResponseEntity<PlaybackResponse> request(@PathVariable String mediaId,@RequestParam(defaultValue="false") boolean retry){
        var info=service.request(mediaId,retry);return result(mediaId,info,info.ready()?200:202);}
    private ResponseEntity<PlaybackResponse> result(String id,PlaybackInfo info,int status){
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(PlaybackResponse.from(id,media.require(id,co.kr.satu.imagegate.media.domain.MediaAction.VIEW).mediaVersion(),info));}
    @RequestMapping(value="/sources/{sourceId}/content",method={RequestMethod.GET,RequestMethod.HEAD})
    void content(@PathVariable String mediaId,@PathVariable String sourceId,
            @RequestHeader(value="Range",required=false) String range,@RequestHeader(value="If-Range",required=false) String ifRange,
            jakarta.servlet.http.HttpServletRequest request,HttpServletResponse response) throws IOException {
        service.content(mediaId,sourceId,content->{
            var info=content.info();String etag="\""+info.sourceId()+"\"";
            response.setHeader("Cache-Control","no-store");response.setHeader("Accept-Ranges","bytes");
            response.setHeader("ETag",etag);response.setContentType(info.mimeType());
            response.setHeader("Content-Disposition","inline; filename=\"playback."+("video/webm".equals(info.mimeType())?"webm":"mp3")+"\"");
            ByteRange selected;
            try{selected=ByteRange.parse(ifRange==null||ifRange.equals(etag)?range:null,info.sizeBytes());}
            catch(IllegalArgumentException invalid){response.setStatus(416);response.setHeader("Content-Range","bytes */"+info.sizeBytes());response.setContentLengthLong(0);return;}
            response.setStatus(selected.partial()?206:200);response.setContentLengthLong(selected.length());
            if(selected.partial())response.setHeader("Content-Range","bytes "+selected.start()+"-"+selected.end()+"/"+info.sizeBytes());
            if("HEAD".equals(request.getMethod()))return;
            try(var input=FileChannel.open(content.path(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
                input.position(selected.start());var block=ByteBuffer.allocate(32768);long remaining=selected.length();
                var output=response.getOutputStream();
                while(remaining>0){block.clear();block.limit((int)Math.min(block.capacity(),remaining));int n=input.read(block);
                    if(n<0)throw new IOException("Truncated playback artifact");if(n==0)continue;
                    output.write(block.array(),0,n);remaining-=n;}
            }
        });
    }
}
