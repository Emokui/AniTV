package tv.anitv;

import android.content.Context;
import android.content.SharedPreferences;

final class PlaybackHistory {
    static final int MAX_ENTRIES=100;
    private static final Object LOCK=new Object();
    private final SharedPreferences prefs, catalog;
    private final Context context;
    private boolean migrating;
    PlaybackHistory(Context context) {
        this.context=context.getApplicationContext();
        prefs = context.getSharedPreferences("native-playback", Context.MODE_PRIVATE);
        catalog=context.getSharedPreferences("native-history-list",Context.MODE_PRIVATE);
        if (!prefs.getBoolean("scoped-progress", false)) migrateProgress();
    }
    static String progressKey(String endpoint, long id) {
        String address = Library.endpointKey(endpoint);
        return Library.DEFAULT_ENDPOINT.equals(address) ? Long.toString(id) : address + "#" + id;
    }

    private void migrateProgress() {
        synchronized (LOCK) {
            if (prefs.getBoolean("scoped-progress", false)) return;
            String fallback = new Library(context).endpoint();
            java.util.Map<Long,String> owners = new java.util.HashMap<>();
            org.json.JSONArray history = rows();
            for (int i = 0; i < history.length(); i++) {
                org.json.JSONObject row = history.optJSONObject(i);
                if (row != null) owners.putIfAbsent(row.optLong("id"), row.optString("endpoint", fallback));
            }
            SharedPreferences.Editor edit = prefs.edit();
            for (java.util.Map.Entry<String,?> entry : prefs.getAll().entrySet()) {
                String old = entry.getKey();
                int split = old.indexOf(':');
                if (split <= 0 || !Character.isDigit(old.charAt(0))) continue;
                long id;
                try { id = Long.parseLong(old.substring(0, split)); }
                catch (NumberFormatException ignored) { continue; }
                String dest = progressKey(owners.getOrDefault(id, fallback), id) + old.substring(split);
                if (dest.equals(old)) continue;
                Object value = entry.getValue();
                if (value instanceof Long) edit.putLong(dest, (Long)value);
                else if (value instanceof Integer) edit.putInt(dest, (Integer)value);
                else if (value instanceof String) edit.putString(dest, (String)value);
                else continue;
                edit.remove(old);
            }
            edit.putBoolean("scoped-progress", true).apply();
        }
    }
    static final class Entry {
        final Show show; final String endpoint; final String[] titles; final boolean hydrated;
        Entry(Show show,String endpoint,String[] titles){this(show,endpoint,titles,false);}
        Entry(Show show,String endpoint,String[] titles,boolean hydrated){this.show=show;this.endpoint=endpoint;this.titles=titles;this.hydrated=hydrated;}
    }
    private org.json.JSONArray rows() {
        try{return new org.json.JSONArray(catalog.getString("entries","[]"));}
        catch(org.json.JSONException e){return new org.json.JSONArray();}
    }
    static boolean missingTitle(Show show){return show.title.trim().isEmpty() || show.title.equals("历史番剧 #"+show.id);}
    static boolean isProgressSubtitle(String subtitle) {
        if (subtitle == null || subtitle.isEmpty()) return false;
        String trimmed = subtitle.trim();
        int split = trimmed.lastIndexOf(" · ");
        if (split <= 0 || trimmed.substring(0, split).trim().isEmpty()) return false;
        String time = trimmed.substring(split + 3);
        int colon = time.indexOf(':');
        if (colon <= 0 || colon != time.length() - 3) return false;
        if (time.charAt(colon + 1) < '0' || time.charAt(colon + 1) > '5') return false;
        for (int i = 0; i < time.length(); i++) {
            if (i == colon) continue;
            char c = time.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }
    static String cleanSubtitle(String subtitle) {
        return subtitle == null || isProgressSubtitle(subtitle) ? "" : subtitle;
    }
    static boolean incomplete(Entry entry){return missingTitle(entry.show) || (entry.show.image.trim().isEmpty() && !entry.hydrated);}
    long revision(){return catalog.getLong("generation",0);}
    private static String[] names(long id,String title,String[] incoming,String[] previous){
        java.util.LinkedHashSet<String> values=new java.util.LinkedHashSet<>();
        for(String[] group:new String[][]{new String[]{title},incoming,previous})for(String name:group)
            if(name!=null && !name.trim().isEmpty() && !name.equals("历史番剧 #"+id) && values.size()<64)values.add(name);
        return values.toArray(new String[0]);
    }
    void remember(Show show,String endpoint,String[] titles) { synchronized(LOCK){
        if(show.id<=0)return;
        migrate(endpoint);
        Entry existing=find(show.id,endpoint);
        boolean hydrated=existing!=null && existing.hydrated;
        String nextSubtitle=cleanSubtitle(show.subtitle);
        if(existing!=null){
            show=new Show(show.id,missingTitle(show)?existing.show.title:show.title,
                    show.image.trim().isEmpty()?existing.show.image:show.image.trim(),nextSubtitle.isEmpty()?existing.show.subtitle:nextSubtitle,show.r18 || existing.show.r18);
        } else {
            show=new Show(show.id,show.title,show.image.trim(),nextSubtitle,show.r18);
        }
        hydrated &= !missingTitle(show);
        titles=names(show.id,show.title,titles,existing==null?new String[0]:existing.titles);
        org.json.JSONArray next=new org.json.JSONArray();
        try {
            org.json.JSONArray names=new org.json.JSONArray();for(String title:titles)names.put(title);
            org.json.JSONObject item=new org.json.JSONObject().put("id",show.id).put("title",show.title).put("image",show.image)
                    .put("subtitle",show.subtitle).put("r18",show.r18).put("endpoint",Library.endpointKey(endpoint)).put("titles",names);
            if(hydrated)item.put("hydrated",true);
            next.put(item);
            org.json.JSONArray old=rows();
            for(int i=0;i<old.length() && next.length()<MAX_ENTRIES;i++) {
                org.json.JSONObject row=old.getJSONObject(i);
                if (row.optLong("id") != show.id || !sameEndpoint(endpoint, row)) {
                    if (isProgressSubtitle(row.optString("subtitle"))) row.put("subtitle", "");
                    next.put(row);
                }
            }
            catalog.edit().putString("entries",next.toString()).apply();
            if (!migrating) {
                pruneProgress(next);
            }
        }catch(org.json.JSONException e){throw new IllegalStateException(e);}
    }}
    boolean enrich(Entry metadata,long expectedRevision){synchronized(LOCK){
        if(revision()!=expectedRevision)return false;
        org.json.JSONArray values=rows();
        for(int i=0;i<values.length();i++)try{
            org.json.JSONObject row=values.getJSONObject(i);
            if(row.optLong("id")!=metadata.show.id || !sameEndpoint(metadata.endpoint,row))continue;
            String title=missingTitle(metadata.show)?row.optString("title"):metadata.show.title;
            String image=metadata.show.image.trim().isEmpty()?row.optString("image").trim():metadata.show.image.trim();
            String[] oldTitles=new String[0];org.json.JSONArray old=row.optJSONArray("titles");
            if(old!=null){oldTitles=new String[old.length()];for(int j=0;j<old.length();j++)oldTitles[j]=old.optString(j);}
            org.json.JSONArray names=new org.json.JSONArray();for(String name:names(metadata.show.id,title,metadata.titles,oldTitles))names.put(name);
            String nextSubtitle=cleanSubtitle(metadata.show.subtitle);
            String subtitle=nextSubtitle.isEmpty()?cleanSubtitle(row.optString("subtitle")):nextSubtitle;
            boolean changed=!title.equals(row.optString("title")) || !image.equals(row.optString("image"))
                    || !subtitle.equals(row.optString("subtitle")) || !names.toString().equals(String.valueOf(old));
            boolean adult=metadata.show.r18 || row.optBoolean("r18");
            changed |= adult!=row.optBoolean("r18");
            boolean hydrated=(metadata.hydrated || row.optBoolean("hydrated")) && !missingTitle(new Show(metadata.show.id,title,image,""));
            row.put("title",title).put("image",image).put("subtitle",subtitle).put("titles",names).put("r18",adult);
            if(hydrated)row.put("hydrated",true);
            catalog.edit().putString("entries",values.toString()).apply();return changed;
        }catch(org.json.JSONException ignored){}
        return false;
    }}
    java.util.List<Show> shows(String endpoint) {
        java.util.List<Show> result=new java.util.ArrayList<>();
        for(Entry entry:entries(endpoint)) {
            String key=progressKey(endpoint,entry.show.id);int episode=lastEpisode(key,1);long seconds=position(key,episode)/1000;
            String label = episode >= Episode.SPECIAL_NUMBER_START
                    ? prefs.getString(key + ":last-title", "特别篇") : "第 " + episode + " 集";
            String progress=String.format(java.util.Locale.ROOT,"%s · %d:%02d",label,seconds/60,seconds%60);
            result.add(new Show(entry.show.id,entry.show.title,entry.show.image,progress,entry.show.r18));
        }
        return result;
    }
    java.util.List<Entry> entries(String endpoint) {
        migrate(endpoint);
        java.util.List<Entry> result=new java.util.ArrayList<>();org.json.JSONArray values=rows();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        boolean dirty = false;
        for(int i=0;i<values.length();i++)try{
            org.json.JSONObject row=values.getJSONObject(i);
            String rawSubtitle = row.optString("subtitle");
            String subtitle = cleanSubtitle(rawSubtitle);
            if (!subtitle.equals(rawSubtitle)) dirty = true;
            long id = row.getLong("id");
            if(!sameEndpoint(endpoint,row) || !seen.add(id))continue;
            org.json.JSONArray names=row.optJSONArray("titles");String[] titles=new String[names==null?1:names.length()];
            if(names==null)titles[0]=row.optString("title");else for(int j=0;j<titles.length;j++)titles[j]=names.optString(j);
            result.add(new Entry(new Show(id,row.getString("title"),row.optString("image"),subtitle,row.optBoolean("r18")),endpoint,titles,row.optBoolean("hydrated")));
        }catch(org.json.JSONException ignored){}
        if (dirty) {
            synchronized (LOCK) {
                org.json.JSONArray current = rows();
                boolean updated = false;
                for (int i = 0; i < current.length(); i++) {
                    org.json.JSONObject row = current.optJSONObject(i);
                    if (row != null && isProgressSubtitle(row.optString("subtitle"))) {
                        try { row.put("subtitle", ""); updated = true; }
                        catch (org.json.JSONException ignored) {}
                    }
                }
                if (updated) catalog.edit().putString("entries", current.toString()).apply();
            }
        }
        return result;
    }
    Entry find(long id,String endpoint){for(Entry entry:entries(endpoint))if(entry.show.id==id)return entry;return null;}
    private static final class LegacyItem {
        final String endpoint;
        final long id;
        LegacyItem(String endpoint, long id) { this.endpoint = endpoint; this.id = id; }
    }
    private void migrate(String endpoint) {
        synchronized (LOCK) {
            if (migrating || catalog.getBoolean("migrated", false)) return;
            String targetEndpoint = Library.endpointKey(endpoint);
            String currentEndpoint = Library.endpointKey(new Library(context).endpoint());
            java.util.Map<Long, Show> known = new java.util.HashMap<>();
            for (Show show : new Library(context).favorites()) known.put(show.id, show);
            java.util.List<LegacyItem> items = new java.util.ArrayList<>();
            for (String key : prefs.getAll().keySet()) if (key.endsWith(":last") && !key.startsWith("direct:")) {
                try {
                    String prefix = key.substring(0, key.length() - 5);
                    int hash = prefix.lastIndexOf('#');
                    String owner = hash > 0 ? Library.endpointKey(prefix.substring(0, hash)) : Library.DEFAULT_ENDPOINT;
                    long id = Long.parseLong(hash > 0 ? prefix.substring(hash + 1) : prefix);
                    if (id > 0 && progressKey(owner, id).equals(prefix) && !rawContains(id, owner)) {
                        items.add(new LegacyItem(owner, id));
                    }
                } catch (NumberFormatException ignored) {}
            }
            items.sort((a, b) -> {
                boolean aTarget = targetEndpoint.equals(a.endpoint), bTarget = targetEndpoint.equals(b.endpoint);
                if (aTarget != bTarget) return aTarget ? 1 : -1;
                int hostOrder = a.endpoint.compareTo(b.endpoint);
                return hostOrder != 0 ? hostOrder : Long.compare(a.id, b.id);
            });
            migrating = true;
            try {
                int budget = Math.max(0, MAX_ENTRIES - rows().length());
                int start = Math.max(0, items.size() - budget);
                for (int i = start; i < items.size(); i++) {
                    LegacyItem item = items.get(i);
                    Show show = currentEndpoint.equals(item.endpoint) ? known.get(item.id) : null;
                    if (show == null) show = new Show(item.id, "历史番剧 #" + item.id, "", "");
                    remember(show, item.endpoint, new String[]{show.title});
                }
                pruneProgress(rows());
                catalog.edit().putBoolean("migrated", true).apply();
            } finally {
                migrating = false;
            }
        }
    }
    private void pruneProgress(org.json.JSONArray kept){
        java.util.Set<String> ids=new java.util.HashSet<>();
        for(int i=0;i<kept.length();i++){org.json.JSONObject row=kept.optJSONObject(i);if(row!=null)ids.add(progressKey(row.optString("endpoint"),row.optLong("id")));}
        SharedPreferences.Editor edit=prefs.edit();
        for(String key:prefs.getAll().keySet()){
            int split=key.lastIndexOf(':');
            if(split>0 && !key.startsWith("direct:") && !ids.contains(key.substring(0,split))) edit.remove(key);
        }
        edit.apply();
    }
    private boolean rawContains(long id,String endpoint){org.json.JSONArray rows=rows();for(int i=0;i<rows.length();i++){
        org.json.JSONObject row=rows.optJSONObject(i);if(row!=null&&row.optLong("id")==id&&sameEndpoint(endpoint,row))return true;
    }return false;}
    private static boolean sameEndpoint(String endpoint, org.json.JSONObject row) {
        String stored = row.optString("endpoint");
        return endpoint.equals(stored) || Library.endpointKey(endpoint).equals(Library.endpointKey(stored));
    }
    boolean hasProgress(String show){return prefs.contains(show+":last");}
    boolean remove(long id,String endpoint){return removeAll(java.util.Collections.singleton(id),endpoint)>0;}
    int removeAll(java.util.Set<Long> ids,String endpoint){synchronized(LOCK){
        org.json.JSONArray kept=new org.json.JSONArray(),old=rows();int count=0;
        for(int i=0;i<old.length();i++){org.json.JSONObject row=old.optJSONObject(i);if(row==null)continue;
            if(ids.contains(row.optLong("id")) && sameEndpoint(endpoint,row)){count++;continue;}kept.put(row);
        }
        if(count>0){catalog.edit().putString("entries",kept.toString()).putLong("generation",revision()+1).apply();pruneProgress(kept);}return count;
    }}
    void clear(){synchronized(LOCK){long next=revision()+1;prefs.edit().clear().putBoolean("scoped-progress",true).apply();catalog.edit().clear().putBoolean("migrated",true).putLong("generation",next).apply();}}

    long position(String show, int episode) { return prefs.getLong(show + ":" + episode, 0); }
    int lastEpisode(String show, int fallback) { return prefs.getInt(show + ":last", fallback); }
    void save(String show, int episode, long position, long duration) {
        save(show, episode, position, duration, "");
    }
    void save(String show, int episode, long position, long duration, String title) {
        long stored = resumePosition(position, duration);
        SharedPreferences.Editor edit = prefs.edit().putLong(show + ":" + episode, stored).putInt(show + ":last", episode);
        if (episode >= Episode.SPECIAL_NUMBER_START && !title.isEmpty()) edit.putString(show + ":last-title", title);
        else if (episode != lastEpisode(show, -1)) edit.remove(show + ":last-title");
        edit.apply();
    }
    static long resumePosition(long position, long duration) {
        return duration > 0 && position >= duration - 2000 ? 0 : Math.max(0, position);
    }
    static String key(long id, String direct) {
        if (id > 0) return Long.toString(id);
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(direct.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder("direct:");
            for (byte b : digest) key.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return key.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
