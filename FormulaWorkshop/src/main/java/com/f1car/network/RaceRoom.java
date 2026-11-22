package com.f1car.network;

import com.f1car.model.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharsetDecoder;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;

/** Formula racing rooms. Socket workers queue packets; poll() owns all game state. */
public final class RaceRoom implements AutoCloseable {
    public static final int DEFAULT_PORT = 26951;
    public static final int PROTOCOL = 2;
    public static final String RULES = "formula-net-v2:" + VehicleSpec.load().physicsVersion;
    /** Smoothed application round trip and its variation. Variation is -1 until a second sample. */
    public record LinkTiming(long roundTripMs, long variationMs) {}
    /** Room socket bytes per second, including message headers and aggregated across peers. */
    public record TransferRate(long receivedBytesPerSecond, long sentBytesPerSecond) {}
    public enum FrameDelay { NONE, WAITING, BRAKE_ONLY, WATCHING }
    public record Member(int id,String name,Tuning tuning,boolean ready,int team,boolean acknowledged) {
        public Member(int id,String name,Tuning tuning,boolean ready) { this(id,name,tuning,ready,0,false); }
        public Member { if(id<0 || name==null || name.isBlank() || name.length()>80 || tuning==null || team<0 || team>1) throw new IllegalArgumentException("Invalid member"); }
    }
    public record Address(String host, int port) {
        public static Address parse(String text) {
            text = text.strip(); String host = text; int port = DEFAULT_PORT;
            if (text.startsWith("[")) {
                int end = text.indexOf(']'); if (end < 0) throw new IllegalArgumentException("Invalid address");
                host = text.substring(1, end);
                if (end + 1 < text.length()) { if (text.charAt(end + 1) != ':') throw new IllegalArgumentException("Invalid port"); port = Integer.parseInt(text.substring(end + 2)); }
            } else if (text.indexOf(':') >= 0 && text.indexOf(':') == text.lastIndexOf(':')) {
                int colon = text.indexOf(':'); host = text.substring(0, colon); port = Integer.parseInt(text.substring(colon + 1));
            }
            if (host.isBlank() || port < 1 || port > 65535) throw new IllegalArgumentException("Invalid address");
            return new Address(host, port);
        }
    }
    static final class Packet {
        int protocol = PROTOCOL, id;
        String game = "formula", rules = RULES, type, name, message, key;
        boolean value;
        long session, serial, timestamp, revision;
        Tuning tuning;
        RaceSession.Input input;
        RoomRules configuration;
        RoomProgram.State program;
        RaceSession.Frame frame;
        List<Member> members;
        Packet(String type) { this.type = type; }
    }
    private record Event(Peer peer, ReceivedPacket received, String error) {}
    private static final int EVENTS_PER_UPDATE = 64;
    private final boolean host;
    private final Gson gson = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private final ArrayBlockingQueue<Event> incoming = new ArrayBlockingQueue<>(512);
    private final Set<Peer> peers = ConcurrentHashMap.newKeySet();
    private final Map<Peer, Integer> identities = new LinkedHashMap<>();
    private final Map<Integer, Member> members = new LinkedHashMap<>();
    private ServerSocket listener;
    private volatile Peer server;
    private volatile boolean closed;
    private volatile String desiredName;
    private volatile Tuning desiredTuning;
    private RoomRules configuration;
    private RoomProgram program;
    private List<TrackLayout> tracks = List.of();
    private long revision = 1, acknowledgedRevision;
    private String configurationKey;
    private RaceSession simulation;
    private RaceSession.Frame latest, displayed;
    private final RoomFramePlayback playback=new RoomFramePlayback();
    private RaceSession.Frame lastBroadcastResult;
    private RaceSession.Input localInput = RaceSession.Input.STOP;
    private int localId = -1, nextId = 1, port;
    private long nextSession = 1, inputSerial;
    private long guestMotionSession = -1;
    private long lastHostBroadcast;
    private final RoomSendClock sendClock = new RoomSendClock();
    private String status = "正在连接方程式房间…";
    private String notice;
    private final RoomLatency latency = new RoomLatency();
    private final RoomTraffic traffic = new RoomTraffic();
    private List<String> addresses;

