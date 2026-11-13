package tv.anitv;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Retains at most three response pages in windowed mode; old cursors can reload earlier pages. */
public final class PageAccumulator {
    static final int WINDOW_PAGES = 3;
    private final LinkedHashMap<Long, Show> items = new LinkedHashMap<>();
    private final List<Frame> frames = new ArrayList<>();
    private final List<Long> previous = new ArrayList<>();
    private boolean windowed, ended;
    private long cursor,offset,hiddenAdultOffset;
    private static final class Frame implements java.io.Serializable {
        private static final long serialVersionUID=1L;
        final long request, next; final boolean ended; final List<Show> shows;
        Frame(long request,long next,boolean ended,List<Show> shows) {
            this.request=request;this.next=next;this.ended=ended;this.shows=new ArrayList<>(shows);
        }
    }
    static final class State implements java.io.Serializable {
        private static final long serialVersionUID=1L;
        final List<Frame> frames;final List<Long> previous;final List<Show> values;
        final boolean windowed,ended;final long cursor,offset,hiddenAdultOffset;
        State(PageAccumulator pages){frames=new ArrayList<>(pages.frames);previous=new ArrayList<>(pages.previous);values=pages.snapshot();windowed=pages.windowed;ended=pages.ended;cursor=pages.cursor;offset=pages.offset;hiddenAdultOffset=pages.hiddenAdultOffset;}
    }
    State save(){return new State(this);}
    void restore(State state){reset();windowed=state.windowed;ended=state.ended;cursor=state.cursor;offset=state.offset;hiddenAdultOffset=state.hiddenAdultOffset;frames.addAll(state.frames);previous.addAll(state.previous);for(Show show:state.values)items.put(show.id,show);}
    public void windowed(boolean enabled) { windowed=enabled; }
    public void reset() { items.clear();frames.clear();previous.clear();cursor=0;offset=0;hiddenAdultOffset=0;ended=false; }
    public void append(CatalogCodec.Page page, boolean paginated) {
        long request=cursor;
        int oldSize=items.size();
        for(Show show:page.shows)items.put(show.id,show);
        long next=page.next>0?page.next:page.shows.isEmpty()?cursor:page.shows.get(page.shows.size()-1).id;
        boolean repeated=next==cursor;
        for(Frame frame:frames)if(frame.request==next)repeated=true;
        if(windowed && previous.contains(next))repeated=true;
        ended=page.next<0 || !paginated || page.shows.isEmpty() || items.size()==oldSize || repeated;
        cursor=next;
        if(windowed) {
            // Empty/repeated terminal responses carry no additional page payload.
            if(items.size()>oldSize)frames.add(new Frame(request,next,ended,page.shows));
            List<Show> merged=new ArrayList<>(items.values());
            if(frames.size()>WINDOW_PAGES)previous.add(frames.remove(0).request);
            rebuild();
            if(!items.isEmpty()){
                long first=items.keySet().iterator().next();int removed=0;
                while(removed<merged.size() && merged.get(removed).id!=first){if(merged.get(removed).r18)hiddenAdultOffset++;removed++;}
                offset+=removed;
            }
        }
    }
    public boolean hasPrevious() { return windowed && !previous.isEmpty(); }
    public long previousCursor() { return hasPrevious()?previous.get(previous.size()-1):-1; }
    public void prepend(long request,CatalogCodec.Page page) throws java.io.IOException {
        if(!hasPrevious() || request!=previousCursor())throw new java.io.IOException("前一段目录已失效，请刷新索引");
        if(page.shows.isEmpty())throw new java.io.IOException("前一段目录为空，请稍后重试");
        // A changed server page must not silently introduce a gap into the retained window.
        long next=page.shows.get(page.shows.size()-1).id;
        if(frames.isEmpty() || next!=frames.get(0).request)throw new java.io.IOException("目录已更新，请在侧栏确认刷新");
        long oldFirst=items.isEmpty()?-1:items.keySet().iterator().next();
        LinkedHashMap<Long,Show> joined=new LinkedHashMap<>();for(Show show:page.shows)joined.put(show.id,show);joined.putAll(items);
        int added=0,adultAdded=0;for(Show show:joined.values()){if(show.id==oldFirst)break;added++;if(show.r18)adultAdded++;}
        offset=Math.max(0,offset-added);hiddenAdultOffset=Math.max(0,hiddenAdultOffset-adultAdded);
        previous.remove(previous.size()-1);
        frames.add(0,new Frame(request,next,false,page.shows));
        if(frames.size()>WINDOW_PAGES)frames.remove(frames.size()-1);
        Frame last=frames.get(frames.size()-1);cursor=last.next;ended=last.ended;
        rebuild();
    }
    private void rebuild() {
        items.clear();for(Frame frame:frames)for(Show show:frame.shows)items.put(show.id,show);
    }
    public long offset(){return offset;}
    public long offset(boolean showR18){return showR18?offset:Math.max(0,offset-hiddenAdultOffset);}
    public long cursor() { return cursor; }
    public boolean ended() { return ended; }
    public List<Show> snapshot() { return new ArrayList<>(items.values()); }
}
