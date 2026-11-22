package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import com.f1car.persistence.TuningStore;
import java.awt.Color;
import java.util.*;
import java.util.List;

/** Explicit full-library recovery review. Stable frames read only frozen display metadata. */
final class TuningRecoveryHud {
    private static final Color INK=new Color(0xebf5f0),SOFT=new Color(0xa2babd),AMBER=new Color(0xffbe72),GREEN=new Color(0x4cebc1);
    private boolean showing,memory,unavailable;private TuningStore.Recovery view;private TuningStore.Archive accepted;
    private TuningStore.Relation relation=TuningStore.Relation.CONFLICT;
    private List<String> details=List.of(),facts=List.of();private String status="",warning="";
    boolean showing() {return showing;}
    void clear() {showing=memory=unavailable=false;view=null;accepted=null;relation=TuningStore.Relation.CONFLICT;details=facts=List.of();status=warning="";}
    void open(FormulaGame app,boolean memoryView) {
        showing=true;memory=memoryView;var store=app.tuningStore;accepted=store.accepted();view=memoryView && store.pending()?new TuningStore.Recovery(accepted,store.candidate()):memoryView?null:store.recovery();
        unavailable=store.damaged() || store.recoveryBlocked();relation=view==null?TuningStore.Relation.CONFLICT:view.relation(accepted);warning=store.warning+" "+store.recoveryWarning();var lines=new ArrayList<String>();var values=new ArrayList<String>();
        status=unavailable?"主库或副本读取受阻；保留原文，修复后明确重读。":view==null?"这侧没有已读恢复候选；不会自动从其它资料补造报告。":switch(relation) {case READY->"原基准与当前已读报告库一致；可以明确恢复为待存，之后另点保存。";case ALREADY_SAVED->"完整候选已经保存；核对后仅清理副本，不重复发布。";case CONFLICT->"当前正式报告库与原基准不同；保留两边内容，不能直接覆盖。";};
        if(view!=null) {
            values.add("原已存库 "+view.base().reports().size()+" 份 → 候选库 "+view.candidate().reports().size()+" 份；最多八份，完整列表见详情。");
            values.add("当前已读正式库 "+accepted.reports().size()+" 份；恢复不会直接写正式库，也不开始驾驶。");
            var latest=view.candidate().reports().get(0);values.add("候选首份："+latest.id().substring(0,8)+" · "+latest.conditions().exercise().title+" · "+latest.runs().size()+" / 6 次。");
            lines.add("这是完整有界报告库候选；保存会按候选列表保留、更新或淘汰报告，请核对两份完整列表。");
            archive(lines,"原已存基准",view.base(),app.vehicleSpec.physicsVersion);archive(lines,"完整候选",view.candidate(),app.vehicleSpec.physicsVersion);
            lines.add("只恢复普通 A／B 报告及其已有用途／冻结装备来源，不重建缺失连续曲线、不采用配置、不恢复驾驶中途状态。");
        }
        if(store.recoveryFailure()!=null) {var failure=store.recoveryFailure();lines.add("恢复副本保存阶段："+failure.stage()+"；本进程尝试 "+failure.attempt()+"；该次替换调用成功="+failure.replaced());for(var cause:failure.causes()) lines.add("副本异常类别："+cause.type()+"；文件系统原因："+cause.filesystemReason());}
        details=List.copyOf(lines);facts=List.copyOf(values);
    }
    private static void archive(List<String> lines,String name,TuningStore.Archive archive,String physics) {
        lines.add("—— "+name+" · "+archive.reports().size()+" 份 ——");
        for(var report:archive.reports()) {
            var c=report.conditions();lines.add("报告 "+report.id()+"；建立毫秒值 "+report.createdAt()+"；原顺序 "+report.order()+"；失败 "+report.failedAttempts()+" 次。");
            lines.add("路线 "+c.trackId()+" / "+c.trackName()+"；原几何 "+c.geometry()+"；物理 "+c.physics()+"；操作 "+c.controls()+"。");
            for(var field:PartsCatalog.Field.values()) lines.add(field.title+"：A "+Float.toString(field.ratio(report.a()))+"；B "+Float.toString(field.ratio(report.b()))+"。");
            var purpose=archive.purposes()==null?null:archive.purposes().get(report.id());lines.addAll(TuningInterpretation.explain(report,purpose,physics));
            for(int i=0;i<report.runs().size();i++) {var run=report.runs().get(i);var m=run.measurement();lines.add("原第 "+(i+1)+" 组 "+run.slot()+" · #"+run.attempt()+" · 完成毫秒 "+run.recordedAt()+"；原测量 "+Double.toString(m.value())+" "+c.exercise().unit+"；入口／出口 "+Float.toString(m.entry())+" / "+Float.toString(m.exit())+" km/h。");lines.add("原操作均值：油门 "+Float.toString(m.throttle())+"；制动 "+Float.toString(m.brake())+"；转向 RMS "+Float.toString(m.steeringRms())+"；车辆观测 "+(m.observation()==null?"缺失，未补零":"随原报告完整保留，可在报告原组查看")+"。");}
        }
    }
    void render(HudRenderer h,FormulaGame app) {
        var selected=view;var original=accepted;boolean memoryView=memory;h.clearButtons();h.panel(28,24,1224,752,new Color(0x13242e));h.text("普通 A／B · 报告恢复副本",60,98,29,INK,true);h.smallButton(1030,57,184,38,"返回试车中心",this::clear);
        h.text("重开后先核对原基准与完整候选；明确恢复为待存，再单独保存。",60,139,17,AMBER);
        h.smallButton(60,170,350,38,"查看本次内存候选",()->open(app,true));h.smallButton(430,170,350,38,"查看已读磁盘副本",()->open(app,false));h.smallButton(800,170,414,38,"明确重读正式库与恢复副本",()->{app.reloadTuningRecovery();open(app,memoryView);});
        h.text(memoryView?"本次内存候选 · 打开时冻结":"已读磁盘副本 · 打开时冻结",60,252,23,GREEN,true);h.wrapped(status,60,295,1154,18,AMBER,29);
        int y=385;for(var line:facts) {h.text(line,60,y,17,SOFT);y+=36;}
        if(!warning.isBlank()) h.wrapped(warning,60,536,1154,16,AMBER,25);
        if(!details.isEmpty()) h.smallButton(60,607,1154,38,"核对完整原基准、候选、用途与原测量",()->h.review("普通报告恢复的完整原文",details,"返回恢复页",()->{}));
        if(selected==null) return;
        if(memoryView) h.smallButton(60,674,550,44,"核对后重试保存本次报告",()->h.review("保存完整候选报告库？",details,"写恢复副本并保存",()->{if(!Objects.equals(selected.candidate(),app.tuningStore.candidate()) || !Objects.equals(original,app.tuningStore.accepted())) app.notifyUser("本次候选或原基准已变化，请重新核对。");else app.retryTuningSave();open(app,app.tuningStore.pending());}));
        else if(!unavailable && !app.tuningStore.pending() && relation==TuningStore.Relation.READY) h.smallButton(60,674,550,44,"只恢复为待存（不发布）",()->h.review("恢复为待保存报告库？",details,"只恢复为待存",()->{app.restoreTuningRecovery(selected);open(app,app.tuningStore.pending());}));
        else if(!unavailable && !app.tuningStore.pending() && relation==TuningStore.Relation.ALREADY_SAVED) h.smallButton(60,674,550,44,"核对已存，仅清理恢复副本",()->h.review("确认完整候选已经保存？",details,"只清理已核对的副本",()->{app.acknowledgeTuningRecovery(selected);open(app,false);}));
        h.smallButton(640,674,574,44,memoryView?"明确取消所选内存候选":"明确放弃所选磁盘副本",()->{var lines=new ArrayList<>(details);lines.add(memoryView?"仅取消报告候选；同份恢复副本清理成功后才释放内存，另一份副本与连续曲线待存保持。":"只移除所选恢复副本；当前正式报告与本次内存候选保持。");h.review("确认放弃这份恢复内容？",lines,"确认放弃所选内容",()->{if(memoryView) app.cancelTuningPending(selected.candidate());else app.discardTuningRecovery(selected);open(app,memoryView && app.tuningStore.pending());});});
        h.text("不重建缺失连续曲线；没有自动恢复、自动重试或掉电持久性保证。",60,754,15,SOFT);
    }
}