    private RaceRoom(boolean host, String name, Tuning tuning) { this.host = host; desiredName = cleanName(name); desiredTuning = Objects.requireNonNull(tuning); }
    public static RaceRoom host(String name, Tuning tuning, RaceSession.Settings settings, int port) throws IOException {
        return host(name,tuning,RoomRules.quick(RoomTrack.builtin(TrackCatalog.DEFAULT_ID),settings),port);
    }
    public static RaceRoom host(String name,Tuning tuning,RoomRules rules,int port) throws IOException {
        RaceRoom room = new RaceRoom(true, name, tuning);
        room.install(Objects.requireNonNull(rules));room.acknowledgedRevision=room.revision;room.localId=0;
        room.members.put(0,new Member(0,room.desiredName,tuning,true,0,true));
        room.listener = new ServerSocket();
        try { room.listener.setReuseAddress(true); room.listener.bind(new InetSocketAddress(port)); }
        catch (IOException | RuntimeException error) { room.listener.close(); throw error; }
        room.port = room.listener.getLocalPort(); room.status = "等待车手加入";
        daemon("formula-accept", room::accept);
        return room;
    }
    public static RaceRoom join(String name, Tuning tuning, Address address) {
        RaceRoom room = new RaceRoom(false, name, tuning); room.port = address.port;
        daemon("formula-connect", () -> {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(address.host, address.port), 5000);
                Peer peer = room.new Peer(socket);
                if (room.closed) { socket.close(); return; }
                room.server = peer; room.peers.add(peer);
                if (room.closed) { peer.close(); room.peers.remove(peer); return; }
                peer.start();
                Packet hello = new Packet("HELLO"); hello.name = room.desiredName; hello.tuning = room.desiredTuning; peer.send(hello);
            } catch (IOException | RuntimeException error) {
                try { socket.close(); } catch (IOException ignored) { }
                room.incoming.offer(new Event(null, null, "连接失败，请检查房主地址与端口。"));
            }
        });
        return room;
    }
    public boolean host() { return host; }
    public boolean closed() { return closed; }
    public int localId() { return localId; }
    public int port() { return port; }
    public String status() { return status; }
    public String takeNotice() { String value=notice;notice=null;return value; }
    /** Last raw reply for diagnostics. Use linkTiming() for a current connection reading. */
    public long latencyMs() { return latency.latestMs(); }
    private boolean guestLinkActive() {
        var peer = server;
        return localId >= 0 && peer != null && !peer.stopped.get();
    }
    public LinkTiming linkTiming() {
        return closed || !guestLinkActive() ? null : latency.timing(System.nanoTime());
    }
    public TransferRate transferRate() {
        return closed || (!host && !guestLinkActive()) ? null : traffic.rate(System.nanoTime());
    }
    public RoomRules rules() { return configuration; }
    public RoomProgram program() { return program; }
    public long revision() { return revision; }
    public boolean editable() { return !closed && simulation==null && latest==null && (program==null || program.complete()); }
    public RaceSession.Settings settings() {
        if(latest!=null) return latest.settings();
        if(program!=null && !program.complete()) return program.settings();
        return configuration==null?null:configuration.settings(configuration.initialPhase());
    }
    public TrackLayout track() {
        if(tracks.isEmpty()) return TrackCatalog.require(TrackCatalog.DEFAULT_ID).layout();
        if(latest!=null) return tracks.get(latest.trackIndex());
        return tracks.get(program!=null && !program.complete()?program.state().round():0);
    }
    public String stageName() {
        if(program!=null && (latest!=null || !program.complete())) return program.stageName();
        return configuration==null?"连接中":configuration.format().title;
    }
    public List<Member> members() { return List.copyOf(members.values()); }
    public boolean ready() { var m = members.get(localId); return m != null && m.ready; }
    public boolean canStart() {
        if(closed || !host || simulation!=null || members.size()<2 || members.values().stream().anyMatch(m->!m.ready() || !m.acknowledged())) return false;
        if(program!=null && !program.complete()) return members.keySet().stream().filter(id->program.state().drivers().stream().anyMatch(d->d.id()==id)).count()>=2;
        return !configuration.teams() || members.size()==4 && members.values().stream().filter(m->m.team()==0).count()==2;
    }
    public RaceSession simulation() { return simulation; }
    /** Uninterpolated host snapshot for passive recording; never a client-authored physics result. */
    public RaceSession.Frame rawFrame() { return latest; }
    public boolean waitingForRaceFrame() {
        return frameDelay()!=FrameDelay.NONE;
    }
    public FrameDelay frameDelay() {
        return host || closed ? FrameDelay.NONE : playback.delay(localId,System.nanoTime());
    }
    public void ready(boolean value) { ready(value, revision); }
    public boolean ready(boolean value, long reviewedRevision) {
        if (closed || host || server == null || !members.containsKey(localId) || latest != null
                || revision != reviewedRevision || acknowledgedRevision != revision) return false;
        Packet packet = new Packet("READY");
        packet.value = value;
        packet.revision = reviewedRevision;
        packet.key = configurationKey;
        server.send(packet);
        return true;
    }
    public void configure(String name, Tuning tuning) {
        if (!editable()) return;
        desiredName = cleanName(name); desiredTuning = Objects.requireNonNull(tuning);
        if (host) { var old=members.get(0);if(old.name().equals(desiredName) && old.tuning().equals(tuning)) return;members.put(0,new Member(0,desiredName,tuning,true,old.team(),true));changed(); }
        else if (server != null && localId >= 0) { Packet p = new Packet("CONFIG"); p.name = desiredName; p.tuning = tuning; server.send(p); }
    }
    public void settings(RaceSession.Settings settings) {
        if(configuration!=null) rules(RoomRules.quick(configuration.calendar().get(0),Objects.requireNonNull(settings)));
    }
    public void rules(RoomRules rules) {
        if(!host || !editable() || configuration.equals(rules)) return;
        install(Objects.requireNonNull(rules));program=null;changed();
    }
    public void team(int id,int team) {
        if(!host || !editable() || team<0 || team>1 || !members.containsKey(id)) return;
        var m=members.get(id);if(m.team()==team) return;members.put(id,new Member(id,m.name(),m.tuning(),id==0,team,id==0));changed();
    }
    public void resetProgram() {
        if(!host || simulation!=null || latest!=null) return;program=null;changed();
    }
    /** Allows a weekend to proceed directly to qualifying without a practice session. */
    public void skipPractice() {
        if(!host || simulation!=null || latest!=null || configuration.format()!=RoomRules.Format.WEEKEND && configuration.format()!=RoomRules.Format.TOUR) return;
        if(program==null || program.complete()) { if(!canStart()) return;program=RoomProgram.create(configuration,members.values().stream().map(m->new RoomProgram.Driver(m.id(),m.name(),m.tuning(),m.team())).toList()); }
        if(program.state().phase()!=RoomProgram.Phase.PRACTICE) return;
        program=program.finishPractice(program.state().lastSession());changed();
    }
    private void install(RoomRules rules) {
        var resolved=rules.calendar().stream().map(RoomTrack::resolve).toList();
        configuration=rules;configurationKey=rules.key();tracks=resolved;
    }
    private void changed() {
        revision++;acknowledgedRevision=revision;
        members.replaceAll((id,m)->new Member(id,m.name(),m.tuning(),id==0,m.team(),id==0));broadcastLobby();
    }
    public void startSession() {
        if (!canStart()) return;
        if(program==null || program.complete()) program=RoomProgram.create(configuration,members.values().stream().map(m->new RoomProgram.Driver(m.id(),m.name(),m.tuning(),m.team())).toList());
        var entries=program.state().drivers().stream().map(d->new RaceSession.Entry(d.id(),d.name(),d.tuning())).toList();
        int round=program.state().round();
        simulation=new RaceSession(nextSession++,program.settings(),entries,tracks.get(round),round,configuration.calendar().get(round).key(),program.grid());
        for(var driver:program.state().drivers()) if(!members.containsKey(driver.id())) simulation.disconnect(driver.id());
        latest = simulation.frame(); localInput = RaceSession.Input.STOP;
        for (Peer p : peers) p.lastInput = -1;
        sendClock.reset();
        broadcastFrame();
    }
    public void finishSession() {
        if (!host || simulation == null) return;
        if(latest.settings().activity()==RaceSession.Activity.PRACTICE) program=program.finishPractice(latest.session());
        else if(latest.complete() && program.state().lastSession()<latest.session()) program=program.accept(latest);
        else if(!latest.complete() && configuration.format()==RoomRules.Format.QUICK_RACE) program=null;
        simulation.close(); simulation=null;latest=lastBroadcastResult=null;localInput=RaceSession.Input.STOP;changed();
    }
    public void control(RaceSession.Input input) {
        localInput = Objects.requireNonNull(input);
        // A pause or focus loss may be the last callback before game-thread polls stop.
        if (latest != null && guestMotionSession == latest.session() && RaceSession.Input.STOP.equals(input)
                && guestLinkActive() && latest.acceptsInputFrom(localId)) {
            sendGuestInput();
            sendClock.reset();
        }
    }

    private void sendGuestInput() {
        Packet p = new Packet("INPUT");
        p.session = latest.session();
        p.serial = ++inputSerial;
        p.input = localInput;
        server.sendInput(p, playback.startedAt());
        guestMotionSession = RaceSession.Input.STOP.equals(localInput) ? -1 : p.session;
    }
    public void recover() {
        if (latest == null) return;
        localInput = RaceSession.Input.STOP;
        if(host) { if(!simulation.recover(0)) notice="恢复区有车或当前不能复位，请稍候。"; }
        else if (server != null) { Packet p = new Packet("RESET"); p.session = latest.session(); server.send(p); }
    }
    public void retire() {
        if (latest == null) return;
        if (host) simulation.retire(0);
        else if (server != null) { Packet p = new Packet("RETIRE"); p.session = latest.session(); server.send(p); }
    }
    public void poll(double dt) {
        poll(dt, null);
    }
    /** Delivers accepted guest snapshots or a new host observation on the game thread, before later room transitions. */
    public void poll(double dt, Consumer<RaceSession.Frame> observer) {
        if (closed) return;
        if (!Double.isFinite(dt) || dt < 0) dt = 0;
        dt = Math.min(.25, dt);
        if (!host && !playback.caughtUp()) displayed = null;
        var frameBeforePoll = latest;
        int remaining = Math.min(incoming.size(), EVENTS_PER_UPDATE);
        Event event;
        while (!closed && remaining-- > 0 && (event = incoming.poll()) != null) {
            if (host && event.peer != null && event.peer.stopped.get()) continue;
            var before = latest;
            try { receive(event); }
            catch (RuntimeException error) {
                if (host && event.peer != null) reject(event.peer, "方程式房间数据格式无效。");
                else { status = "房间数据读取失败。"; close(); }
            }
            if (observer != null && !host && !closed && latest != null && latest != before) observer.accept(latest);
        }
        if (closed) return;
        for (Peer peer : peers) {
            if (!peer.stopped.get() || !host && !incoming.isEmpty() || !peers.remove(peer)) continue;
            if (!host) { status = "房主连接已断开。"; close(); return; }
            Integer id = identities.remove(peer);
            if(id!=null) { members.remove(id);if(simulation!=null) simulation.disconnect(id);else changed(); }
        }
        if (closed) return;
        long now = System.nanoTime();
        boolean guestConnected = !host && guestLinkActive();
        traffic.sample(now, host ? !identities.isEmpty() : guestConnected);
        if (guestConnected) {
            if (latency.beginProbe(now)) {
                Packet p = new Packet("PING"); p.timestamp = now; server.send(p);
            }
        }
        if (host && simulation != null) {
            simulation.input(0, localInput); simulation.update(dt); latest = simulation.frame();
            boolean stageEnded = latest.complete() && program.state().lastSession() < latest.session();
            if (stageEnded) {
                program = program.accept(latest);
                sendClock.reset();
            }
            if (stageEnded || sendClock.advance(dt)) broadcastFrame();
            if (observer != null) observer.accept(latest);
        } else if (!host && latest != null && !latest.complete() && server != null) {
            boolean sessionStarted = frameBeforePoll == null || frameBeforePoll.session() != latest.session();
            if (sessionStarted) sendClock.reset();
            if ((sessionStarted || sendClock.advance(dt)) && latest.acceptsInputFrom(localId)) {
                sendGuestInput();
            }
        }
        if (host && !identities.isEmpty() && now - lastHostBroadcast >= 1_000_000_000L)
            broadcast(new Packet("KEEPALIVE"));
    }
    public RaceSession.Frame frame() {
        if (host || latest == null) return latest;
        if (displayed == null) displayed = playback.frame(System.nanoTime());
        return displayed;
    }

    private void receive(Event event) {
        if (event.error != null) { status = event.error; close(); return; }
        Packet p = event.received.packet();
        if (p == null || p.protocol != PROTOCOL || !"formula".equals(p.game) || !RULES.equals(p.rules) || p.type == null) {
            if (host) reject(event.peer, "游戏类型、版本或赛道规则不一致，请使用相同版本的方程式房间。");
            else { status = "方程式房间版本或规则不一致。"; close(); }
            return;
        }
        if (!host) {
            switch (p.type) {
                case "WELCOME" -> { localId = p.id; configure(desiredName, desiredTuning); }
                case "LOBBY" -> {
                    if(p.members==null || p.members.isEmpty() || p.members.size()>4 || p.configuration==null || p.revision<revision || p.members.stream().map(Member::id).distinct().count()!=p.members.size() || p.members.stream().noneMatch(m->m.id()==localId)) throw new IllegalArgumentException("Invalid lobby");
                    if(!Objects.equals(configurationKey,p.key)) { install(p.configuration);if(!configurationKey.equals(p.key)) throw new IllegalArgumentException("Configuration mismatch"); }
                    program=p.program==null?null:receivedProgram(p.program);
                    members.clear();for(var m:p.members) members.put(m.id(),m);
                    revision=p.revision;latest=displayed=null;playback.clear();localInput=RaceSession.Input.STOP;status="地图已校验，等待车手准备";
                    if(acknowledgedRevision!=revision) { acknowledgedRevision=revision;Packet ack=new Packet("ACK");ack.revision=revision;ack.key=configurationKey;server.send(ack); }
                }
                case "FRAME" -> {
                    validateFrame(p.frame);
                    if(configuration==null || p.revision!=revision || !Objects.equals(p.key,configurationKey) || p.frame.trackIndex()>=tracks.size() || !configuration.calendar().get(p.frame.trackIndex()).key().equals(p.frame.trackKey())) throw new IllegalArgumentException("Frame map mismatch");
                    var state=receivedProgram(p.program);
                    // Frame and program already require distinct driver IDs. Membership plus equal size covers the roster.
                    if(state.state().drivers().size()!=p.frame.cars().size()) throw new IllegalArgumentException("Frame roster mismatch");
                    if(p.frame.cars().stream().anyMatch(car->state.state().drivers().stream().noneMatch(driver->driver.id()==car.id() && driver.name().equals(car.name()) && driver.tuning().equals(car.tuning())))) throw new IllegalArgumentException("Frame vehicle rule mismatch");
                    if (latest != null && (p.frame.session() < latest.session() || p.frame.session() == latest.session() && p.frame.sequence() <= latest.sequence())) return;
                    state.validateStage(p.frame);
                    if (latest == null || latest.session() != p.frame.session()) localInput = RaceSession.Input.STOP;
                    latest=p.frame;playback.accept(latest,event.received.startedAt(),event.received.completedAt());
                    displayed=null;program=state;status=latest.complete()?"阶段完成":"同场驾驶中";
                }
                case "PONG" -> latency.reply(p.timestamp, event.received.completedAt());
                case "NOTICE" -> notice=p.message;
                case "ERROR" -> { status = p.message; close(); }
                default -> { }
            }
            return;
        }
        if (p.type.equals("HELLO")) {
            if (identities.containsKey(event.peer)) return;
            if (members.size() >= 4 || simulation != null || program!=null && !program.complete()) { reject(event.peer,"房间已满或系列进行中，请等房主结束系列后再加入。");return; }
            int id = nextId++; identities.put(event.peer,id);
            members.put(id,new Member(id,cleanName(p.name),p.tuning==null?Tuning.STOCK:p.tuning,false,members.size()%2,false));
            Packet welcome=new Packet("WELCOME");welcome.id=id;event.peer.send(welcome);changed();return;
        }
        Integer id = identities.get(event.peer);
        if (id == null) { reject(event.peer,"请先完成房间连接。"); return; }
        Member member = members.get(id);
        switch (p.type) {
            case "ACK" -> { if(simulation==null && !member.acknowledged() && p.revision==revision && configurationKey.equals(p.key)) { members.put(id,new Member(id,member.name(),member.tuning(),false,member.team(),true));broadcastLobby(); } }
            case "READY" -> { if(simulation==null && member.acknowledged() && member.ready()!=p.value && p.revision==revision && configurationKey.equals(p.key)) { members.put(id,new Member(id,member.name(),member.tuning(),p.value,member.team(),true));broadcastLobby(); } }
            case "CONFIG" -> {
                Tuning tuning=p.tuning==null?Tuning.STOCK:p.tuning;String name=cleanName(p.name);
                if(editable() && (!member.name().equals(name) || !member.tuning().equals(tuning))) { members.put(id,new Member(id,name,tuning,false,member.team(),false));changed(); }
            }
            case "INPUT" -> {
                if (simulation != null && latest.session() == p.session && p.serial > event.peer.lastInput) {
                    event.peer.lastInput = p.serial;
                    simulation.receiveRemoteInput(id, p.input == null ? RaceSession.Input.STOP : p.input,
                            event.received.startedAt(), System.nanoTime());
                }
            }
            case "RESET" -> { if(simulation!=null && latest.session()==p.session && !simulation.recover(id)) { Packet notice=new Packet("NOTICE");notice.message="恢复区有车或当前不能复位，请稍候。";event.peer.send(notice); } }
            case "RETIRE" -> { if (simulation != null && latest.session() == p.session) simulation.retire(id); }
            case "PING" -> { Packet reply = new Packet("PONG"); reply.timestamp = p.timestamp; event.peer.send(reply); }
            default -> { }
        }
    }
    /** Re-enumerate local interfaces on the next lobby draw after a network change. */
    public void refreshAddresses() { addresses = null; }

    public List<String> addresses() {
        if (addresses != null) return addresses;
        List<String> result = new ArrayList<>();
        try {
            var interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                var network = interfaces.nextElement();
                try {
                    if (!network.isUp() || network.isLoopback()) continue;
                } catch (SocketException unavailable) {
                    continue; // An adapter may disappear while switching networks.
                }
                var entries = network.getInetAddresses();
                while (entries.hasMoreElements()) { var a = entries.nextElement(); if (a instanceof Inet4Address && a.isSiteLocalAddress()) result.add(a.getHostAddress() + ":" + port); }
            }
        } catch (SocketException ignored) { }
        if (result.isEmpty()) result.add("127.0.0.1:" + port);
        result.sort(Comparator.comparingInt(s -> s.startsWith("192.168.") ? 0 : s.startsWith("10.") ? 1 : 2));
        addresses = List.copyOf(result); return addresses;
    }
    public static String cleanName(String name) {
        return DriverName.clean(name);
    }
    private void broadcastLobby() { Packet p=new Packet("LOBBY");p.configuration=configuration;p.key=configurationKey;p.revision=revision;p.program=program==null?null:program.state();p.members=members();broadcast(p); }
    private void broadcastFrame() {
        if (latest.complete()) {
            // Physics ticks continue on the results screen. A disconnect can still change the final roster.
            if (lastBroadcastResult != null && lastBroadcastResult.session() == latest.session()
                    && lastBroadcastResult.cars().equals(latest.cars())) return;
            lastBroadcastResult = latest;
        }
        Packet p=new Packet("FRAME");p.frame=latest;p.program=program.state();p.key=configurationKey;p.revision=revision;broadcast(p);
    }
    private void broadcast(Packet p) {
        lastHostBroadcast = System.nanoTime();
        for (var peer : identities.keySet()) peer.send(p);
    }
    private void reject(Peer peer, String message) { if (peer == null) return; Packet p = new Packet("ERROR"); p.message = message; peer.send(p); }
    private void accept() {
        while (!closed) {
            try {
                Socket socket = listener.accept();
                if (closed || peers.size() >= 8) { socket.close(); continue; }
                Peer peer;
                try { peer = new Peer(socket); }
                catch (IOException error) { try { socket.close(); } catch (IOException ignored) { } continue; }
                peers.add(peer);
                if (closed) { peer.close(); peers.remove(peer); return; }
                peer.start();
            } catch (IOException error) { if (!closed) incoming.offer(new Event(null,null,"方程式房间监听停止。")); return; }
        }
    }
    @Override public void close() {
        if (closed) return; closed = true;
        if (listener != null) try { listener.close(); } catch (IOException ignored) { }
        for (var peer : peers) peer.close(); peers.clear();
        if (simulation != null) { simulation.close(); simulation = null; }
    }
    private RoomProgram receivedProgram(RoomProgram.State received) {
        if (program != null && program.rules() == configuration && program.state().equals(received)) return program;
        return new RoomProgram(configuration, received);
    }

    private static void validateFrame(RaceSession.Frame frame) {
        if (frame == null || frame.settings() == null || frame.session() < 1 || frame.sequence() < 0 || !Double.isFinite(frame.elapsed()) || frame.elapsed() < 0
            || !Float.isFinite(frame.countdown()) || frame.cars().size()<2 || frame.cars().size()>4 || frame.trackIndex()<0 || frame.trackIndex()>2 || frame.trackKey()==null || !frame.trackKey().matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid frame");
        Set<Integer> ids = new HashSet<>();
        for (var car : frame.cars()) {
            if (car == null || car.id() < 0 || !ids.add(car.id()) || car.name() == null || car.tuning() == null || car.pose() == null || car.progress() == null) throw new IllegalArgumentException("Invalid car");
            if(!car.pose().usable()) throw new IllegalArgumentException("Invalid car motion");
            for(float v:new float[]{car.penalty(),car.throttle(),car.braking(),car.steering(),car.distance()})
                if(!Float.isFinite(v)) throw new IllegalArgumentException("Nonfinite state");
            var progress=car.progress();
            if(progress.nextGate()<0 || progress.nextGate()>17 || progress.completedLaps()<0 || progress.completedLaps()>10000 || car.recovery()<0 || car.penalty()<0 || frame.countdown()<0 || frame.countdown()>4.1f) throw new IllegalArgumentException("Invalid progress");
            for(double time:new double[]{progress.lapStart(),progress.lastLap(),progress.bestLap(),progress.finishTime()}) if(!Double.isFinite(time) || time< -1 || time>86400) throw new IllegalArgumentException("Invalid time");
            if(progress.finished() && (progress.finishTime()<=0 || progress.lastLap()<=0)) throw new IllegalArgumentException("Invalid finish");
        }
    }
    private static void daemon(String name, Runnable action) { Thread t = new Thread(action,name); t.setDaemon(true); t.start(); }
    record ReceivedPacket(Packet packet, long startedAt, long completedAt) {}
    // Stamp successful source reads beneath the buffer. Several packets can share a single read.
    private static final class TimedInput extends FilterInputStream {
        private final LongSupplier nanoTime;
        private final IntConsumer countBytes;
        private long receivedAt;

        TimedInput(InputStream source, LongSupplier nanoTime, IntConsumer countBytes) {
            super(source);
            this.nanoTime = nanoTime;
            this.countBytes = countBytes;
        }

        @Override public int read() throws IOException {
            int value = in.read();
            if (value >= 0) recordRead(1);
            return value;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = in.read(bytes, offset, length);
            if (count > 0) recordRead(count);
            return count;
        }
        private void recordRead(int bytes) {
            receivedAt = nanoTime.getAsLong();
            countBytes.accept(bytes);
        }
    }

    static final class PacketReader {
        private final TimedInput source;
        private final DataInputStream stream;
        private final int limit;
        private final Gson gson;
        private final CharsetDecoder utf8 = StandardCharsets.UTF_8.newDecoder();

        PacketReader(InputStream input, int limit, Gson gson, LongSupplier nanoTime) {
            this(input, limit, gson, nanoTime, ignored -> {});
        }

        PacketReader(InputStream input, int limit, Gson gson, LongSupplier nanoTime, IntConsumer countBytes) {
            source = new TimedInput(input, nanoTime, countBytes);
            stream = new DataInputStream(new BufferedInputStream(source));
            this.limit = limit;
            this.gson = gson;
        }

        ReceivedPacket read() throws IOException {
            int first = stream.readUnsignedByte();
            long startedAt = source.receivedAt;
            int length = (first << 24) | (stream.readUnsignedByte() << 16) | stream.readUnsignedShort();
            if (length < 1 || length > limit) throw new IOException("Packet size");
            byte[] bytes = new byte[length];
            stream.readFully(bytes);
            long completedAt = source.receivedAt;
            Packet packet = gson.fromJson(utf8.decode(ByteBuffer.wrap(bytes)).toString(), Packet.class);
            // Controls age from the first byte we read. Presentation and round trips need the complete packet.
            return new ReceivedPacket(packet, startedAt, completedAt);
        }
    }
    static final class Outbox {
        private static final int CAPACITY = 64;
        private record Entry(Packet packet, long enqueuedAt, long frameStartedAt) {}
        private final ArrayDeque<Entry> entries = new ArrayDeque<>();
        private boolean closed;
        private boolean tailIsLaterMovement;

        boolean offer(Packet packet, long now) {
            return offer(packet, now, now);
        }

        synchronized boolean offer(Packet packet, long now, long frameStartedAt) {
            if (closed) return false;
            Entry tail = entries.peekLast();
            boolean sameMovement = tail != null && continuesMovement(tail.packet, packet);
            if (tail != null && isInput(packet) && isInput(tail.packet) && packet.session == tail.packet.session) {
                // Continuous controls replace the previous sample, but never cross a command in the queue.
                if (packet.serial <= tail.packet.serial) return true;
                entries.removeLast();
            } else if (sameMovement && tailIsLaterMovement) {
                // Keep the first observation after an event and the newest unsent movement for this peer.
                entries.removeLast();
            } else if (entries.size() == CAPACITY) return false;
            entries.addLast(new Entry(packet, now, frameStartedAt));
            tailIsLaterMovement = sameMovement;
            notifyAll();
            return true;
        }

        synchronized Packet take(LongSupplier nanoTime) throws InterruptedException {
            while (!closed) {
                if (entries.isEmpty()) {
                    wait();
                    continue;
                }
                Entry entry = entries.removeFirst();
                if (!isInput(entry.packet)) return entry.packet;
                long now = nanoTime.getAsLong();
                if (now - entry.enqueuedAt >= RaceSession.INPUT_TIMEOUT_NANOS) continue;
                // The view can expire while the writer waits, even when the control itself is fresh.
                if (RoomSnapshotTiming.requiresBrake(entry.frameStartedAt,now)) entry.packet.input = RaceSession.Input.STOP;
                return entry.packet;
            }
            return null;
        }

        synchronized void close() {
            closed = true;
            entries.clear();
            notifyAll();
        }

        private static boolean isInput(Packet packet) { return "INPUT".equals(packet.type); }

        private static boolean continuesMovement(Packet before, Packet after) {
            if (!"FRAME".equals(after.type) || !"FRAME".equals(before.type) || before.program != after.program
                    || before.revision != after.revision || !Objects.equals(before.key, after.key)) return false;
            var first = before.frame;
            var next = after.frame;
            if (first == null || next == null || first.complete() || next.complete() || first.session() != next.session()
                    || next.sequence() <= first.sequence() || first.trackIndex() != next.trackIndex()
                    || !first.trackKey().equals(next.trackKey()) || !first.settings().equals(next.settings())
                    || Math.ceil(first.countdown()) != Math.ceil(next.countdown())
                    || first.cars().size() != next.cars().size()) return false;
            for (int i = 0; i < first.cars().size(); i++) {
                var car = first.cars().get(i);
                var newer = next.cars().get(i);
                if (car.id() != newer.id() || car.connected() != newer.connected() || car.retired() != newer.retired()
                        || car.recovery() != newer.recovery() || car.penalty() != newer.penalty()
                        || !car.progress().equals(newer.progress())) return false;
            }
            return true;
        }
    }
    private final class Peer {
        final Socket socket;
        final Outbox outgoing = new Outbox();
        final AtomicBoolean stopped = new AtomicBoolean();
        long lastInput = -1;
        Peer(Socket socket) throws IOException { this.socket = socket; socket.setTcpNoDelay(true); socket.setSoTimeout(15000); }
        void start() { daemon("formula-read",this::read); daemon("formula-write",this::write); }
        void send(Packet packet) { if (!stopped.get() && !outgoing.offer(packet,System.nanoTime())) close(); }
        void sendInput(Packet input, long frameStartedAt) {
            if (!stopped.get() && !outgoing.offer(input, System.nanoTime(), frameStartedAt)) close();
        }
        void read() {
            try {
                var reader = new PacketReader(socket.getInputStream(), host ? 8192 : 262144, gson, System::nanoTime, traffic::received);
                while (!stopped.get()) {
                    var received = reader.read();
                    if (!incoming.offer(new Event(this,received,null))) throw new IOException("Input overflow");
                }
            } catch (IOException | RuntimeException error) { close(); }
        }
        void write() {
            try {
                var stream = new DataOutputStream(socket.getOutputStream());
                while (!stopped.get()) {
                    Packet p = outgoing.take(System::nanoTime); if (p == null) return;
                    byte[] bytes = gson.toJson(p).getBytes(StandardCharsets.UTF_8);
                    if(bytes.length>(host?262144:8192)) throw new IOException("Packet size");
                    stream.writeInt(bytes.length);
                    traffic.sent(Integer.BYTES);
                    stream.write(bytes);
                    traffic.sent(bytes.length);
                    stream.flush();
                    if (p.type.equals("ERROR")) { close(); return; }
                }
            } catch (IOException | InterruptedException | RuntimeException error) { close(); }
        }
        void close() {
            if (!stopped.compareAndSet(false,true)) return;
            outgoing.close();
            try { socket.close(); } catch (IOException ignored) { }
            // The game thread releases the slot and removes this member even when the packet queue is full.
        }
    }
}
