package co.kr.satu.imagegate.media.controller;
import co.kr.satu.imagegate.media.controller.dto.TilePyramidResponse;
import co.kr.satu.imagegate.media.service.MediaTileService;
import co.kr.satu.imagegate.media.exception.MediaException;
import jakarta.servlet.http.*;
import java.io.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/media/{mediaId}/pages/{page}")
public final class MediaTileController {
    private final MediaTileService tiles;
    public MediaTileController(MediaTileService tiles){this.tiles=tiles;}
    @PostMapping("/pyramids")
    ResponseEntity<TilePyramidResponse> prepare(@PathVariable String mediaId,@PathVariable int page,@RequestParam(defaultValue="false") boolean retry){
        var view=tiles.view(mediaId,page,true,retry);String state=view.tiles().status().state();
        return ResponseEntity.status(state.equals("queued")||state.equals("running")?202:200).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options","nosniff").body(TilePyramidResponse.from(view));
    }
    @GetMapping("/pyramid")
    ResponseEntity<TilePyramidResponse> status(@PathVariable String mediaId,@PathVariable int page){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(TilePyramidResponse.from(tiles.view(mediaId,page,false,false)));
    }
    @RequestMapping(value="/pyramids/{tileSetId}/tiles/{level}/{column}/{row}",method={RequestMethod.GET,RequestMethod.HEAD})
    void content(@PathVariable String mediaId,@PathVariable int page,@PathVariable String tileSetId,@PathVariable int level,
            @PathVariable int column,@PathVariable int row,HttpServletRequest request,HttpServletResponse response)throws IOException{
        tiles.content(mediaId,page,tileSetId,level,column,row,(input,tile)->{
            if(request.getHeader("Range")!=null||request.getHeader("If-Range")!=null)throw new MediaException(MediaException.Code.INVALID_REQUEST);
            response.setStatus(200);response.setContentType(MediaType.IMAGE_PNG_VALUE);response.setHeader("Cache-Control","no-store");
            response.setHeader("X-Content-Type-Options","nosniff");response.setHeader("Content-Disposition","inline; filename=\"tile.png\"");response.setContentLengthLong(tile.bytes());
            if(request.getMethod().equals("HEAD"))return;
            byte[] buffer=new byte[32768];long remaining=tile.bytes();var output=response.getOutputStream();
            while(remaining>0){int n=input.read(buffer,0,(int)Math.min(buffer.length,remaining));if(n<0)throw new EOFException("Incomplete tile");output.write(buffer,0,n);remaining-=n;}
        });
    }
}
