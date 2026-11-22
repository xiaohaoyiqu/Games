package com.f1car.ui;

import com.f1car.persistence.SaveDiagnostics;
import java.util.*;

/** Optional metadata-only explanation; it never re-saves the rolled-back garage or consumes a draft. */
final class ProfileSaveHud {
    private ProfileSaveHud() {}
    static void controls(HudRenderer h,SaveDiagnostics.Failure failure) {
        if(failure!=null) h.smallButton(782,116,444,37,"上次配置保存失败 · 查看详情",()->h.review("上次配置保存未完成",details(failure),"返回车库",()->{}));
    }
    static List<String> details(SaveDiagnostics.Failure failure) {
        var lines=new ArrayList<String>();lines.add("保存阶段："+switch(failure.stage()) {case ENCODE->"整理和编码配置";case PREPARE->"准备用户目录";case BACKUP->"保留旧文件备份";case CREATE_TEMP->"准备临时文件";case WRITE_TEMP->"写入临时文件";case REPLACE->"替换正式文件";case FALLBACK_REPLACE->"不支持原子移动后的替换";case CLEANUP->"清理临时文件";});
        lines.add("本次进程保存尝试 "+failure.attempt()+"；编码字节数 "+(failure.encodedBytes()<0?"尚未完成":failure.encodedBytes())+"。此处只显示诊断摘要。");
        for(var cause:failure.causes()) lines.add("异常类别："+cause.type()+(cause.filesystemReason().isBlank()?"":"；文件系统原因："+cause.filesystemReason()));
        lines.add("此详情保持打开时的失败记录；不读取存档、自动重试或识别占用进程，也不证明以往错误的原因。");lines.add("数值调整失败时车辆和车库仍保持旧值，输入框内的候选可再次明确提交；已取消的输入需重新填写。装备待装则从装备记录入口处理。");lines.add("修复目录／文件占用后，返回原操作明确重试；这里只查看，不把已回退的车库值误存成候选。");return List.copyOf(lines);
    }
}
