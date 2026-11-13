package tv.anitv;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class CatalogWindowTest {
    static CatalogCodec.Page page(int n){
        List<Show> shows=new ArrayList<>();for(int i=0;i<40;i++){long id=10000-n*40-i;shows.add(new Show(id,"番剧"+id,"",""));}
        return new CatalogCodec.Page(shows,shows.get(shows.size()-1).id);
    }
    @Test public void hundredsOfPagesKeepOnlyThreePayloadsAndCanReturnToFirst()throws Exception {
        PageAccumulator pages=new PageAccumulator();pages.windowed(true);
        for(int i=0;i<100;i++){pages.append(page(i),true);assertTrue(pages.snapshot().size()<=120);assertFalse(pages.ended());assertEquals(Math.max(0,i-2)*40,pages.offset());}
        assertEquals(page(97).shows.get(0),pages.snapshot().get(0));
        for(int i=96;i>=0;i--){long cursor=pages.previousCursor();assertEquals(i==0?0:page(i-1).next,cursor);pages.prepend(cursor,page(i));assertEquals(page(i).shows.get(0),pages.snapshot().get(0));assertEquals(i*40,pages.offset());assertTrue(pages.snapshot().size()<=120);}
        assertFalse(pages.hasPrevious());assertEquals(page(2).next,pages.cursor());
        pages.append(page(3),true);assertEquals(page(1).shows.get(0),pages.snapshot().get(0));
    }
    @Test public void changedEarlierPageDoesNotDropCurrentWindow()throws Exception {
        PageAccumulator pages=new PageAccumulator();pages.windowed(true);for(int i=0;i<4;i++)pages.append(page(i),true);
        List<Show> before=pages.snapshot();long cursor=pages.previousCursor();
        assertThrows(java.io.IOException.class,()->pages.prepend(cursor,page(30)));
        assertEquals(before,pages.snapshot());assertEquals(cursor,pages.previousCursor());
        pages.reset();assertFalse(pages.hasPrevious());assertEquals(0,pages.cursor());assertTrue(pages.snapshot().isEmpty());
    }
    @Test public void terminalPageAndRepeatedPayloadStopWithoutBreakingBackwardReload()throws Exception {
        PageAccumulator pages=new PageAccumulator();pages.windowed(true);for(int i=0;i<4;i++)pages.append(page(i),true);
        pages.append(page(3),true);assertTrue(pages.ended());assertEquals(120,pages.snapshot().size());
        pages.prepend(pages.previousCursor(),page(0));assertFalse(pages.ended());assertEquals(page(2).next,pages.cursor());
    }
    @Test public void aCursorCycleThroughEvictedPagesStillStops(){
        PageAccumulator pages=new PageAccumulator();pages.windowed(true);
        for(int i=0;i<6;i++)pages.append(page(i),true);
        pages.append(page(0),true);assertTrue(pages.ended());assertTrue(pages.snapshot().size()<=120);
    }
}
