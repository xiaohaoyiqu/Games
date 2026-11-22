package com.f1car.persistence;

import com.f1car.model.*;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.*;
import java.io.*;
import java.lang.reflect.ParameterizedType;
import java.math.BigDecimal;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.*;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

/** Legacy content keeps its shape. Appearance subtrees reuse strict, bounded livery validation. */
public final class ReplayCodec {
    public static final int MAX_BYTES=64000000;
    private static final Gson JSON=new GsonBuilder().setStrictness(Strictness.STRICT)
            .registerTypeAdapterFactory(new Designs()).registerTypeAdapterFactory(new Drivers())
            .registerTypeAdapterFactory(new ReplayLists())
            .registerTypeAdapter(new TypeToken<List<RaceReplay.RecoveryWait>>(){}.getType(),new RecoveryWaits().nullSafe()).create();
    private ReplayCodec() {}
    public static String encode(RaceReplay value) {return JSON.toJson(Objects.requireNonNull(value));}

    static ArchiveWriter.Stamp write(Path temporary,RaceReplay value,int limit) throws IOException {
        Objects.requireNonNull(value);
        try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS)) {
            var bytes=new ReplayOutput(Channels.newOutputStream(channel),limit);
            try(var writer=JSON.newJsonWriter(new BufferedWriter(new OutputStreamWriter(bytes,StandardCharsets.UTF_8)))) {
                JSON.getAdapter(RaceReplay.class).write(writer,value);
            }
            channel.force(true);
            return bytes.stamp();
        }
    }

    /** The caller owns the channel and forces it after the encoder is closed. */
    private static final class ReplayOutput extends OutputStream {
        private final OutputStream output;
        private final int limit;
        private final java.security.MessageDigest hash=ArchiveWriter.digest();
        private int bytes;

        ReplayOutput(OutputStream output,int limit) {
            this.output=output;
            this.limit=limit;
        }

        @Override public void write(int value) throws IOException {
            requireRoom(1);
            output.write(value);
            hash.update((byte)value);
            bytes++;
        }

        @Override public void write(byte[] buffer,int offset,int length) throws IOException {
            Objects.checkFromIndexSize(offset,length,buffer.length);
            requireRoom(length);
            output.write(buffer,offset,length);
            hash.update(buffer,offset,length);
            bytes+=length;
        }

        private void requireRoom(int length) throws IOException {
            if(length>limit-bytes) throw new IOException("Replay exceeds size bound");
        }

        ArchiveWriter.Stamp stamp() {
            return new ArchiveWriter.Stamp(bytes,HexFormat.of().formatHex(hash.digest()));
        }
    }

    public static RaceReplay decode(byte[] bytes) throws IOException {
        if(bytes.length>MAX_BYTES) throw new IOException("Replay too large");
        return decode(new ByteArrayInputStream(bytes));
    }

    static RaceReplay decode(InputStream input) throws IOException {
        var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try(var reader=new ReplayReader(new InputStreamReader(new ReplayInput(input),decoder))) {
            reader.setStrictness(Strictness.STRICT);
            var value=JSON.getAdapter(RaceReplay.class).read(reader);
            if(value==null) throw new IOException("Missing replay content");
            if(reader.peek()!=JsonToken.END_DOCUMENT) throw new IOException("Trailing replay content");
            return value;
        } catch(RuntimeException error) {
            throw new IOException("Invalid replay content",error);
        }
    }

    private static final class ReplayInput extends FilterInputStream {
        private int remaining=MAX_BYTES;
        ReplayInput(InputStream input) {super(input);}

        @Override public int read() throws IOException {
            if(Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Replay read interrupted");
            if(remaining==0) return requireEnd();
            int value=in.read();
            if(value>=0) remaining--;
            return value;
        }

        @Override public int read(byte[] buffer,int offset,int length) throws IOException {
            Objects.checkFromIndexSize(offset,length,buffer.length);
            if(length==0) return 0;
            if(Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Replay read interrupted");
            if(remaining==0) return requireEnd();
            int count=in.read(buffer,offset,Math.min(length,remaining));
            if(count>0) remaining-=count;
            return count;
        }

        private int requireEnd() throws IOException {
            if(in.read()!=-1) throw new IOException("Replay too large");
            return -1;
        }
    }

    private static final class ReplayReader extends JsonReader {
        private static final int MAX_DEPTH=32;
        private static final int MAX_HEADER_FIELDS=64;
        private final Set<String> headerNames=new HashSet<>();
        private int depth;
        ReplayReader(Reader input) {super(input);}

        private void checkDepth() throws IOException {
            if(depth>=MAX_DEPTH) throw new IOException("Replay JSON nesting exceeds bound");
        }

        @Override public void beginArray() throws IOException {checkDepth();super.beginArray();depth++;}
        @Override public void beginObject() throws IOException {checkDepth();super.beginObject();depth++;}
        @Override public void endArray() throws IOException {super.endArray();depth--;}
        @Override public void endObject() throws IOException {super.endObject();depth--;}

        @Override public String nextName() throws IOException {
            String name=super.nextName();
            if(depth==1) {
                if(headerNames.size()>=MAX_HEADER_FIELDS) throw new IOException("Too many replay properties");
                if(!headerNames.add(name)) throw new IOException("Duplicate replay property");
            }
            return name;
        }

        @Override public void skipValue() throws IOException {
            switch(peek()) {
                case BEGIN_ARRAY -> {
                    beginArray();
                    while(hasNext()) skipValue();
                    endArray();
                }
                case BEGIN_OBJECT -> {
                    beginObject();
                    while(hasNext()) {
                        nextName();
                        skipValue();
                    }
                    endObject();
                }
                default -> super.skipValue();
            }
        }
    }

    private static final class ReplayLists implements TypeAdapterFactory {
        private record Limit(int count,String label) {}
        private static final Map<Class<?>,Limit> LIMITS=Map.of(
                RaceReplay.Frame.class,new Limit(RaceReplay.MAX_FRAMES,"frames"),
                RaceReplay.Event.class,new Limit(RaceReplay.MAX_EVENTS,"events"),
                RaceReplay.Driver.class,new Limit(RaceReplay.MAX_DRIVERS,"drivers"),
                RaceReplay.Car.class,new Limit(RaceReplay.MAX_DRIVERS,"cars in one frame"),
                RaceReplay.Anchor.class,new Limit(RaceReplay.MAX_ANCHORS,"track anchors"),
                CircuitScene.Item.class,new Limit(CircuitScene.MAX_ITEMS,"scene items"));

        @Override public <T> TypeAdapter<T> create(Gson gson,TypeToken<T> type) {
            if(type.getRawType()!=List.class || !(type.getType() instanceof ParameterizedType list)) return null;
            var element=list.getActualTypeArguments()[0];
            var limit=LIMITS.get(element);
            if(limit==null) return null;
            var item=gson.getAdapter(TypeToken.get(element));
            var delegate=gson.getDelegateAdapter(this,type);
            return new TypeAdapter<T>() {
                @Override @SuppressWarnings("unchecked") public T read(JsonReader reader) throws IOException {
                    var values=new ArrayList<>(Math.min(limit.count(),16));
                    reader.beginArray();
                    while(reader.hasNext()) {
                        if(values.size()>=limit.count()) throw new IOException("Too many replay "+limit.label());
                        values.add(item.read(reader));
                    }
                    reader.endArray();
                    return (T)values;
                }

                @Override public void write(JsonWriter writer,T value) throws IOException {delegate.write(writer,value);}
            }.nullSafe();
        }
    }
    private static final class RecoveryWaits extends TypeAdapter<List<RaceReplay.RecoveryWait>> {
        private static final int REQUIRED_WAIT_FIELDS=0b1111;

        @Override public List<RaceReplay.RecoveryWait> read(JsonReader reader) throws IOException {
            var waits=new ArrayList<RaceReplay.RecoveryWait>();
            int maps=0;
            reader.beginArray();
            while(reader.hasNext()) {
                if(waits.size()>=RaceReplay.MAX_RECOVERY_WAITS) throw new IOException("Too many replay recovery waits");
                int driver=-1,fields=0;
                double from=Double.NaN,through=Double.NaN;
                RaceReplay.RecoveryWaitEnd end=null;
                RaceReplay.RecoverySearchEvidence evidence=null;
                RaceReplay.RecoveryWaitLocations locations=null;
                reader.beginObject();
                while(reader.hasNext()) {
                    switch(reader.nextName()) {
                        case "driver" -> {fields=claim(fields,1);driver=number(reader).intValueExact();}
                        case "from" -> {fields=claim(fields,2);from=number(reader).doubleValue();}
                        case "through" -> {fields=claim(fields,4);through=number(reader).doubleValue();}
                        case "end" -> {
                            fields=claim(fields,8);
                            if(reader.peek()!=JsonToken.STRING) throw new IOException("Invalid replay recovery outcome");
                            end=RaceReplay.RecoveryWaitEnd.valueOf(reader.nextString());
                        }
                        case "evidence" -> {fields=claim(fields,16);evidence=readEvidence(reader);}
                        case "locations" -> {
                            fields=claim(fields,32);
                            if(++maps>RaceReplay.MAX_RECOVERY_WAIT_MAPS) throw new IOException("Too many replay recovery maps");
                            locations=readLocations(PartsJson.subtree(reader,"/recoveryWaits/"+waits.size()+"/locations",4));
                        }
                        default -> throw new IOException("Unknown replay recovery wait field");
                    }
                }
                reader.endObject();
                if((fields&REQUIRED_WAIT_FIELDS)!=REQUIRED_WAIT_FIELDS) throw new IOException("Missing replay recovery wait field");
                waits.add(new RaceReplay.RecoveryWait(driver,from,through,end,evidence,locations));
            }
            reader.endArray();
            return waits;
        }

        private static int claim(int fields,int flag) throws IOException {
            if((fields&flag)!=0) throw new IOException("Duplicate replay recovery field");
            return fields|flag;
        }

        private static BigDecimal number(JsonReader reader) throws IOException {
            if(reader.peek()!=JsonToken.NUMBER) throw new IOException("Expected a replay recovery number");
            String value=reader.nextString();
            if(value.length()>32) throw new IOException("Replay recovery number exceeds its length limit");
            return new BigDecimal(value);
        }

        private static double seconds(PartsJson.Node node) throws IOException {
            if(!node.value().isJsonPrimitive() || !node.value().getAsJsonPrimitive().isNumber())
                throw new IOException("Invalid replay recovery time");
            return node.value().getAsDouble();
        }
        private static RaceReplay.RecoverySearchEvidence readEvidence(JsonReader reader) throws IOException {
            int fields=0;
            long geometry=0,traffic=0,incomplete=0;
            reader.beginObject();
            while(reader.hasNext()) {
                switch(reader.nextName()) {
                    case "staticBlocked" -> {fields=claim(fields,1);geometry=number(reader).longValueExact();}
                    case "predictedTraffic" -> {fields=claim(fields,2);traffic=number(reader).longValueExact();}
                    case "incompleteTraffic" -> {fields=claim(fields,4);incomplete=number(reader).longValueExact();}
                    default -> throw new IOException("Unknown replay recovery search count");
                }
            }
            reader.endObject();
            if(fields!=7) throw new IOException("Missing replay recovery search count");
            return new RaceReplay.RecoverySearchEvidence(geometry,traffic,incomplete);
        }
        private static RaceReplay.RecoveryWaitLocations readLocations(PartsJson.Node node) throws IOException {
            var placement=node.child("placement");
            boolean hasPlacement=placement.value()!=null;
            if(hasPlacement) node.object("first","lastTime","last","placement");
            else node.object("first","lastTime","last");
            return new RaceReplay.RecoveryWaitLocations(readSites(node.child("first")),seconds(node.child("lastTime")),
                    readSites(node.child("last")),hasPlacement?readPlacement(placement):null);
        }
        private static RecoveryPlanner.Placement readPlacement(PartsJson.Node node) throws IOException {
            node.object("index","x","z");
            return new RecoveryPlanner.Placement(node.child("index").integer(0,RecoveryPlanner.MAX_SITES-1),
                    new RecoveryPlanner.Site((float)seconds(node.child("x")),(float)seconds(node.child("z"))));
        }
        private static RecoveryPlanner.FailedSites readSites(PartsJson.Node node) throws IOException {
            var drivers=node.child("blockingDrivers");
            boolean hasDrivers=drivers.value()!=null;
            if(hasDrivers) node.object("points","geometryMask","predictedMask","blockingDrivers");
            else node.object("points","geometryMask","predictedMask");
            var raw=node.child("points");
            int length=raw.array(1,RecoveryPlanner.MAX_SITES).size();
            var points=new ArrayList<RecoveryPlanner.Site>(length);
            for(int index=0;index<length;index++) {
                var point=raw.index(index).object("x","z");
                points.add(new RecoveryPlanner.Site((float)seconds(point.child("x")),(float)seconds(point.child("z"))));
            }
            var blockers=new ArrayList<Integer>(hasDrivers?length:0);
            if(hasDrivers) {
                drivers.array(length,length);
                for(int index=0;index<length;index++)
                    blockers.add(drivers.index(index).integer(RecoveryPlanner.UNKNOWN_DRIVER,Integer.MAX_VALUE));
            }
            int maxMask=(1<<length)-1;
            return new RecoveryPlanner.FailedSites(points,node.child("geometryMask").integer(0,maxMask),
                    node.child("predictedMask").integer(0,maxMask),blockers);
        }
        @Override public void write(JsonWriter writer,List<RaceReplay.RecoveryWait> waits) throws IOException {
            writer.beginArray();
            for(var wait:waits) {
                writer.beginObject();
                writer.name("driver").value(wait.driver());
                writer.name("from").value(wait.from());
                writer.name("through").value(wait.through());
                writer.name("end").value(wait.end().name());
                if(wait.evidence()!=null) {
                    var evidence=wait.evidence();
                    writer.name("evidence").beginObject();
                    writer.name("staticBlocked").value(evidence.staticBlocked());
                    writer.name("predictedTraffic").value(evidence.predictedTraffic());
                    writer.name("incompleteTraffic").value(evidence.incompleteTraffic());
                    writer.endObject();
                }
                if(wait.locations()!=null) {
                    var locations=wait.locations();
                    writer.name("locations").beginObject();
                    writer.name("first");
                    writeSites(writer,locations.first());
                    writer.name("lastTime").value(locations.lastTime());
                    writer.name("last");
                    writeSites(writer,locations.last());
                    if(locations.placement()!=null) {
                        var placement=locations.placement();
                        writer.name("placement").beginObject();
                        writer.name("index").value(placement.index());
                        writer.name("x").value(placement.site().x());
                        writer.name("z").value(placement.site().z());
                        writer.endObject();
                    }
                    writer.endObject();
                }
                writer.endObject();
            }
            writer.endArray();
        }
        private static void writeSites(JsonWriter writer,RecoveryPlanner.FailedSites sites) throws IOException {
            writer.beginObject();
            writer.name("points").beginArray();
            for(var point:sites.points()) {
                writer.beginObject();
                writer.name("x").value(point.x());
                writer.name("z").value(point.z());
                writer.endObject();
            }
            writer.endArray();
            writer.name("geometryMask").value(sites.geometryMask());
            writer.name("predictedMask").value(sites.predictedMask());
            if(!sites.blockingDrivers().isEmpty()) {
                writer.name("blockingDrivers").beginArray();
                for(int driver:sites.blockingDrivers()) writer.value(driver);
                writer.endArray();
            }
            writer.endObject();
        }
    }
    private static final class Drivers implements TypeAdapterFactory {
        @Override @SuppressWarnings("unchecked") public <T> TypeAdapter<T> create(Gson gson,TypeToken<T> type) {
            if(type.getRawType()!=RaceReplay.Driver.class) return null;
            var integers=gson.getAdapter(Integer.class);var strings=gson.getAdapter(String.class);var tuning=gson.getAdapter(Tuning.class);var look=gson.getAdapter(DriverJournal.Look.class);var appearance=gson.getAdapter(RaceReplay.Appearance.class);
            return (TypeAdapter<T>)new TypeAdapter<RaceReplay.Driver>() {
                @Override public RaceReplay.Driver read(JsonReader reader) throws IOException {if(reader.peek()==JsonToken.NULL) {reader.nextNull();return null;}reader.beginObject();Integer id=null;String name=null;Tuning setup=null;DriverJournal.Look emblem=null;RaceReplay.Appearance frozen=null;boolean seen=false;while(reader.hasNext()) {switch(reader.nextName()) {case "id"->id=integers.read(reader);case "name"->name=strings.read(reader);case "tuning"->setup=tuning.read(reader);case "look"->emblem=look.read(reader);case "appearance"->{if(seen) throw new IOException("Duplicate replay appearance");seen=true;if(reader.peek()==JsonToken.NULL) reader.nextNull();else {var node=PartsJson.subtree(reader,"/drivers/appearance",10);boolean regions=node.value().isJsonObject() && node.value().getAsJsonObject().has("regions");if(regions) node.object("vehicle","atlas","design","regions");else node.object("vehicle","atlas","design");var mask=regions && !node.child("regions").value().isJsonNull()?PaintRegionsCodec.decode(node.child("regions")):null;frozen=new RaceReplay.Appearance(node.child("vehicle").token(),node.child("atlas").text(96,false),LiveryCodec.content(node.child("design")),mask);}}default->reader.skipValue();}}reader.endObject();if(id==null) throw new IOException("Missing replay driver identity");return new RaceReplay.Driver(id,name,setup,emblem,frozen);}
                @Override public void write(JsonWriter writer,RaceReplay.Driver value) throws IOException {if(value==null) {writer.nullValue();return;}writer.beginObject();writer.name("id").value(value.id());writer.name("name").value(value.name());writer.name("tuning");tuning.write(writer,value.tuning());if(value.look()!=null) {writer.name("look");look.write(writer,value.look());}if(value.appearance()!=null) {writer.name("appearance");appearance.write(writer,value.appearance());}writer.endObject();}
            };
        }
    }
    private static final class Designs implements TypeAdapterFactory {
        @Override @SuppressWarnings("unchecked") public <T> TypeAdapter<T> create(Gson gson,TypeToken<T> type) {if(type.getRawType()!=CarLivery.class) return null;var tree=gson.getAdapter(JsonElement.class);return (TypeAdapter<T>)new TypeAdapter<CarLivery>() {@Override public CarLivery read(JsonReader reader) throws IOException {return LiveryCodec.content(PartsJson.subtree(reader,"/design",10));}@Override public void write(JsonWriter writer,CarLivery value) throws IOException {tree.write(writer,LiveryCodec.body(value));}};}
    }
}
