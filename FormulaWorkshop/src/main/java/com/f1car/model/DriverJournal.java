package com.f1car.model;

import java.util.*;

/** Bounded local history, best medals and cosmetic ownership, independent of car performance. */
public final class DriverJournal {
    public enum Activity { LAP,COURSE,CHALLENGE,CUP,RACE,EVENT_RACE,EVENT_COMPLETE,BOOK }
    public enum ReceiptGroup { GENERAL, EVENT, WORKSHOP }
    public enum Pattern {
        ORIGINAL("原版","始终可用"),STRIPE("中线","获得任意一枚奖牌"),CHEVRON("箭纹","两个项目获得银牌或以上"),LAUREL("纪念环","任意一个项目获得金牌");
        public final String title,requirement;Pattern(String title,String requirement) { this.title=title;this.requirement=requirement; }
    }
    public record Look(int number,Pattern pattern) {
        public Look { if(number<1 || number>99 || pattern==null) throw new IllegalArgumentException("Invalid cosmetic look"); }
        public static final Look DEFAULT=new Look(7,Pattern.ORIGINAL);
    }
    public record MedalRecord(ChallengeBook.Medal medal,double elapsed,int completions) {
        public MedalRecord { if(medal==null || medal==ChallengeBook.Medal.NONE || !Double.isFinite(elapsed) || elapsed<=0 || elapsed>600 || completions<1 || completions>1_000_000) throw new IllegalArgumentException("Invalid medal record"); }
    }
    public record TrackStats(String name,int laps,int courses,int challenges,int experiments,int raceFinishes,int raceWins) {
        public TrackStats {
            text(name,80);
            for(int count:new int[]{laps,courses,challenges,experiments,raceFinishes,raceWins}) if(count<0 || count>1_000_000) throw new IllegalArgumentException("Invalid career count");
            if(raceWins>raceFinishes) throw new IllegalArgumentException("Invalid win count");
        }
    }
    public record Notice(String receipt,long timestamp,Activity activity,String trackKey,String trackName,String outcome,
                         boolean success,double elapsed,String bookKey,ChallengeBook.Medal medal,int placing) {
        public Notice {
            text(receipt,180);text(trackKey,400);text(trackName,80);text(outcome,160);
            if(timestamp<0 || activity==null || !Double.isFinite(elapsed) || elapsed<0 || elapsed>86400 || medal==null || placing<0 || placing>4)
                throw new IllegalArgumentException("Invalid driving result");
            if(bookKey==null || bookKey.length()>500 || medal!=ChallengeBook.Medal.NONE && (bookKey.isBlank() || !success || elapsed<=0 || elapsed>600))
                throw new IllegalArgumentException("Invalid challenge award");
            if(success && (activity==Activity.RACE || activity==Activity.EVENT_RACE) && placing<1) throw new IllegalArgumentException("Missing race placing");
        }
        public ReceiptGroup receiptGroup() {
            if(activity==Activity.EVENT_RACE || activity==Activity.EVENT_COMPLETE) return ReceiptGroup.EVENT;
            if(activity==Activity.CUP && receipt.startsWith("workshop:")) return ReceiptGroup.WORKSHOP;
            return ReceiptGroup.GENERAL;
        }
    }
    public record Trophy(String receipt,String title,long timestamp) {
        public Trophy { text(receipt,180);text(title,160);if(timestamp<0) throw new IllegalArgumentException("Invalid trophy date"); }
    }
    public record State(int version,Map<String,MedalRecord> medals,Map<String,TrackStats> tracks,List<Notice> recent,
                        List<String> receipts,List<Trophy> trophies,Look look,List<String> eventReceipts,Set<Pattern> owned,List<String> workshopReceipts) {
        public State(int version,Map<String,MedalRecord> medals,Map<String,TrackStats> tracks,List<Notice> recent,List<String> receipts,List<Trophy> trophies,Look look,List<String> eventReceipts,Set<Pattern> owned) {
            this(version,medals,tracks,recent,receipts,trophies,look,eventReceipts,owned,List.of());
        }
        public State {
            if(version!=1 || medals==null || tracks==null || recent==null || receipts==null || trophies==null || look==null
                || eventReceipts==null || medals.size()>200 || tracks.size()>64 || recent.size()>32 || receipts.size()>2048 || trophies.size()>64 || eventReceipts.size()>512)
                throw new IllegalArgumentException("Invalid journal state");
            medals=immutableMap(medals,500);tracks=immutableMap(tracks,400);recent=List.copyOf(recent);receipts=List.copyOf(receipts);trophies=List.copyOf(trophies);
            receipts.forEach(r->text(r,180));if(new HashSet<>(receipts).size()!=receipts.size()) throw new IllegalArgumentException("Duplicate receipt");
            eventReceipts=List.copyOf(eventReceipts);eventReceipts.forEach(r->text(r,180));
            if(new HashSet<>(eventReceipts).size()!=eventReceipts.size()) throw new IllegalArgumentException("Duplicate event receipt");
            workshopReceipts=workshopReceipts==null?List.of():List.copyOf(workshopReceipts);
            if(workshopReceipts.size()>512 || new HashSet<>(workshopReceipts).size()!=workshopReceipts.size()) throw new IllegalArgumentException("Invalid workshop receipts");workshopReceipts.forEach(receipt->text(receipt,180));
            var earned=EnumSet.of(Pattern.ORIGINAL);if(owned!=null) earned.addAll(owned);
            for(var pattern:Pattern.values()) if(DriverJournal.unlocked(medals,pattern)) earned.add(pattern);
            owned=Collections.unmodifiableSet(earned);
            if(!owned.contains(look.pattern())) look=new Look(look.number(),Pattern.ORIGINAL);
        }
        public static State empty() { return new State(1,Map.of(),Map.of(),List.of(),List.of(),List.of(),Look.DEFAULT,List.of(),Set.of(Pattern.ORIGINAL)); }
        public boolean unlocked(Pattern pattern) { return owned.contains(pattern); }
        public boolean recorded(Notice notice) {
            var group=notice.receiptGroup();
            var history=switch(group) {
                case GENERAL -> receipts;
                case EVENT -> eventReceipts;
                case WORKSHOP -> workshopReceipts;
            };
            if(history.contains(notice.receipt())) return true;
            // A trophy or a feed entry can outlive its dedicated receipt list.
            for(var item:recent)
                if(item.receipt().equals(notice.receipt()) && item.receiptGroup()==group) return true;
            if(group==ReceiptGroup.EVENT)
                for(var trophy:trophies) if(trophy.receipt().equals(notice.receipt())) return true;
            return false;
        }
    }
    private DriverJournal() {}
    /** Select once against the starting history, before an earlier addition can evict a known receipt. */
    public static List<Notice> unrecorded(State state,List<Notice> notices) {
        if(notices.isEmpty()) return List.of();
        if(notices.size()==1) {
            var notice=notices.get(0);
            return state.recorded(notice)?List.of():List.of(notice);
        }
        var known=new EnumMap<ReceiptGroup,Set<String>>(ReceiptGroup.class);
        known.put(ReceiptGroup.GENERAL,new HashSet<>(state.receipts()));
        known.put(ReceiptGroup.EVENT,new HashSet<>(state.eventReceipts()));
        known.put(ReceiptGroup.WORKSHOP,new HashSet<>(state.workshopReceipts()));
        for(var item:state.recent()) known.get(item.receiptGroup()).add(item.receipt());
        for(var trophy:state.trophies()) known.get(ReceiptGroup.EVENT).add(trophy.receipt());
        var additions=new ArrayList<Notice>();
        for(var notice:notices) {
            if(known.get(notice.receiptGroup()).add(notice.receipt())) additions.add(notice);
        }
        return additions;
    }

