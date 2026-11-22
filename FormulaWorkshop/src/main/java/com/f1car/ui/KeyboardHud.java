package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.input.KeyboardSettings;
import java.awt.Color;

final class KeyboardHud {
    private static final Color INK=new Color(235,245,240),SOFT=new Color(162,186,189),MINT=new Color(76,235,193),AMBER=new Color(255,190,114);
    void render(HudRenderer h,FormulaGame app) {
        h.panel(60,35,1160,730,new Color(10,29,39,246));h.text("KEYBOARD CONTROLS",92,76,14,MINT,true);h.text("键盘驾驶按键",92,118,30,INK,true);
        h.smallButton(1000,66,184,42,"返回",app::backFromKeyboard);
        h.text("点击主要或备用按键后，按下新键。菜单确认与返回固定为 ENTER / ESC。",92,154,17,SOFT);
        int index=0;
        for(var command:KeyboardSettings.Command.values()) {
            int x=92+(index%2)*552,y=197+(index/2)*77;index++;
            var binding=app.keyboard.settings().bindings().get(command);
            h.text(command.title,x,y+23,18,INK,true);
            h.smallButton(x+151,y-2,169,39,KeyboardSettings.name(binding.primary()),()->app.captureKeyboardKey(command,false));
            h.smallButton(x+331,y-2,169,39,KeyboardSettings.name(binding.alternate()),()->app.captureKeyboardKey(command,true));
        }
        h.wrapped(app.keyboardMessage,92,606,1080,16,AMBER,24);
        h.text("重复分配会提示冲突；F6、F12 与组合键修饰键保留。编辑器快捷键使用原绑定。",92,655,15,SOFT);
        h.smallButton(92,693,322,40,"恢复默认键位",()->h.confirm("恢复默认键位？","将恢复 W/A/S/D、方向键及原有驾驶快捷键。",()->app.setKeyboard(KeyboardSettings.defaults())));
        if(!app.keyboard.dirty() && !app.keyboard.warning.isEmpty()) h.text("当前使用默认键位",440,720,16,SOFT);
        else h.smallButton(440,693,344,40,app.keyboard.dirty()?"重试保存键位":"键位已保存",()->{app.keyboard.flush();app.keyboardMessage=app.keyboard.warning;});
        if(app.capturingKey()!=null) {
            h.clearButtons();
            h.panel(220,267,840,242,new Color(18,48,58,255));h.text("绑定："+app.capturingKey().title,255,313,28,MINT,true);
            h.wrapped(app.keyboardMessage,255,358,765,18,INK,28);
            h.smallButton(255,439,770,43,"取消绑定  ESC",app::cancelKeyboardCapture);
        }
    }
}
