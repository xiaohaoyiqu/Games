package com.f1car;

import com.jme3.system.AppSettings;
import com.jme3.system.JmeContext;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Main {
    public static String version() {
        String version=Main.class.getPackage().getImplementationVersion();return version==null?"开发构建":version;
    }
    public static void main(String[] args) {
        boolean headless = false, smoke = false, lowSpec=false;
        String scenario = "menu";
        Path data = Path.of("userdata"), captures = null, assets = Path.of("assets/pixel");
        for (String arg : args) {
            if (arg.equals("--headless")) headless = true;
            else if(arg.equals("--low-spec")) lowSpec=true;
            else if (arg.equals("--smoke")) smoke = true;
            else if (arg.startsWith("--data-dir=")) data = Path.of(arg.substring(11));
            else if (arg.startsWith("--captures=")) captures = Path.of(arg.substring(11));
            else if (arg.startsWith("--scenario=")) scenario = arg.substring(11);
            else if (arg.startsWith("--assets-dir=")) assets = Path.of(arg.substring(13));
        }
        Logger.getLogger("com.jme3.bullet").setLevel(Level.WARNING);
        Logger.getLogger("com.jme3.asset").setLevel(Level.SEVERE);
        AppSettings settings = new AppSettings(true);
        settings.setTitle("Formula Workshop "+version()+" | 2D 像素方程式");
        System.out.println("Formula Workshop "+version());
        settings.setResolution(1280, 800);
        settings.setResizable(true);
        settings.setFrameRate(smoke && headless ? 240 : 60);
        settings.setVSync(!smoke);
        settings.setSamples(0);
        settings.setGammaCorrection(false);
        settings.setUseJoysticks(!headless);
        settings.setSettingsDialogImage(null);
        if (headless) settings.setAudioRenderer(null);
        FormulaGame game = new FormulaGame(data, captures, assets, headless, smoke, scenario);
        if(lowSpec) { game.store.profile.graphicsQuality=com.f1car.model.GraphicsQuality.LOW;settings.setResolution(960,600); }
        if(!smoke) settings.setFrameRate(game.graphicsQuality().frameRate);
        game.setSettings(settings);
        game.setPauseOnLostFocus(!smoke);
        game.setShowSettings(false);
        game.start(headless ? JmeContext.Type.Headless : JmeContext.Type.Display);
    }
}
