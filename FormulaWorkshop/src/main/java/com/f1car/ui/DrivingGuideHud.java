package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.input.KeyboardSettings.Command;
import com.f1car.model.*;
import java.awt.Color;

final class DrivingGuideHud {
    private static final Color INK=new Color(235,245,240),SOFT=new Color(162,186,189),MINT=new Color(76,235,193);
    void render(HudRenderer h,FormulaGame app) {
        h.panel(28,24,1224,752,new Color(10,29,39,246));h.text("FIRST LAPS",60,65,14,MINT,true);h.text("四图驾驶入门",60,107,32,INK,true);
        h.smallButton(866,57,166,42,"键盘键位",app::openKeyboard);h.smallButton(1050,57,166,42,"返回主菜单",app::mainMenu);
        h.text("随时可以直接比赛，也可以按下面三步熟悉驾驶。选图只改变预览，开始前会再次确认。",60,145,17,SOFT);
        var tracks=TrackCatalog.all();for(int i=0;i<tracks.size();i++) { var entry=tracks.get(i);h.smallButton(60+i*291,170,275,44,(entry.id().equals(app.circuit.id)?"● ":"")+entry.layout().name,()->app.chooseGuideTrack(entry.id())); }
        var entry=TrackCatalog.forLayout(app.circuit);h.text(entry.layout().name,60,263,27,MINT,true);h.wrapped(entry.description(),60,302,1120,18,INK,28);
        String advice=switch(entry.theme()) {
            case HARBOR -> "海湾：先认识整圈与检查点，入弯前减速，再平顺加速出弯。";
            case FOREST -> "林地：连续弯要看向下一处出口，少作来回修正；先练稳定，再追单圈。";
            case PARK -> "公园：直线长，接近高速弯前提前找制动参照；提高动力后要重新观察制动点。";
            case STREET -> "港区：道路两侧有实体建筑与护栏，先留出余量；靠近对手时避免突然变线。";
        };
        h.wrapped(advice,60,369,1110,19,INK,30);
        h.text("键盘  油门 "+app.keyLabel(Command.THROTTLE)+"    制动／倒车 "+app.keyLabel(Command.BRAKE)+"    全制动 "+app.keyLabel(Command.HANDBRAKE),60,447,17,MINT);
        h.text("转向 "+app.keyLabel(Command.LEFT)+" / "+app.keyLabel(Command.RIGHT)+"    复位 "+app.keyLabel(Command.RESET)+"    暂停 ESC",60,480,17,MINT);
        String[] titles={"1  起步与停车","2  无目标熟悉赛道","3  一圈轻松比赛"};
        String[] details={"固定训练场练加速和停稳，可随时重试。","跑完整路线认识检查点，越界的圈不计最佳。","与三辆 AI 跑一圈，先练完赛与留空间。"};
        for(int i=0;i<3;i++) {
            int x=60+i*390;h.panel(x,529,370,170,new Color(24,49,60));h.text(titles[i],x+18,563,22,INK,true);h.wrapped(details[i],x+18,596,330,16,SOFT,25);
            int step=i;h.smallButton(x+18,642,334,40,"选择并开始",()->{if(step==0) app.prepareMode(FormulaGame.Mode.LESSON,PracticeSession.Lesson.START_STOP);else app.prepareGuideDrive(step==1?FormulaGame.Mode.FREE_CIRCUIT:FormulaGame.Mode.RACE);});
        }
        h.text("手柄驾驶可在设置中校准。若圈速无效，先看越界和检查点提示；普通比赛复位会增加罚时。",60,744,15,SOFT);
    }
}
