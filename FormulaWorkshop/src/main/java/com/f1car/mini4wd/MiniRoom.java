package com.f1car.mini4wd;

import com.f1car.model.DriverName;
import com.f1car.network.RoomSendClock;
import com.f1car.network.RoomSnapshotTiming;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.NetworkInterface;
import java.net.Inet4Address;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** TCP room transport. Only poll() changes room and race state on the game thread. */
public final class MiniRoom implements AutoCloseable {
    public static final int DEFAULT_PORT = 26950;
    public record Member(int id, String name, MiniSetup setup, boolean ready) {}

    private static final class Packet {
        int protocol = 2;
        String type, name, map, message;
        int id;
        boolean value;
        transient long queuedAt;
        MiniSetup setup;
        List<Member> members;
        MiniRace.Frame frame;
        Packet(String type) { this.type = type; }
    }
    private record Event(Peer peer, Packet packet, String error, long startedAt, long receivedAt) {}
    private static final int EVENTS_PER_UPDATE = 64;
    private static final long HEARTBEAT_NANOS = 1_000_000_000L;
    // Older guests send one PING per second while holding boost.
    static final long BOOST_TIMEOUT_NANOS = 3_000_000_000L;

    static final class RemoteBoost {
        private boolean held;
        private long receipt;

        void set(boolean value, long startedAt) {
            held = value;
            receipt = startedAt;
        }
        boolean active(long now) {
            if (held && now - receipt >= BOOST_TIMEOUT_NANOS) held = false;
            return held;
        }
        void heartbeat(long startedAt) {
            if (active(startedAt)) receipt = startedAt;
        }
    }

    private final boolean host;
    private final Gson gson = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private final MiniTrackStore codec;
    private final ArrayBlockingQueue<Event> incoming = new ArrayBlockingQueue<>(256);
    private final Set<Peer> peers = ConcurrentHashMap.newKeySet();
    private final Map<Peer, Integer> identities = new LinkedHashMap<>();
    private final Map<Integer, Member> members = new LinkedHashMap<>();
    private volatile boolean closed;
    private volatile Peer server;
    private ServerSocket listener;
    private MiniTrackStore.MapData map;
    private String encodedMap, status = "正在连接房间…";
    private int localId = -1, nextId = 1, port;
    private MiniRace race;
    private MiniRace.Frame latest, previous, interpolatedFrame;
    private final RoomSendClock broadcastClock = new RoomSendClock();
    private long lastHostBroadcast;
    private final LongSupplier nanoTime;
    private long previousReceipt, latestReceipt;
    private long raceEndCount;
    private boolean localBoost, sentBoost;
    private long latestFrameStarted, raceStarted, lastGuestSend;
    private volatile Packet latestHeldBoost;
    private volatile String desiredName;
    private volatile MiniSetup desiredSetup;
    private List<String> cachedAddresses;

    MiniRoom(boolean host, MiniTrackStore codec, LongSupplier nanoTime) {
        this.host = host;
        this.codec = codec;
        this.nanoTime = nanoTime;
    }

    public static MiniRoom host(MiniTrackStore codec, MiniTrackStore.MapData map, String name, MiniSetup setup, int port) throws IOException {
        return host(codec, map, name, setup, port, System::nanoTime);
    }

    static MiniRoom host(MiniTrackStore codec, MiniTrackStore.MapData map, String name, MiniSetup setup, int port,
                         LongSupplier clock) throws IOException {
        if (!map.track().inspect().ready()) throw new IOException("地图需要先连接成可试跑路线。");
        if (!setup.ready()) throw new IOException("请先完成车辆组装检查。");
        MiniRoom room = new MiniRoom(true, codec, clock);
        room.map = map;
        room.encodedMap = codec.encode(map.track(), map.name());
        room.localId = 0;
        room.desiredName = playerName(name); room.desiredSetup = setup;
        room.members.put(0, new Member(0, playerName(name), setup, true));
        room.listener = new ServerSocket();
        try {
            room.listener.setReuseAddress(true);
            room.listener.bind(new InetSocketAddress("0.0.0.0", port));
        } catch (IOException e) { room.listener.close(); throw e; }
        room.port = room.listener.getLocalPort();
        room.status = "等待车手加入";
        daemon("mini4wd-accept", () -> room.accept());
        return room;
    }

    public static MiniRoom join(MiniTrackStore codec, String address, int port, String name, MiniSetup setup) {
        return join(codec, address, port, name, setup, System::nanoTime);
    }

