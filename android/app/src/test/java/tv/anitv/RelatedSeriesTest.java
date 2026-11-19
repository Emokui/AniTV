package tv.anitv;
import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;
public class RelatedSeriesTest {
    private Show show(int id,String title){return new Show(id,title,"","");}
    @Test public void suppliedJujutsuExampleRejectsOtherFranchises(){
        List<Show> rows=Arrays.asList(show(1,"咒术回战"),show(2,"咒术回战 第二季"),show(3,"剧场版 咒术回战 0"),
                show(4,"被流放的开挂魔术师讴歌随心所欲第二人生"),show(5,"命运石之门0特别篇"),show(6,"命运石之门0"),show(7,"命运石之门 聪明睿智的认知计算"));
        List<Show> result=RelatedSeries.filter(rows,"咒术回战 涩谷事变×死灭回游剧场版");
        assertEquals(Arrays.asList(rows.get(0),rows.get(1),rows.get(2)),result);
    }
    @Test public void steinsGateKeepsItsOwnSpecialsAndSequels(){
        List<Show> rows=Arrays.asList(show(1,"命运石之门0特别篇"),show(2,"命运石之门0"),show(3,"命运石之门 聪明睿智的认知计算"),show(4,"咒术回战"));
        assertEquals(rows.subList(0,3),RelatedSeries.filter(rows,"命运石之门"));
    }
    @Test public void aliasesSupportAnotherLanguageAndKeepEnglishWordBoundaries(){
        List<Show> rows=Arrays.asList(show(1,"呪術廻戦 第2期"),show(2,"Jujutsu Kaisen Season 2"),show(3,"Jujutsu Other"));
        assertEquals(rows.subList(0,2),RelatedSeries.filter(rows,"咒术回战","呪術廻戦","Jujutsu Kaisen"));
        assertEquals("attackontitan",RelatedSeries.stem("Attack on Titan Season 2"));
        assertEquals("86",RelatedSeries.stem("86"));
        assertEquals("86",RelatedSeries.stem("86 第二季"));
    }
    @Test public void genericWordsAndSharedFragmentsDoNotEstablishSeries(){
        assertTrue(RelatedSeries.filter(Arrays.asList(show(1,"被流放的勇者"),show(2,"异世界魔王"),show(3,"第二季"),show(4,"剧场版")),"被流放的开挂魔术师讴歌随心所欲第二人生").isEmpty());
        assertTrue(RelatedSeries.filter(Arrays.asList(show(1,"Re:CREATORS")),"Re:从零开始的异世界生活").isEmpty());
        assertEquals(1,RelatedSeries.filter(Arrays.asList(show(1,"死神 第二季"),show(2,"死神少爷与黑女仆")),"死神").size());
    }
}
