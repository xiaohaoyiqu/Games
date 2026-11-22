package com.f1car.ui;

import com.f1car.persistence.SaveDiagnostics;
import java.util.*;

/** Optional immutable save metadata; opening or closing this view never retries a report. */
final class TuningSaveHud {
    private TuningSaveHud() {}
    static void controls(HudRenderer h,SaveDiagnostics.Failure failure) {
        if(failure!=null) h.smallButton(888,661,328,29,failure.replaced()?"报告已保存 · 清理详情":"报告保存未完成 · 查看详情",()->h.review("本次报告保存记录",details(failure),"返回试车中心",()->{}));
    }
    static List<String> details(SaveDiagnostics.Failure failure) {
        var lines=new ArrayList<String>();
        lines.add("报告保存阶段："+switch(failure.stage()) {case ENCODE->"整理和编码报告";case PREPARE->"准备资料目录";case BACKUP->"保留损坏文件或旧格式备份";case CREATE_TEMP->"准备临时文件";case WRITE_TEMP->"写入临时文件";case REPLACE->"替换正式报告";case FALLBACK_REPLACE->"不支持原子移动后的替换";case CLEANUP->"清理临时文件";});
        lines.add("本进程尝试 "+failure.attempt()+"；编码字节数 "+(failure.encodedBytes()<0?"尚未完成":failure.encodedBytes())+"。记录保持打开时的这一份，不随之后的保存变化。");
        lines.add(failure.replaced()?"该次正式文件替换调用已成功返回，后续清理异常不会把报告改回待存；这里不承诺掉电持久化。":"该次保存未完成时，报告候选仍留在本进程；原文件最终状态不能只凭此摘要保证，当前待存状态请返回原页查看。");
        for(var cause:failure.causes()) lines.add(cause("异常类别：",cause));
        for(var cause:failure.cleanupCauses()) lines.add(cause("另有清理异常：",cause));
        lines.add("这里只查看本机普通 A／B 报告的保存摘要，不读取报告正文、不识别占用者，也不自动重试；连续记录、四条件试车和收到记录分别处理。");
        lines.add(failure.replaced()?"返回原页查看当前保存状态；不用因这条清理记录重复试车。":"先保留当前报告，处理目录或占用问题后，在原页明确点击“重试保存”；不要据此删除旧报告或盲目重做试车。");
        return List.copyOf(lines);
    }
    private static String cause(String title,SaveDiagnostics.Cause cause) {return title+cause.type()+(cause.filesystemReason().isBlank()?"":"；文件系统原因："+cause.filesystemReason());}
}