    static MiniRoom join(MiniTrackStore codec, String address, int port, String name, MiniSetup setup, LongSupplier clock) {
        MiniRoom room = new MiniRoom(false, codec, clock);
        room.port = port;
        room.desiredName = playerName(name); room.desiredSetup = setup;
        daemon("mini4wd-connect", () -> {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(address, port), 5000);
                if (room.closed) { socket.close(); return; }
                Peer peer = room.new Peer(socket);
                room.server = peer;
                room.peers.add(peer);
                if (room.closed) { peer.close(); room.peers.remove(peer); return; }
                peer.start();
                Packet hello = new Packet("HELLO");
                hello.name = room.desiredName;
                hello.setup = room.desiredSetup;
                peer.send(hello);
            } catch (IOException | RuntimeException e) {
                try { socket.close(); } catch (IOException ignored) { }
                room.incoming.offer(new Event(null, null, "连接失败，请检查地址、端口和房主状态。", 0, 0));
            }
        });
        return room;
    }

    public boolean host() { return host; }
    public boolean closed() { return closed; }
    public int localId() { return localId; }
    public int port() { return port; }
    public String status() { return status; }
    public String addressHint() { return addressHints().get(0); }
    public List<String> addressHints() {
        if (cachedAddresses != null) return cachedAddresses;
        List<String> result = new ArrayList<>();
        try {
            var interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                var network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                var addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    var address = addresses.nextElement();
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) result.add(address.getHostAddress() + ":" + port);
                }
            }
        } catch (IOException ignored) { }
        if (result.isEmpty()) result.add("127.0.0.1:" + port);
        result.sort(java.util.Comparator.comparingInt(address -> address.startsWith("192.168.") ? 0 : address.startsWith("10.") ? 1 : 2));
        cachedAddresses = List.copyOf(result);
        return cachedAddresses;
    }
    public MiniTrackStore.MapData map() { return map; }
    public List<Member> members() { return List.copyOf(members.values()); }
    public boolean canStart() { return host && race == null && members.size() >= 2 && members.values().stream().allMatch(m -> m.ready && m.setup.ready()); }
    public boolean ready() { return members.containsKey(localId) && members.get(localId).ready; }

    public void ready(boolean value) {
        if (host || server == null || latest != null) return;
        Packet packet = new Packet("READY"); packet.value = value && desiredSetup.ready(); server.send(packet);
    }

    public void configure(String name, MiniSetup setup) {
        if (latest != null) return;
        desiredName = playerName(name); desiredSetup = setup;
        if (host) {
            members.put(0, new Member(0, playerName(name), setup, setup.ready()));
            broadcastLobby();
        } else if (server != null) {
            Packet packet = new Packet("CONFIG"); packet.name = playerName(name); packet.setup = setup; server.send(packet);
        }
    }

    public void startRace() {
        if (!canStart()) return;
        List<MiniRace.Entry> entries = members.values().stream().map(m -> new MiniRace.Entry(m.id, m.name, m.setup, false)).toList();
        race = new MiniRace(map.track().inspect(), entries);
        raceStarted = nanoTime.getAsLong();
        for (var peer : identities.keySet()) peer.boost.set(false, raceStarted);
        broadcastClock.reset();
        latest = race.frame();
        broadcastFrame();
    }

    public void finishRace() {
        if (!host) return;
        race = null;
        clearRaceFrames();
        members.replaceAll((id, member) -> new Member(id, member.name, member.setup, id == 0 && member.setup.ready()));
        broadcastLobby();
    }

    public void boost(boolean value) {
        if (localBoost == value || (value && !hasRaceFrame())) return;
        localBoost = value;
        if (!host) sendGuestInput();
    }

    private void sendGuestInput() {
        if (server == null || localId < 0) return;
        long now = nanoTime.getAsLong();
        boolean heartbeat = now - lastGuestSend >= HEARTBEAT_NANOS;
        boolean held = localBoost && latest != null && !latest.complete()
                && now - latestFrameStarted < BOOST_TIMEOUT_NANOS;
        if (held != sentBoost || (heartbeat && held)) {
            Packet packet = new Packet("BOOST");
            packet.value = held;
            packet.queuedAt = now;
            latestHeldBoost = held ? packet : null;
            server.send(packet);
            sentBoost = held;
            lastGuestSend = now;
        } else if (heartbeat) {
            server.send(new Packet("PING"));
            lastGuestSend = now;
        }
    }

    public void poll(double dt) {
        if (closed) return;
        if (!Double.isFinite(dt) || dt < 0) dt = 0;
        dt = Math.min(.25, dt);
        if (!host && interpolatedFrame != latest) interpolatedFrame = null;
        int remaining = Math.min(incoming.size(), EVENTS_PER_UPDATE);
        Event event;
        while (!closed && remaining-- > 0 && (event = incoming.poll()) != null) {
            if (host && event.peer != null && event.peer.stopped.get()) continue;
            try { receive(event); }
            catch (IOException | RuntimeException e) {
                if (host && event.peer != null) reject(event.peer, "房间数据格式不正确。");
                else { status = "房间数据读取失败。"; close(); }
            }
        }
        if (closed) return;
        for (Peer peer : peers) {
            if (!peer.stopped.get() || !host && !incoming.isEmpty() || !peers.remove(peer)) continue;
            if (!host) { status = "房主连接已断开。"; close(); return; }
            Integer id = identities.remove(peer);
            if (id != null) {
                members.remove(id);
                if (race != null) race.disconnect(id);
                else broadcastLobby();
            }
        }
        if (!host) {
            sendGuestInput();
            return;
        }
        if (race != null) {
            var before = latest;
            race.boost(0, localBoost);
            long now = nanoTime.getAsLong();
            for (var entry : identities.entrySet()) race.boost(entry.getValue(), entry.getKey().boost.active(now));
            race.update(dt);
            latest = race.frame();
            if (latest.complete()) {
                // MiniRace reuses its final frame until a participant's state changes.
                if (latest != before) broadcastFrame();
            } else if (broadcastClock.advance(dt)) broadcastFrame();
        }
        if (!identities.isEmpty() && nanoTime.getAsLong() - lastHostBroadcast >= HEARTBEAT_NANOS)
            broadcast(new Packet("PING"));
    }

    boolean hasRaceFrame() { return latest != null; }
    long raceEndCount() { return raceEndCount; }

    private void clearRaceFrames() {
        if (latest != null) raceEndCount++;
        latest = previous = interpolatedFrame = null;
        localBoost = sentBoost = false;
        latestHeldBoost = null;
    }

    private void acceptRaceFrame(MiniRace.Frame frame, long receivedAt) {
        previous = latest;
        previousReceipt = latestReceipt;
        latest = frame;
        latestReceipt = receivedAt;
        interpolatedFrame = null;
    }

    public MiniRace.Frame frame() {
        if (host || previous == null || latest.complete() || previous.countdown() > 0 || latest.countdown() > 0) return latest;
        if (interpolatedFrame != null) return interpolatedFrame;
        double t = RoomSnapshotTiming.fraction(latest.elapsed() - previous.elapsed(), previousReceipt, latestReceipt, nanoTime.getAsLong());
        if (t == 1) {
            interpolatedFrame = latest;
            return latest;
        }

        var current = latest.cars();
        List<MiniRace.CarFrame> cars = null;
        for (int i = 0; i < current.size(); i++) {
            var car = current.get(i);
            var before = previousCar(car.id());
            if (before == null || !before.connected() || !car.connected() || before.result() != MiniTrial.Result.RUNNING
                    || car.result() != MiniTrial.Result.RUNNING || before.lane() != car.lane()
                    || (before.x() == car.x() && before.y() == car.y() && before.heading() == car.heading())) continue;
            if (cars == null) cars = new ArrayList<>(current);
            double turn = Math.IEEEremainder(car.heading() - before.heading(), 2 * Math.PI);
            double angle = Math.IEEEremainder(before.heading() + turn * t, 2 * Math.PI);
            cars.set(i, new MiniRace.CarFrame(car.id(), car.name(), car.lane(), car.setup(), car.result(), car.connected(), car.progress(),
                car.elapsed(), car.speed(), car.battery(), car.boosting(), before.x() + (car.x() - before.x()) * t,
                before.y() + (car.y() - before.y()) * t, angle, car.laps(), car.lapTimes()));
        }
        interpolatedFrame = cars == null ? latest : new MiniRace.Frame(latest.closed(), latest.countdown(), latest.elapsed(), latest.complete(), cars);
        return interpolatedFrame;
    }

    private MiniRace.CarFrame previousCar(int id) {
        var cars = previous.cars();
        for (int i = 0; i < cars.size(); i++) {
            var car = cars.get(i);
            if (car.id() == id) return car;
        }
        return null;
    }

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
            } catch (IOException e) {
                if (!closed) incoming.offer(new Event(null, null, "房间监听已停止。", 0, 0));
                return;
            }
        }
    }

    private void receive(Event event) throws IOException {
        if (event.error != null) {
            status = event.error;
            close();
            return;
        }
        Packet packet = event.packet;
        if (packet == null || packet.protocol != 2 || packet.type == null) throw new IOException("Protocol mismatch");
        if (!host) {
            switch (packet.type) {
                case "WELCOME" -> {
                    localId = packet.id;
                    lastGuestSend = nanoTime.getAsLong();
                    configure(desiredName, desiredSetup);
                }
                case "LOBBY" -> {
                    if (packet.members == null || packet.members.isEmpty() || packet.members.size() > 4
                            || packet.members.stream().anyMatch(member -> member == null || member.id() < 0
                                || member.name() == null || member.setup() == null || member.ready() && !member.setup().ready())
                            || packet.members.stream().map(Member::id).distinct().count() != packet.members.size())
                        throw new IOException("Invalid room members");
                    if (!packet.map.equals(encodedMap)) { map = codec.decode(packet.map); encodedMap = packet.map; }
                    members.clear();
                    for (Member m : packet.members) members.put(m.id, m);
                    clearRaceFrames();
                    status = "等待准备与发车";
                }
                case "FRAME" -> {
                    if (map == null) throw new IOException("Race frame before the room map");
                    validateFrame(packet.frame, map.track().inspect().closed());
                    latestFrameStarted = event.startedAt;
                    acceptRaceFrame(packet.frame, event.receivedAt);
                    status = "比赛进行中";
                }
                case "ERROR" -> { status = packet.message; close(); }
                default -> { }
            }
            return;
        }
        if (packet.type.equals("HELLO")) {
            if (identities.containsKey(event.peer)) return;
            if (members.size() >= 4 || race != null) { reject(event.peer, race != null ? "比赛进行中，请稍后加入。" : "房间已满。 "); return; }
            int id = nextId++;
            identities.put(event.peer, id);
            members.put(id, new Member(id, playerName(packet.name), packet.setup == null ? MiniSetup.stock() : packet.setup, false));
            Packet welcome = new Packet("WELCOME"); welcome.id = id; event.peer.send(welcome);
            broadcastLobby();
            return;
        }
        Integer id = identities.get(event.peer);
        if (id == null) throw new IOException("Handshake required");
        Member member = members.get(id);
        switch (packet.type) {
            case "READY" -> {
                if (race == null) { members.put(id, new Member(id, member.name, member.setup, packet.value && member.setup.ready())); broadcastLobby(); }
            }
            case "CONFIG" -> {
                if (race == null) { members.put(id, new Member(id, playerName(packet.name), packet.setup == null ? MiniSetup.stock() : packet.setup, false)); broadcastLobby(); }
            }
            case "BOOST" -> {
                if (race != null && event.startedAt - raceStarted >= 0) event.peer.boost.set(packet.value, event.startedAt);
            }
            case "PING" -> {
                if (race != null && event.startedAt - raceStarted >= 0) event.peer.boost.heartbeat(event.startedAt);
            }
            default -> { }
        }
    }

    static void validateFrame(MiniRace.Frame frame, boolean closedTrack) throws IOException {
        if (frame == null || frame.closed() != closedTrack || !inRange(frame.countdown(), 0, 3)
                || !inRange(frame.elapsed(), 0, Double.MAX_VALUE) || frame.cars().size() < 2 || frame.cars().size() > 4)
            throw new IOException("Invalid race frame");
        var ids = new HashSet<Integer>();
        int lanes = 0;
        boolean allStopped = true;
        int lapLimit = closedTrack ? MiniTrial.LAPS : 1;
        for (var car : frame.cars()) {
            if (car.id() < 0 || !ids.add(car.id()) || car.lane() < 0 || car.lane() >= frame.cars().size()
                    || (lanes & (1 << car.lane())) != 0 || car.name() == null || car.setup() == null || !car.setup().ready()
                    || car.result() == null || !inRange(car.progress(), 0, lapLimit + 1e-8)
                    || !inRange(car.elapsed(), 0, Double.MAX_VALUE) || !inRange(car.speed(), 0, Double.MAX_VALUE)
                    || !inRange(car.battery(), 0, 1) || !inRange(car.x(), -1, MiniTrack.COLUMNS + 1)
                    || !inRange(car.y(), -1, MiniTrack.ROWS + 1) || !inRange(car.heading(), -Math.PI, Math.PI)
                    || car.laps() < 0 || car.laps() > lapLimit || car.lapTimes() == null || car.lapTimes().size() > lapLimit
                    || car.lapTimes().stream().anyMatch(time -> time == null || !inRange(time, 0, Double.MAX_VALUE)))
                throw new IOException("Invalid racer observation");
            lanes |= 1 << car.lane();
            if (car.result() == MiniTrial.Result.RUNNING) {
                if (!car.connected()) throw new IOException("Disconnected racer is still running");
                allStopped = false;
            }
        }
        if (frame.complete() != allStopped) throw new IOException("Race completion disagrees with racer results");
    }

    private static boolean inRange(double value, double minimum, double maximum) {
        return value >= minimum && value <= maximum;
    }

    private void broadcastLobby() {
        Packet packet = new Packet("LOBBY"); packet.map = encodedMap; packet.members = members();
        broadcast(packet);
    }
    private void broadcastFrame() {
        Packet packet = new Packet("FRAME"); packet.frame = latest;
        broadcast(packet);
    }
    private void broadcast(Packet packet) {
        lastHostBroadcast = nanoTime.getAsLong();
        for (Peer peer : identities.keySet()) peer.send(packet);
    }
    private void reject(Peer peer, String message) {
        Packet p = new Packet("ERROR"); p.message = message; peer.send(p);
    }

    @Override public void close() {
        closed = true;
        if (listener != null) try { listener.close(); } catch (IOException ignored) { }
        for (Peer peer : peers) peer.close();
        peers.clear();
    }

    private static String playerName(String name) {
        return DriverName.clean(name);
    }

    private static void daemon(String name, Runnable task) { Thread thread = new Thread(task, name); thread.setDaemon(true); thread.start(); }

    private final class Peer {
        private final Socket socket;
        private final ArrayBlockingQueue<Packet> outgoing = new ArrayBlockingQueue<>(64);
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final RemoteBoost boost = new RemoteBoost();

        Peer(Socket socket) throws IOException {
            this.socket = socket;
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(15000);
        }
        void start() { daemon("mini4wd-read", this::read); daemon("mini4wd-write", this::write); }
        void send(Packet packet) { if (!stopped.get() && !outgoing.offer(packet)) close(); }
        void read() {
            try {
                DataInputStream input = new DataInputStream(socket.getInputStream());
                var decoder=StandardCharsets.UTF_8.newDecoder();
                byte[] header = new byte[Integer.BYTES];
                var lengthBuffer = ByteBuffer.wrap(header);
                while (!stopped.get()) {
                    header[0] = input.readByte();
                    long startedAt = nanoTime.getAsLong();
                    input.readFully(header, 1, header.length - 1);
                    int length = lengthBuffer.getInt(0);
                    if (length <= 0 || length > 1_000_000) throw new IOException("Invalid packet size");
                    byte[] bytes = input.readNBytes(length);
                    if (bytes.length != length) throw new IOException("Incomplete packet");
                    long receivedAt = nanoTime.getAsLong();
                    Packet packet=gson.fromJson(decoder.decode(ByteBuffer.wrap(bytes)).toString(),Packet.class);
                    if (!incoming.offer(new Event(this, packet, null, startedAt, receivedAt))) throw new IOException("Room input overflow");
                }
            } catch (IOException | RuntimeException e) { close(); }
        }
        void write() {
            try {
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                while (!stopped.get()) {
                    Packet packet = outgoing.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                    if (packet == null) continue;
                    if (packet.value && "BOOST".equals(packet.type) && (packet != latestHeldBoost
                            || nanoTime.getAsLong() - packet.queuedAt >= BOOST_TIMEOUT_NANOS)) continue;
                    byte[] bytes = gson.toJson(packet).getBytes(StandardCharsets.UTF_8);
                    output.writeInt(bytes.length); output.write(bytes); output.flush();
                    if (packet.type.equals("ERROR")) { close(); return; }
                }
            } catch (IOException | InterruptedException | RuntimeException e) { close(); }
        }
        void close() {
            if (!stopped.compareAndSet(false, true)) return;
            try { socket.close(); } catch (IOException ignored) { }
            // The game thread releases the slot and removes this member even when the packet queue is full.
        }
    }
}
