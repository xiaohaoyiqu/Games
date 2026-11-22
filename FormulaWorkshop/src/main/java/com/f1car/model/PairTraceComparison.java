package com.f1car.model;

import java.util.*;

/** Explicit cross-source reading of two frozen originals. No aggregation, adoption or identity certification. */
public final class PairTraceComparison {
    public enum Kind {LOCAL,HISTORY,RECEIVED}
    public enum Issue {
        NONE(""),SOURCE_CONFLICT("同一报告身份的冻结计划冲突；请分别核对原文。"),
        RUN_CONFLICT("同一报告身份与原试次编号包含不同原文；不能混作历史版本。"),
        SAME_RUN("两侧声明为同一个成功原试次；副本不能当作两次驾驶。"),
        RAW_COPY("两侧原完成时间、原测量与完整原过程相同；不能当作两份独立观测。");
        public final String title;Issue(String title) {this.title=title;}
    }
    public record Source(Kind kind,PairExperiment.Snapshot report,PairTrialRevision history,PairTrialShare share) {
        public Source {
            Objects.requireNonNull(kind);Objects.requireNonNull(report);
            boolean valid=switch(kind) {
                case LOCAL -> history==null && share==null;
                case HISTORY -> history!=null && share==null && report.equals(history.before());
                case RECEIVED -> share!=null && report.equals(share.report()) && Objects.equals(history,share.history());
            };
            if(!valid) throw new IllegalArgumentException("Source declaration does not contain this frozen report");
        }
        public static Source local(PairExperiment.Snapshot report) {return new Source(Kind.LOCAL,report,null,null);}
        public static Source history(PairTrialRevision history) {return new Source(Kind.HISTORY,Objects.requireNonNull(history).before(),history,null);}
        public static Source received(PairTrialShare share) {return new Source(Kind.RECEIVED,Objects.requireNonNull(share).report(),share.history(),share);}
        public String label() {return switch(kind) {case LOCAL -> "本机已存";case HISTORY -> "本机历史";case RECEIVED -> "收到·"+shortText(share.author(),12);};}
        public List<String> declaration() {
            var lines=new ArrayList<String>();lines.add("来源："+label()+"；完整报告 "+report.id()+"。");
            if(history!=null) lines.add("历史身份 "+history.id()+"；原计数 #"+history.before().lastAttempt()+" → #"+history.afterAttempt()+"。");
            if(share!=null) {lines.add("收到封套 "+share.id()+"；提供者声明："+share.author()+"；分享毫秒值 "+share.sharedAt()+"。");lines.add("提供者原说明："+share.notes());lines.add("收到记录保持外部声明，不提升为本机测量；身份与声明不认证作者或实际独立驾驶。");}
            return List.copyOf(lines);
        }
        private static String shortText(String text,int limit) {return text.codePointCount(0,text.length())<=limit?text:text.substring(0,text.offsetByCodePoints(0,limit))+"…";}
    }
    public record Pick(Source source,int index) {
        public Pick {Objects.requireNonNull(source);new PairTraceView.RunSource(source.report(),index);}
        public PairTraceView.RunSource original() {return new PairTraceView.RunSource(source.report(),index);}
    }
    private final List<Pick> picks;
    private final List<String> differences;
    private final String routeRestriction;
    public PairTraceComparison(Pick left,Pick right) {
        var issue=issue(left,right);if(issue!=Issue.NONE) throw new IllegalArgumentException(issue.title);
        picks=List.of(left,right);var a=left.source().report();var b=right.source().report();var ac=a.conditions();var bc=b.conditions();
        var notes=new ArrayList<String>();
        if(ac.exercise()!=bc.exercise()) notes.add("试车项目不同："+ac.exercise().title+" / "+bc.exercise().title+"；原测量单位和窗口各自保留，不比较成绩大小。");
        if(!ac.geometry().equals(bc.geometry())) notes.add("冻结路线几何不同；只允许各自原时间轴，不共用路线坐标。");
        if(!ac.trackId().equals(bc.trackId()) || !ac.trackName().equals(bc.trackName())) notes.add("路线身份或名称不同："+ac.trackName()+" / "+bc.trackName()+"；完整身份分别查看。");
        if(!ac.physics().equals(bc.physics())) notes.add("物理版本不同；曲线差异不能直接解释为装备收益。");
        if(!ac.controls().equals(bc.controls())) notes.add("操作身份不同；原油门、制动和转向各自保留。");
        if(!a.plan().model().equals(b.plan().model()) || !a.plan().modelIdentity().equals(b.plan().modelIdentity())) notes.add("车型或完整模型来源不同；不只按可见参数判定相同车辆。");
        if(!a.plan().equipment().equals(b.plan().equipment())) notes.add("完整冻结装备定义或安装背景不同；本页不安装或采用任何方案。");
        if(!a.tuning(left.index()).withLivery(0).equals(b.tuning(right.index()).withLivery(0))) notes.add("两侧实际性能参数不同；同名四条件标签不代表同一配置。");
        if(!a.plan().equals(b.plan())) notes.add("四条件完整计划不同；本页不合并试次、中位数或贡献估计。");
        if(notes.isEmpty()) notes.add("冻结背景与所选实际性能参数一致；仍需核对原操作、窗口和波动，不证明收益。");
        notes.add("时间轴使用各自原窗口的时间；相同时间不保证相同位置或驾驶阶段。没有平移、归一化或插值。");
        notes.add("本页仅对照声明中的原文；不同编号不认证独立驾驶，同一原文副本不作两次观测。");
        differences=List.copyOf(notes);
        routeRestriction=!ac.geometry().equals(bc.geometry())?"冻结路线几何不同；保留各自原时间轴，不对齐路线坐标。":ac.exercise()!=bc.exercise()?"试车项目不同；保留各自原时间轴，不对齐路线坐标。":!positions(left) || !positions(right)?"所选组或对照组缺少可用原位置；保留时间轴，不补造位置。":"";
    }
    public List<Pick> picks() {return picks;}public Pick pick(int slot) {return picks.get(slot);}
    public List<String> differences() {return differences;}public boolean routeAvailable() {return routeRestriction.isEmpty();}public String routeRestriction() {return routeRestriction;}
    public static Issue issue(Pick left,Pick right) {
        Objects.requireNonNull(left);Objects.requireNonNull(right);var a=left.original();var b=right.original();
        if(a.report().id().equals(b.report().id())) {
            if(!PairTrialRevision.sameSource(a.report(),b.report())) return Issue.SOURCE_CONFLICT;
            for(var x:a.report().runs()) for(var y:b.report().runs()) if(x.attempt()==y.attempt() && !x.equals(y)) return Issue.RUN_CONFLICT;
            if(a.run().attempt()==b.run().attempt()) return Issue.SAME_RUN;
        }
        if(a.run().recordedAt()==b.run().recordedAt() && a.run().measurement().equals(b.run().measurement()) && a.run().trace().equals(b.run().trace())) return Issue.RAW_COPY;
        return Issue.NONE;
    }
    private static boolean positions(Pick pick) {var positions=pick.original().run().trace().positions();return positions!=null && positions.stream().anyMatch(p->p.aligned()==1);}
}
