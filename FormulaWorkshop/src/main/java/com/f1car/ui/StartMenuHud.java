package com.f1car.ui;

import com.f1car.FormulaGame;
import java.awt.Color;

final class StartMenuHud {
    private boolean single;
    void reset() { single=false; }
    void back(HudRenderer h,FormulaGame app) { h.navigate(()->{if(single) single=false;else app.mainMenu();}); }
    String context() { return single?"single":"categories"; }
    void render(HudRenderer h,FormulaGame app) {
        h.panel(24,24,430,742,new Color(12,28,40,246));h.text("START GAME",52,75,17,new Color(76,235,193),true);
        h.text(single?"正常单机":"选择游戏模式",52,139,32,new Color(235,245,240),true);
        h.wrapped("先选玩法，再选择赛道、预览地图并确认开始。",52,188,350,17,new Color(162,186,189),28);
        if(!single) {
            h.button(52,280,360,68,"正常单机","四车比赛／自由驾驶／计时",true,()->h.navigate(()->single=true));
            h.button(52,364,360,68,"赛事","单站周末／三站杯／自选赛历",false,()->h.navigate(app::openEvents));
            h.button(52,448,360,68,"挑战","单挑／追击／防守／奖牌挑战",false,()->h.navigate(app::openChallenges));
            h.button(52,532,360,68,"联机","创建或加入方程式房间",false,()->h.navigate(app::openRaceRoom));
        } else {
            h.button(52,280,360,68,"正常比赛","玩家与三辆 AI 同场",true,()->h.navigate(()->app.prepareMode(FormulaGame.Mode.RACE,null)));
            h.button(52,364,360,68,"赛道自由驾驶","无强制目标，熟悉线路",false,()->h.navigate(()->app.prepareMode(FormulaGame.Mode.FREE_CIRCUIT,null)));
            h.button(52,448,360,68,"计时挑战","有效圈、个人最佳与好友幽灵",false,()->h.navigate(()->app.prepareMode(FormulaGame.Mode.TIME_TRIAL,null)));
        }
        h.smallButton(52,684,360,44,single?"返回模式选择":"返回主界面",()->h.navigate(()->{if(single) single=false;else app.mainMenu();}));
    }
    void mini(HudRenderer h,FormulaGame app) {
        h.panel(24,24,430,742,new Color(12,28,40,246));h.text("MINI 4WD",52,75,17,new Color(76,235,193),true);h.text("四驱车小游戏",52,139,32,new Color(235,245,240),true);
        h.wrapped("独立的组装、轨道制作、试跑与竞速。",52,188,350,17,new Color(162,186,189),28);
        h.button(52,280,360,68,"组装四驱车","安装零件、保存和载入整车蓝图",true,()->h.navigate(()->{app.openMini4wd();app.mini4wd().openGarage();}));
        h.button(52,364,360,68,"赛道与试跑","选择轨道，计时或四车竞速",false,()->h.navigate(app::openMini4wd));
        h.button(52,448,360,68,"制作轨道","复制当前轨道或编辑自己的草稿",false,()->h.navigate(()->{app.openMini4wd();app.mini4wd().edit();}));
        h.button(52,532,360,68,"四驱车联机","独立的迷你四驱车房间",false,()->h.navigate(()->{app.openMini4wd();app.mini4wd().showRooms();}));
        h.smallButton(52,684,360,44,"返回主界面",()->h.navigate(app::mainMenu));
    }
}
