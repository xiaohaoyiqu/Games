package com.f1car.model;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Bounded declarative parts: every permitted setting already has a FormulaCar implementation. */
public final class PartsCatalog {
    public static final int MAX_PACKS=8,MAX_PARTS_PER_PACK=16,MAX_LINKS=8;
    public enum Slot {
        POWER("动力","engine"),BRAKES("制动","brake"),SUSPENSION("悬挂","spring"),AERO("气动","wing");
        public final String title,symbol;
        Slot(String title,String symbol) {this.title=title;this.symbol=symbol;}
        public String stockId() {return "builtin:"+name().toLowerCase(Locale.ROOT)+"-stock";}
        public List<Field> fields() {return switch(this) {
            case POWER->List.of(Field.POWER);case BRAKES->List.of(Field.BRAKES,Field.BRAKE_BIAS);
            case SUSPENSION->List.of(Field.SUSPENSION,Field.FRONT_SPRING,Field.REAR_SPRING,Field.FRONT_DAMPING,Field.REAR_DAMPING);case AERO->List.of(Field.WING);
        };}
    }
    public enum Field {
        POWER("动力输出",82,112),BRAKES("制动力度",75,125),BRAKE_BIAS("前轴制动比例",35,65),
        SUSPENSION("悬挂总配套",75,130),FRONT_SPRING("前轴弹簧",80,120),REAR_SPRING("后轴弹簧",80,120),
        FRONT_DAMPING("前轴阻尼",80,120),REAR_DAMPING("后轴阻尼",80,120),WING("尾翼方案",65,140);
        public final String title;public final int min,max;
        Field(String title,int min,int max) {this.title=title;this.min=min;this.max=max;}
        public int step() {return this==BRAKE_BIAS?1:5;}
        public static Field tuningKey(String key) {return switch(key) {
            case "power"->POWER;case "brakes"->BRAKES;case "brakeBias"->BRAKE_BIAS;case "suspension"->SUSPENSION;case "wing"->WING;
            case "frontSpring"->FRONT_SPRING;case "rearSpring"->REAR_SPRING;case "frontDamping"->FRONT_DAMPING;case "rearDamping"->REAR_DAMPING;
            default->throw new IllegalArgumentException("Unknown tuning field");
        };}
        public Slot slot() {for(var slot:Slot.values()) if(slot.fields().contains(this)) return slot;throw new IllegalStateException("Unassigned field");}
        public int percent(Tuning t) {return Math.round(100*ratio(t));}
        public float ratio(Tuning t) {return switch(this) {
            case POWER->t.power();case BRAKES->t.brakes();case BRAKE_BIAS->t.brakeBias();case SUSPENSION->t.suspension();
            case FRONT_SPRING->t.frontSpring();case REAR_SPRING->t.rearSpring();case FRONT_DAMPING->t.frontDamping();case REAR_DAMPING->t.rearDamping();case WING->t.wing();
        };}
    }
    public record Range(int min,int max,int initial) {
        public Range {if(min>max || initial<min || initial>max) throw new IllegalArgumentException("Invalid part range");}
        public int clamp(long value) {return (int)Math.max(min,Math.min(max,value));}
    }
    public record Part(String id,Slot slot,String name,String purpose,String tradeoff,int points,int color,
                       Map<Field,Range> settings,Set<String> requires,Set<String> excludes) {
        public Part {
            if(!validId(id) || slot==null || !text(name,40) || name.isBlank() || !text(purpose,120) || !text(tradeoff,160)
                || points<0 || points>100 || color<0 || color>0xffffff || settings==null || requires==null || excludes==null
                || requires.size()>MAX_LINKS || excludes.size()>MAX_LINKS || requires.contains(id) || excludes.contains(id)
                || !Collections.disjoint(requires,excludes) || requires.stream().anyMatch(value->!validId(value)) || excludes.stream().anyMatch(value->!validId(value)))
                throw new IllegalArgumentException("Invalid part definition");
            var ranges=new EnumMap<Field,Range>(Field.class);ranges.putAll(settings);
            if(!ranges.keySet().equals(Set.copyOf(slot.fields()))) throw new IllegalArgumentException("Part must define exactly its slot's fields");
            ranges.forEach((field,range)->{if(range==null || range.min()<field.min || range.max()>field.max) throw new IllegalArgumentException("Unsupported model range");});
            settings=Collections.unmodifiableMap(ranges);requires=Set.copyOf(requires);excludes=Set.copyOf(excludes);
        }
        /** Labels and colors can be edited without silently changing a build's functional identity. */
        public String fingerprint() {
            var value=new StringBuilder(id).append('|').append(slot).append('|').append(points);
            settings.forEach((field,range)->value.append('|').append(field).append(':').append(range.min()).append(':').append(range.max()).append(':').append(range.initial()));
            requires.stream().sorted().forEach(id->value.append("|requires:").append(id));excludes.stream().sorted().forEach(id->value.append("|excludes:").append(id));
            return hash(value.toString());
        }
    }
    public record Pack(String id,int revision,String name,String author,List<Part> parts) {
        public Pack {
            if(id==null || !id.matches("[a-z][a-z0-9-]{0,31}") || revision<1 || revision>1_000_000 || !text(name,60) || name.isBlank() || !text(author,60)
                || parts==null || parts.isEmpty() || parts.size()>MAX_PARTS_PER_PACK) throw new IllegalArgumentException("Invalid part pack");
            parts=List.copyOf(parts);var ids=new HashSet<String>();
            for(var part:parts) if(!part.id().startsWith(id+":") || !ids.add(part.id())) throw new IllegalArgumentException("Part namespace or identity mismatch");
        }
    }
    private final Map<String,Part> byId;
    private final List<Pack> packs;
    private final Map<Slot,List<Part>> bySlot=new EnumMap<>(Slot.class);
    private final Map<String,String> fingerprints,search;
    public PartsCatalog(Pack builtin,List<Pack> custom) {
        if(builtin==null || !builtin.id().equals("builtin") || custom==null || custom.size()>MAX_PACKS) throw new IllegalArgumentException("Invalid catalogue");
        var all=new ArrayList<Pack>();all.add(builtin);all.addAll(custom);var ids=new HashSet<String>();var parts=new LinkedHashMap<String,Part>();
        for(var pack:all) {if(!ids.add(pack.id())) throw new IllegalArgumentException("Duplicate pack identity");for(var part:pack.parts()) if(parts.putIfAbsent(part.id(),part)!=null) throw new IllegalArgumentException("Duplicate part identity");}
        for(var slot:Slot.values()) {
            var stock=parts.get(slot.stockId());if(stock==null || stock.slot()!=slot || stock.points()!=0) throw new IllegalArgumentException("Missing stock part");
            for(var field:slot.fields()) {var range=stock.settings().get(field);if(range.initial()!=field.percent(Tuning.STOCK)) throw new IllegalArgumentException("Stock setting mismatch");}
            bySlot.put(slot,parts.values().stream().filter(part->part.slot()==slot).sorted(Comparator.comparingInt(Part::points).thenComparing(Part::id)).toList());
        }
        var hashes=new HashMap<String,String>();var texts=new HashMap<String,String>();
        parts.values().forEach(part->{hashes.put(part.id(),part.fingerprint());texts.put(part.id(),(part.name()+" "+part.purpose()+" "+part.id()).toLowerCase(Locale.ROOT));});
        byId=Map.copyOf(parts);packs=List.copyOf(all);fingerprints=Map.copyOf(hashes);search=Map.copyOf(texts);
    }
    public Part find(String id) {return byId.get(id);}
    public String fingerprint(String id) {return fingerprints.get(id);}
    public List<Pack> packs() {return packs;}
    public List<Part> parts(Slot slot) {return bySlot.get(slot);}
    public List<Part> search(Slot slot,String query) {String term=Objects.requireNonNull(query).strip().toLowerCase(Locale.ROOT);return term.isEmpty()?parts(slot):parts(slot).stream().filter(part->search.get(part.id()).contains(term)).toList();}
    public static boolean validId(String id) {return id!=null && id.matches("[a-z][a-z0-9-]{0,31}:[a-z][a-z0-9-]{0,47}");}
    public static boolean text(String value,int max) {return value!=null && value.length()<=max && value.codePoints().noneMatch(cp->Character.isISOControl(cp) || cp>=0xd800 && cp<=0xdfff);}
    public static String hash(String text) {try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible) {throw new AssertionError(impossible);}}
}
