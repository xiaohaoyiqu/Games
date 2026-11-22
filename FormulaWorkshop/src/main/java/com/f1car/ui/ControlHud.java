package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.input.*;
import java.awt.Color;
import java.util.Locale;

/** Setup remains usable with keyboard/mouse even when a device mapping is incomplete. */
final class ControlHud {
    private static final Color INK=new Color(235,245,240),MUTED=new Color(162,186,189),MINT=new Color(76,235,193),GOLD=new Color(255,205,122);
    private boolean buttons;
    void render(HudRenderer h,FormulaGame app) {
        var settings=app.controls.settings();
        h.panel(70,52,1140,698,new Color(10,29,39,245));h.text("手柄与操作设置",100,97,29,INK,true);
        h.smallButton(488,66,245,38,"键盘键位设置",app::openKeyboard);
        h.smallButton(755,66,198,38,settings.enabled()?"驾驶：手柄":"驾驶：键盘",()->app.controlSettings(settings.enabled(!settings.enabled())));
        h.smallButton(973,66,207,38,buttons?"查看轴与校准":"查看按键映射",()->{buttons=!buttons;app.cancelCalibration();});
        var device=app.controllerInput.selectedDevice(settings);String name=device.map(ControllerInput.Device::displayName).orElse("未连接／尚未选择");
        if(name.length()>36) name=name.substring(0,36)+"…";
        h.text("设备："+name,102,143,18,device.isPresent()?MINT:GOLD);
        h.smallButton(973,117,207,36,"选择／切换手柄",app::selectController);
        if(app.calibration()!=null) {
            h.text("校准："+title(app.calibration().channel()),103,217,25,MINT,true);h.wrapped(app.calibration().instruction(),103,267,1000,22,INK,30);
            h.wrapped(raw(app),103,328,1000,16,MUTED,25);
            h.smallButton(103,442,465,46,"确认当前状态",app::confirmCalibration);h.smallButton(595,442,465,46,"取消校准",app::cancelCalibration);
        } else if(app.capturingButton()!=null) {
            h.text("绑定："+command(app.capturingButton()),103,225,25,MINT,true);
            h.wrapped("按下要绑定的按钮；如占用其它动作，将交换两项绑定。ESC 或点击下方按钮取消。",103,285,1000,19,INK,29);
            h.smallButton(103,442,957,46,"取消绑定",app::cancelCalibration);
        } else if(buttons) {
            int i=0;for(var command:ControlSettings.Command.values()) {
                int column=i%2,row=i/2,x=103+column*550,y=192+row*69;i++;
                h.text(command(command),x,y+23,18,INK);h.smallButton(x+225,y-3,265,39,"按钮 "+settings.buttons().get(command)+" · 重新绑定",()->app.captureControllerButton(command));
            }
            h.text("菜单用摇杆方向选择、确认键执行、返回键退出；编号以本机原始输入为准。",103,510,15,MUTED);
        } else {
            int i=0;var preview=app.controllerInput.preview(settings);
            for(var channel:ControlSettings.Channel.values()) {
                var axis=settings.axis(channel);int y=191+i++*94;
                float value=switch(channel) { case STEERING->preview.steer();case THROTTLE->preview.throttle();case BRAKE->preview.brake(); };
                boolean missing=axis.id()>=0 && device.isPresent() && !device.get().hasAxis(axis.id());
                String reading=axis.id()<0?"未校准":missing?"当前手柄无轴 "+axis.id()
                        :"轴 "+axis.id()+String.format(Locale.ROOT," · 输出 %.0f%%",value*100);
                h.text(title(channel)+" · "+reading,103,y+22,18,axis.id()<0 || missing?GOLD:INK);
                h.smallButton(103,y+36,178,32,"校准范围",()->app.beginCalibration(channel));
                h.smallButton(296,y+36,98,32,"反向",()->app.controlSettings(settings.axis(channel,axis.inverted())));
                h.text(String.format(Locale.ROOT,"死区 %.0f%%",axis.deadzone()*100),430,y+21,16,MUTED);
                h.smallButton(428,y+36,64,32,"−",()->response(app,channel,-.02f,0));h.smallButton(504,y+36,64,32,"+",()->response(app,channel,.02f,0));
                h.text(String.format(Locale.ROOT,"曲线 %.1f",axis.curve()),614,y+21,16,MUTED);
                h.smallButton(611,y+36,64,32,"−",()->response(app,channel,0,-.1f));h.smallButton(687,y+36,64,32,"+",()->response(app,channel,0,.1f));
                h.text(channel==ControlSettings.Channel.STEERING?"曲线越大，中心附近越柔和":"曲线越大，轻踩时输出越小",800,y+52,14,MUTED);
            }
            h.smallButton(103,494,319,35,"菜单横轴："+settings.menuX()+" · 切换",()->cycleMenu(app,true));
            h.smallButton(449,494,319,35,"菜单纵轴："+settings.menuY()+" · 切换",()->cycleMenu(app,false));
            h.text("菜单方向轴使用原始居中值。",800,518,14,MUTED);
        }
        String message=app.controlMessage.isEmpty()?app.controls.warning:app.controlMessage;
        String conflict=settings.calibrationConflict();
        if(!conflict.isEmpty() && !message.equals(conflict)) message=message.isEmpty()?conflict:message+"\n"+conflict;
        h.wrapped(message,103,569,1045,16,GOLD,22);
        h.text("驾驶设置在下一次尝试／新赛事阶段生效；暂停后恢复保留当前条件。",103,623,15,MUTED);
        h.text("制图画布、文字与系统文件窗口仍使用鼠标／键盘；同型号多手柄按连接顺序区分。",103,647,14,MUTED);
        if(!app.controls.dirty() && !app.controls.warning.isEmpty()) h.text("当前使用默认设置",103,703,16,MUTED);
        else h.smallButton(103,675,319,42,app.controls.dirty()?"重试保存设置":"设置已保存",()->{app.controls.flush();app.controlMessage=app.controls.warning;});
        h.smallButton(449,675,319,42,"恢复默认（键盘）",()->app.controlSettings(ControlSettings.defaults()));
        h.smallButton(795,675,385,42,"返回",app::backFromControls);
    }
    private void response(FormulaGame app,ControlSettings.Channel channel,float zone,float curve) {
        var settings=app.controls.settings();var axis=settings.axis(channel);
        float nextZone=Math.round(Math.max(0,Math.min(.4f,axis.deadzone()+zone))*100)/100f;
        float nextCurve=Math.round(Math.max(.5f,Math.min(3,axis.curve()+curve))*10)/10f;
        app.controlSettings(settings.axis(channel,axis.response(nextZone,nextCurve)));
    }
    private void cycleMenu(FormulaGame app,boolean horizontal) {
        var settings=app.controls.settings();int count=app.controllerInput.selectedDevice(settings).map(ControllerInput.Device::axes).orElse(0);
        if(count==0) return;int current=horizontal?settings.menuX():settings.menuY();app.controlSettings(settings.menu(horizontal,(current+2)%(count+1)-1));
    }
    private String raw(FormulaGame app) { return "原始轴："+app.controllerInput.raw(app.controls.settings()).entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).limit(12).map(e->String.format(Locale.ROOT,"%d = %.2f",e.getKey(),e.getValue())).collect(java.util.stream.Collectors.joining("    ")); }
    private String title(ControlSettings.Channel channel) { return switch(channel) { case STEERING->"转向";case THROTTLE->"油门";case BRAKE->"制动"; }; }
    private String command(ControlSettings.Command command) { return switch(command) { case CONFIRM->"菜单确认";case BACK->"返回";case PAUSE->"暂停／继续";case RESET->"恢复／重试";case CAMERA->"切换镜头";case HELP->"帮助";case REVERSE->"向前制动／倒车";case BOOST->"四驱车加力"; }; }
}
