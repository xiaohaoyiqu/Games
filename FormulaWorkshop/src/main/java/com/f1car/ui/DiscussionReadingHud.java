package com.f1car.ui;

import com.f1car.FormulaGame;
import java.util.*;

/** Explicit local marks; opening a list, a preview or a page never marks it read. */
final class DiscussionReadingHud {
    static void controls(HudRenderer hud,FormulaGame app,Object thread,String message,int y) {
        var store=app.discussionReading;
        if(store.blocked() || store.unread(thread)<0) {hud.smallButton(600,y,614,32,store.status(),()->recovery(hud,app));return;}
        boolean read=store.read(thread,message);
        hud.smallButton(600,y,294,32,read?"本条标为未读":"本条标为已读",()->app.markDiscussionReading(thread,message,!read));
        hud.smallButton(914,y,300,32,"本话题当前留言全标已读",()->hud.review("标记当前话题为已读？",List.of("仅标记当前这份话题的 "+(store.unread(thread))+" 条未读留言。","包括其他分页；确认前收到新回复需重新查看。","这是本机个人标记，不表示接受意见或完成处理，也不会随文件发送。","本机最多保留六十四份话题背景的标记；更早标记会移除。"),"标记这份话题",()->app.markDiscussionReading(thread,null,true)));
    }
    static void recovery(HudRenderer hud,FormulaGame app) {
        var store=app.discussionReading;var expected=store.state();
        try {
            if(!store.blocked()) {hud.review("本机已读标记",List.of(store.status(),store.warning(),"重新读取磁盘记录；失败的标记需要重新选择。","标记只表示个人阅读选择，不自动解决意见或形成共享回执。","本机最多保留六十四份话题背景的标记；更早标记会移除。"),"重新读取本机标记",store::reload);return;}
            var disk=store.inspectDisk();hud.review("恢复本机已读标记",List.of(store.warning(),"先保留当前磁盘原字节，再保存上次成功保留在内存的标记。","若当前标记从未读入，将以空标记保存，所有留言需重新标记。","备份最多八份；确认前磁盘或内存变化需要重新查看。"),"保留原档并恢复标记",()->app.notifyUser(store.backupAndSave(disk,expected)?"本机标记已恢复；请重新选择未保存的标记。":store.warning()));
        } catch(java.io.IOException error) {app.notifyUser("标记文件暂时无法读取；解除占用后重试，原文保持。");}
    }
    private DiscussionReadingHud() {}
}