    public static State record(State state,Notice notice) {
        if(state.recorded(notice)) return state;
        var group=notice.receiptGroup();
        var receipts=new ArrayList<>(state.receipts());var eventReceipts=new ArrayList<>(state.eventReceipts());var workshopReceipts=new ArrayList<>(state.workshopReceipts());
        var target=switch(group) {
            case GENERAL -> receipts;
            case EVENT -> eventReceipts;
            case WORKSHOP -> workshopReceipts;
        };
        target.add(notice.receipt());
        if(target.size()>(group==ReceiptGroup.GENERAL?2048:512)) target.remove(0);
        var medals=new LinkedHashMap<>(state.medals());
        if(notice.medal()!=ChallengeBook.Medal.NONE) {
            var before=medals.get(notice.bookKey());var best=before;
            if(before==null || notice.medal().ordinal()>before.medal().ordinal() || notice.medal()==before.medal() && notice.elapsed()<before.elapsed())
                best=new MedalRecord(notice.medal(),notice.elapsed(),1);
            medals.put(notice.bookKey(),new MedalRecord(best.medal(),best.elapsed(),before==null?1:increment(before.completions())));
            while(medals.size()>200) medals.remove(medals.keySet().iterator().next());
        }
        var tracks=new LinkedHashMap<>(state.tracks());
        var old=tracks.getOrDefault(notice.trackKey(),new TrackStats(notice.trackName(),0,0,0,0,0,0));
        if(notice.success()) {
            boolean race=notice.activity()==Activity.RACE || notice.activity()==Activity.EVENT_RACE;
            tracks.put(notice.trackKey(),new TrackStats(notice.trackName(),old.laps()+(notice.activity()==Activity.LAP && old.laps()<1_000_000?1:0),
                old.courses()+(notice.activity()==Activity.COURSE && old.courses()<1_000_000?1:0),old.challenges()+(notice.activity()==Activity.CHALLENGE && old.challenges()<1_000_000?1:0),
                old.experiments()+(notice.activity()==Activity.CUP && old.experiments()<1_000_000?1:0),old.raceFinishes()+(race && old.raceFinishes()<1_000_000?1:0),
                old.raceWins()+(race && notice.placing()==1 && old.raceWins()<1_000_000?1:0)));
            while(tracks.size()>64) tracks.remove(tracks.keySet().iterator().next());
        }
        var history=new ArrayList<>(state.recent());
        // Laps accumulate in track totals; the feed is reserved for completed activities.
        if(notice.activity()!=Activity.LAP) { history.add(notice);while(history.size()>32) history.remove(0); }
        var trophies=new ArrayList<>(state.trophies());
        if(notice.activity()==Activity.EVENT_COMPLETE && notice.success() && notice.placing()==1) {
            trophies.add(new Trophy(notice.receipt(),notice.outcome(),notice.timestamp()));while(trophies.size()>64) trophies.remove(0);
        }
        return new State(1,medals,tracks,history,receipts,trophies,state.look(),eventReceipts,state.owned(),workshopReceipts);
    }
    public static State look(State state,Look look) {
        if(!state.unlocked(look.pattern())) throw new IllegalArgumentException("Cosmetic not earned");
        return new State(1,state.medals(),state.tracks(),state.recent(),state.receipts(),state.trophies(),look,state.eventReceipts(),state.owned(),state.workshopReceipts());
    }
    private static boolean unlocked(Map<String,MedalRecord> medals,Pattern pattern) {
        return switch(pattern) {
            case ORIGINAL->true;
            case STRIPE->!medals.isEmpty();
            case CHEVRON->medals.values().stream().filter(m->m.medal().ordinal()>=ChallengeBook.Medal.SILVER.ordinal()).count()>=2;
            case LAUREL->medals.values().stream().anyMatch(m->m.medal()==ChallengeBook.Medal.GOLD);
        };
    }
    private static int increment(int value) { return Math.min(1_000_000,value+1); }
    private static void text(String text,int max) { if(text==null || text.isBlank() || text.length()>max || text.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid journal text"); }
    private static <T> Map<String,T> immutableMap(Map<String,T> source,int max) {
        source.forEach((key,value)->{text(key,max);Objects.requireNonNull(value);});return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
