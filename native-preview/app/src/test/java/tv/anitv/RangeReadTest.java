package tv.anitv;

import static org.junit.Assert.*;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.*;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.*;
import okio.Buffer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,application=AniApp.class)
public class RangeReadTest {
    private byte[] media(int size){byte[] bytes=new byte[size];for(int i=0;i<size;i++)bytes[i]=(byte)(i%251);return bytes;}
    private RangeMediaSource source(MockWebServer server){return new RangeMediaSource(new OkHttpClient(),new PlaySource(server.url("/video.mp4").toString(),"","",Collections.emptyMap()));}
    private Dispatcher ranges(byte[] bytes,int cap){return new Dispatcher(){
        @Override public MockResponse dispatch(RecordedRequest request){
            String header=request.getHeader("Range");long start=Long.parseLong(header.substring(6,header.indexOf('-')));
            if(start>=bytes.length)return new MockResponse().setResponseCode(416).setHeader("Content-Range","bytes */"+bytes.length);
            int length=Math.min(cap,bytes.length-(int)start);
            return new MockResponse().setResponseCode(206).setHeader("Content-Range","bytes "+start+"-"+(start+length-1)+"/"+bytes.length).setBody(new Buffer().write(bytes,(int)start,length));
        }
    };}
    private void read(RangeMediaSource source,byte[] bytes,int position)throws Exception{
        byte[] out=new byte[32];int n=Math.min(out.length,bytes.length-position);
        assertEquals(n,source.readAt(position,out,0,n));for(int i=0;i<n;i++)assertEquals(bytes[position+i],out[i]);
    }
    @Test public void sequentialEightMiBUsesFourRequestsAndOneConnectionWithTwoMiBCache()throws Exception{
        byte[] bytes=media(RangeMediaSource.BLOCK*16);
        try(MockWebServer server=new MockWebServer()){
            server.setDispatcher(ranges(bytes,RangeMediaSource.BLOCK*4));
            try(RangeMediaSource input=source(server)){
                assertFalse(input.probeIsHls());
                for(int i=0;i<16;i++)read(input,bytes,i*RangeMediaSource.BLOCK+31);
                assertEquals(4,server.getRequestCount());assertEquals(bytes.length,input.getSize());
                for(int i=0;i<4;i++)assertEquals(i,server.takeRequest(2,TimeUnit.SECONDS).getSequenceNumber());
                Field field=RangeMediaSource.class.getDeclaredField("blocks");field.setAccessible(true);
                Map<?,byte[]> blocks=(Map<?,byte[]>)field.get(input);assertEquals(4,blocks.size());assertEquals(2*1024*1024,blocks.values().stream().mapToInt(b->b.length).sum());
                read(input,bytes,bytes.length-24);assertEquals(4,server.getRequestCount());
            }
        }
    }
    @Test public void http200StreamsBeyondFirstBlockAndBackSeekReopensOnlyAfterEviction()throws Exception{
        byte[] bytes=media(RangeMediaSource.BLOCK*6+137);
        try(MockWebServer server=new MockWebServer()){
            server.setDispatcher(new Dispatcher(){public MockResponse dispatch(RecordedRequest r){return new MockResponse().setBody(new Buffer().write(bytes));}});
            try(RangeMediaSource input=source(server)){
                for(int i=0;i<7;i++)read(input,bytes,i*RangeMediaSource.BLOCK+11);
                assertEquals(1,server.getRequestCount());assertEquals(bytes.length,input.getSize());
                read(input,bytes,RangeMediaSource.BLOCK*4+73);assertEquals(1,server.getRequestCount());
                read(input,bytes,29);assertEquals(2,server.getRequestCount());
                read(input,bytes,RangeMediaSource.BLOCK*2+19);assertEquals(2,server.getRequestCount());
            }
        }
    }
    @Test public void cappedRangesAndFinalShortBlockRemainByteAccurate()throws Exception{
        byte[] bytes=media(RangeMediaSource.BLOCK+137);
        try(MockWebServer server=new MockWebServer()){
            server.setDispatcher(ranges(bytes,RangeMediaSource.BLOCK/2));
            try(RangeMediaSource input=source(server)){
                read(input,bytes,0);assertEquals(2,server.getRequestCount());
                read(input,bytes,bytes.length-32);assertEquals(3,server.getRequestCount());
                assertEquals(-1,input.readAt(bytes.length,new byte[1],0,1));
            }
        }
    }
    @Test public void truncatedRangeIsAnErrorAndDoesNotBecomeCachedSuccess()throws Exception{
        try(MockWebServer server=new MockWebServer()){
            server.enqueue(new MockResponse().setResponseCode(206).setHeader("Content-Range","bytes 0-524287/1048576").setBody("short"));
            server.enqueue(new MockResponse().setResponseCode(206).setHeader("Content-Range","bytes 0-3/4").setBody("good"));
            try(RangeMediaSource input=source(server)){
                assertThrows(IOException.class,()->input.readAt(0,new byte[1],0,1));
                byte[] out=new byte[4];assertEquals(4,input.readAt(0,out,0,4));assertEquals("good",new String(out,java.nio.charset.StandardCharsets.UTF_8));
            }
        }
    }
    @Test public void closeCancelsBlockedNetworkWithoutWaitingOnReaderLock()throws Exception{
        ExecutorService reader=Executors.newSingleThreadExecutor();
        try(MockWebServer server=new MockWebServer()){
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));RangeMediaSource input=source(server);
            Future<?> result=reader.submit(()->assertThrows(IOException.class,()->input.readAt(0,new byte[1],0,1)));
            assertNotNull(server.takeRequest(2,TimeUnit.SECONDS));input.close();result.get(2,TimeUnit.SECONDS);
            assertThrows(IOException.class,()->input.readAt(0,new byte[1],0,1));
        }finally{reader.shutdownNow();}
    }
}
