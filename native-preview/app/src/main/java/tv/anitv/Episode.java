package tv.anitv;

public final class Episode {
    public final int number;
    public final String title;
    public final boolean named;
    public final String image;
    public final int duration;
    public final Boolean available;
    public Episode(int number, String title) {
        this(number, title, false);
    }
    public Episode(int number, String title, boolean named) {
        this(number, title, named, "", 0, null);
    }
    public Episode(int number, String title, boolean named, String image, int duration, Boolean available) {
        this.image = image == null ? "" : image;
        this.duration = Math.max(0, duration);
        this.available = available;
        this.named = named; this.number = number; this.title = title == null || title.isEmpty() ? "第 " + number + " 集" : title;
    }
    public String label() { return named ? title : "第 " + number + " 集  ·  " + title; }
}
