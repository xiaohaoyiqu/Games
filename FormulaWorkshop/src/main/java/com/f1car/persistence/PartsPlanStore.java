package com.f1car.persistence;

import com.f1car.model.PartsPlan;
import com.f1car.model.PartsPlanSource;
import com.f1car.model.PartsPlanShare;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Sixteen named plans, independent from the pack library, garage and legacy last draft. */
public final class PartsPlanStore {
    private final Path file;
    private final GuardedArchive archive;
    private List<PartsPlan> entries=List.of();
    private Map<String,PartsPlanSource> sources=Map.of();
    private Map<String,PartsPlanShare.Trail> trails=Map.of();
    private int version=1;
    private boolean writable=true;
    private String warning="";
    private final SaveDiagnostics diagnostics=new SaveDiagnostics("parts-plans");
    public SaveDiagnostics.Failure saveFailure() {return diagnostics.failure();}
    public PartsPlanStore(Path directory) {
        file = directory.resolve("parts-plans.json");
        archive = new GuardedArchive(directory, "parts-plans", PartsPackCodec.PLANS_BYTES, diagnostics);
        reload();
    }
    public List<PartsPlan> entries() {return entries;}
    public PartsPlan find(String id) {return entries.stream().filter(plan->plan.id().equals(id)).findFirst().orElse(null);}
    public PartsPlanSource source(String id) {return sources.get(id);}
    public PartsPlanShare.Trail trail(String id) {var value=trails.get(id);if(value!=null) return value;var source=sources.get(id);return source==null?PartsPlanShare.Trail.EMPTY:new PartsPlanShare.Trail(List.of(source),0);}
    public boolean writable() {return writable;}
    public String warning() {return warning;}
    public Path file() {return file;}
    public void reload() {
        try {
            byte[] bytes = archive.readSource();
            var library = bytes == null ? new PartsPackCodec.PlanLibrary(1, List.of(), Map.of())
                    : PartsPackCodec.readPlanLibrary(new ByteArrayInputStream(bytes));
            entries = library.entries();sources = library.sources();trails = library.trails();
            version = library.version();writable = true;warning = "";
        }
        catch(IOException | RuntimeException error) {writable=false;warning="部件方案库无法读取；保留原文件与已读取方案，恢复文件后重新读取。";}
    }
    public boolean save(PartsPlan value) {return save(value,sources.get(value.id()),trails.get(value.id()));}
    public boolean save(PartsPlan value,PartsPlanSource source) {
        return save(value,source,null);
    }
    public boolean saveShared(PartsPlan value,PartsPlanShare.Trail trail) {if(trail==null || trail.steps().isEmpty()) throw new IllegalArgumentException("Shared copy needs a direct source");return save(value,trail.steps().get(0),trail);}
    private boolean save(PartsPlan value,PartsPlanSource source,PartsPlanShare.Trail trail) {
        Objects.requireNonNull(value);var next=new ArrayList<PartsPlan>();next.add(value);entries.stream().filter(plan->!plan.id().equals(value.id())).forEach(next::add);
        if(next.size()>PartsPlan.CAPACITY) {warning="最多保存 16 份部件方案；请先导出或移除不需要的方案。";return false;}var origins=new HashMap<>(sources);if(source==null) origins.remove(value.id());else origins.put(value.id(),source);var history=new HashMap<>(trails);if(trail==null) history.remove(value.id());else history.put(value.id(),trail);return commit(next,origins,history);
    }
    public boolean remove(String id) {if(find(id)==null) return false;var origins=new HashMap<>(sources);origins.remove(id);var history=new HashMap<>(trails);history.remove(id);return commit(entries.stream().filter(plan->!plan.id().equals(id)).toList(),origins,history);}
    private boolean commit(List<PartsPlan> next,Map<String,PartsPlanSource> origins,Map<String,PartsPlanShare.Trail> history) {
        if(!writable) return false;
        try {
            String share = version < 3 && !history.isEmpty() ? "parts-plans.pre-share-v" + version + ".json" : null;
            String source = version == 1 && !origins.isEmpty() ? "parts-plans.pre-source-v1.json" : null;
            archive.commit(PartsPackCodec.plans(next, origins, history), false, share, source);
            entries = List.copyOf(next);sources = Map.copyOf(origins);trails = Map.copyOf(history);
            version = !history.isEmpty() ? 3 : origins.isEmpty() ? 1 : 2;
            warning = "";
            return true;
        } catch (ArchiveWriter.StaleArchiveException error) {
            warning = "磁盘上的命名方案已更新。请重新读取方案，再核对并重试改动。";
            return false;
        }
        catch(IOException | RuntimeException error) {warning="部件方案尚未保存；已有方案保留，检查用户目录后重试。";return false;}
    }
}
