package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.TrackPreview;
import com.f1car.model.Tuning;
import java.awt.*;

final class GameSetupHud {
    private static final Color INK=new Color(235,245,240),MINT=new Color(76,235,193),SOFT=new Color(162,186,189);
    private int page;
    private FormulaGame.LaunchSetup previous;
    private String roomPerformance;
    void render(HudRenderer h,Graphics2D g,FormulaGame app) {
        var setup=app.launchSetup();if(setup==null) return;
        if(previous!=setup) {
            previous=setup;page=0;
            roomPerformance=setup.roomLaunch()?Tuning.performanceDescription(setup.tuning.performanceKey()):null;
        }
        h.panel(24,24,430,742,new Color(12,28,40,246));h.text("READY TO PLAY",52,65,15,MINT,true);h.text(setup.title,52,110,27,INK,true);
        h.smallButton(52,133,360,38,"返回",()->h.navigate(()->app.cancelLaunch(setup)));
        h.text(setup.selectable?"选择赛道":"本模式使用固定赛道",52,214,22,MINT,true);
        if(setup.selectable) {
            var entries=app.launchTracks();int pages=Math.max(1,(entries.size()+3)/4);page=Math.max(0,Math.min(page,pages-1));
            for(int i=page*4;i<Math.min(entries.size(),page*4+4);i++) { var entry=entries.get(i);String name=entry.layout().name;h.smallButton(52,235+(i-page*4)*62,360,50,(entry.id().equals(setup.preview.track().id)?"● ":"")+(name.length()>18?name.substring(0,17)+"…":name),()->app.chooseLaunchTrack(entry.id())); }
            if(pages>1) { h.smallButton(52,493,100,32,"上一页",()->page=Math.max(0,page-1));h.centered((page+1)+" / "+pages,230,516,15,SOFT);h.smallButton(312,493,100,32,"下一页",()->page=Math.min(pages-1,page+1)); }
        } else h.wrapped(setup.training?"综合训练场 · 固定课程布局":setup.preview.track().name,52,269,350,20,INK,32);
        if(setup.roomLaunch()) {
            h.text("房主赛车 · 涂装 "+(setup.tuning.livery()+1),52,355,18,MINT,true);
            h.wrapped(roomPerformance,52,391,355,16,INK,26);
            h.text(setup.drivingRule(),52,575,18,INK);
        } else h.smallButton(52,548,360,38,setup.drivingRule()+(setup.configurable?" · 切换":" · 模式固定"),()->{if(setup.configurable) setup.difficulty=(setup.difficulty+1)%3;});
        h.smallButton(52,597,360,38,setup.lengthRule!=null?setup.lengthRule:"正赛圈数："+setup.laps+(setup.adjustableLaps?" · 切换":" · 固定"),()->{if(setup.adjustableLaps) setup.laps=setup.laps==1?3:setup.laps==3?5:1;});
        if(setup.eventStage()!=null) h.smallButton(52,643,360,26,"核对赛事固定配置与本阶段条件",()->h.review("实际开赛条件",setup.eventStage().lines(),"返回赛前预览",()->{}));
        h.smallButton(52,679,360,46,"确定",h::confirmLaunch);h.text("下一步会再次确认，再开始游戏。",52,751,14,SOFT);
        String name=setup.preview.track().name;h.panel(480,24,752,80,new Color(12,28,40,220));h.text(setup.training?"训练场预览":name.length()>26?name.substring(0,25)+"…":name,500,60,26,INK,true);h.text("拖动背景平移 · 滚轮缩放 · 选择地图只影响预览，确认后开始",500,89,15,SOFT);
        g.setColor(new Color(200,235,220,125));g.setStroke(new BasicStroke(2));g.draw(TrackPreview.AREA);
        h.smallButton(480,685,156,38,"复原",setup.preview::reset);h.smallButton(650,685,156,38,"近看起点",setup.preview::nearStart);h.smallButton(820,685,110,38,"← 预览",()->setup.preview.pan(80,0));
        h.smallButton(944,685,64,38,"−",()->setup.preview.zoom(false,856,393));h.smallButton(1022,685,64,38,"+",()->setup.preview.zoom(true,856,393));
        h.smallButton(1100,685,132,38,"预览 →",()->setup.preview.pan(-80,0));
    }
}
