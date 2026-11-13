package tv.anitv;

import java.util.Objects;

public final class Show implements java.io.Serializable {
    private static final long serialVersionUID=1L;
    // Keep metadata providers in disjoint namespaces for favorites and history.
    static final long BANGUMI_OFFSET = 1L << 40;
    public final long id;
    boolean fromBangumi() { return id >= BANGUMI_OFFSET; }
    long metadataId() { return fromBangumi() ? id - BANGUMI_OFFSET : id; }
    public final String title, image, subtitle;
    public final boolean r18;
    public Show(long id, String title, String image, String subtitle) {
        this(id,title,image,subtitle,false);
    }
    public Show(long id,String title,String image,String subtitle,boolean r18){
        this.id=id;this.title=title;this.image=image;this.subtitle=subtitle;
        this.r18=r18 || ContentVisibility.labeled(title) || ContentVisibility.labeled(subtitle);
    }
    @Override public boolean equals(Object other) {
        if (!(other instanceof Show)) return false;
        Show s = (Show) other;
        return id == s.id && r18 == s.r18 && title.equals(s.title) && image.equals(s.image) && subtitle.equals(s.subtitle);
    }
    @Override public int hashCode() { return Objects.hash(id, title, image, subtitle,r18); }
}
