package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.persistence.EditorDraftStore;
import java.util.*;

/** Explicit disk inspection; routine rendering only reads the in-memory status. */
final class DraftRecoveryHud {
    static <T> void review(HudRenderer hud,FormulaGame app,EditorDraftStore<T> store) {
        app.captureAuthoringDrafts();var value=store.current();
        var lines=new ArrayList<String>();lines.add(store.status());if(!store.warning().isEmpty()) lines.add(store.warning());
        lines.add("草稿恢复与作品保存、采用、留言发布分别操作。恢复不改变车库、成绩或本机测量。");
        if(store.needsBackup()) {
            try {
                var disk=store.inspectDisk();lines.add("确认将保留当前磁盘原字节，再保存本次草稿；最多八份恢复备份。确认前草稿或磁盘变化需要重新查看。");
                hud.review("草稿恢复 · 保留两份",lines,"保留两份并保存草稿",()->{
                    app.captureAuthoringDrafts();if(!Objects.equals(value,store.current())) {app.notifyUser("草稿已变化，请重新查看。");return;}
                    app.notifyUser(store.backupAndSave(disk,value)?"原文件已保留，草稿已保存。":store.warning());
                });
            } catch(java.io.IOException error) {app.notifyUser("恢复文件暂时无法读取，本次草稿保留；解除占用后重试。");}
        } else hud.review("草稿恢复状态",lines,"重试保存当前草稿",()->{app.captureAuthoringDrafts();app.notifyUser(store.retry()?"草稿恢复已保存。":store.warning());});
    }
}
