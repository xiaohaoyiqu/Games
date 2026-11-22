package com.f1car.pixel;

import com.f1car.FormulaGame;
import com.f1car.model.PracticeSession;
import com.f1car.model.TrackLayout;
import com.f1car.model.TrackCatalog;
import com.f1car.model.TrackScenery;
import com.f1car.model.DriverJournal;
import com.f1car.model.CarLivery;
import com.f1car.model.PaintRegions;
import com.f1car.model.CircuitScene;
import com.f1car.model.TrackGuide;
import com.f1car.model.RaceReplay;
import com.f1car.model.ReplayPlayer;
import com.f1car.model.ExperienceTrial;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;

/** Actual 2-D tile and sprite presentation; the physics world is never rendered. */
public final class PixelScene {
    private static final int W=640,H=400;
    private static final Rectangle VIEWPORT = new Rectangle(0,0,W,H);
    private static final long MAX_MAP_PIXELS=8_000_000;
    private static final Color CAR_SHADOW = new Color(15, 25, 34, 100);
    private static final Color FOLLOWED_CAR = new Color(0xf5d784);
    private static final Color COURSE_GATE=new Color(0x85abbb);
    private static final Color COURSE_MARKER_TEXT=new Color(0xdce8b8);
    private static final Color NEXT_GATE_TEXT = new Color(0xffd279);
    private static final String[] TIME_TRIAL_MARKER_LABELS={"START","S1","S2"};
    private static final BasicStroke ACTIVE_GATE_STROKE=new BasicStroke(2),COURSE_GATE_STROKE=new BasicStroke(1);
    private static final Font COURSE_MARKER_FONT=new Font(Font.MONOSPACED,Font.BOLD,7);
    private static final AlphaComposite FADED_CAR = AlphaComposite.SrcOver.derive(.5f);
    private int mapScale=4,requestedMapScale;
    private MapAppearance appearance;
    private RaceReplay.Track mappedReplay;
    private float mapMinX=-260,mapMaxZ=270;
    private int mapWidth=2080,mapHeight=2160;
    private record TreeArea(float minX, float maxZ, int width, int depth) {}
    private TreeArea treeArea;
    private TrackLayout mappedTrack;
    private TrackLayout.Sample[] timeTrialMarkers;
    private final FormulaGame app;
    private PixelAssets assets;
    private BufferedImage circuitMap,trainingMap;
    private final BufferedImage frame=new BufferedImage(W,H,BufferedImage.TYPE_INT_RGB);
    private ReplayPlayer drawnReplay;
    private RaceReplay.Frame drawnReplayFrame;
    private final Rectangle replayDamage = new Rectangle();
    private int drawnFollow;
    private float drawnCenterX,drawnCenterZ,drawnZoom;
    private PropLayer circuitProps=PropLayer.EMPTY,trainingProps=PropLayer.EMPTY;
    private Rectangle barrierCrop;
    private float centerX,centerZ,zoom=4.5f;
    private boolean cameraReady;
    private ReplayPlayer cameraReplay;
    private long cameraCut;
    private RaceReplay.Car replayCameraSample;
    private CameraTarget replayCameraTarget;
    private final LiverySprites decoratedCars=new LiverySprites(),liveryPreview=new LiverySprites();
    private final ReplaySprites replaySprites=new ReplaySprites();
    private PaintRegionStencil regionStencil;
    private final Vector3f carPosition=new Vector3f(),carForward=new Vector3f(),carVelocity=new Vector3f();
    private final Vector3f gatePosition = new Vector3f(), gateRight = new Vector3f();
    private final Quaternion carRotation=new Quaternion();
    record Prop(float x,float z,int id,float width) {}
    private record PropLayer(List<Prop> sorted,float widest) {
        private static final PropLayer EMPTY=new PropLayer(List.of(),0);
    }
    private record MapAppearance(String geometry,CircuitScene scene,TrackCatalog.Theme theme,long seed) {}
    record CameraTarget(float x,float z,float zoom) {}

    public PixelScene(FormulaGame app,PixelAssets assets) { this.app=app; replaceAssets(assets); }
    public void replaceAssets(PixelAssets assets) {
        this.assets=Objects.requireNonNull(assets);regionStencil=null;decoratedCars.close();liveryPreview.close();replaySprites.close();
        barrierCrop = null;
        // New artwork needs fresh map pixels even while the camera is following the same track.
        if (appearance != null) releaseMaps();
        ensureMaps();
    }
    private void rebuildMaps(TrackLayout track,int requestedScale) {
        releaseMaps();
        var bounds=track.bounds();
        mapMinX = Math.min(-260, bounds.minX() - 60);
        mapMaxZ = Math.max(270, bounds.maxZ() + 60);
        float maxX = Math.max(260, bounds.maxX() + 60);
        float minZ = Math.min(-270, bounds.minZ() - 60);
        sizeMap(maxX, minZ, requestedScale);
        // Preserve the original seed coordinates even if a distant structure expands the raster.
        treeArea = new TreeArea(mapMinX, mapMaxZ, mapWidth / mapScale, mapHeight / mapScale);
        if (track.scene() != null) {
            for (var item : track.scene().items()) {
                if (!item.solid()) continue;
                // The half diagonal covers every yaw. Allow for pixel rounding and the minimum wall stroke.
                float reach = item.clearance() + 3;
                mapMinX = Math.min(mapMinX, (float)Math.floor(item.x() - reach));
                maxX = Math.max(maxX, (float)Math.ceil(item.x() + reach));
                minZ = Math.min(minZ, (float)Math.floor(item.z() - reach));
                mapMaxZ = Math.max(mapMaxZ, (float)Math.ceil(item.z() + reach));
            }
            sizeMap(maxX, minZ, mapScale);
        }
        cameraReady=false;
    }
    private void sizeMap(float maxX, float minZ, int scale) {
        do {
            mapScale = scale--;
            mapWidth = (int)Math.ceil((maxX - mapMinX) * mapScale);
            mapHeight = (int)Math.ceil((mapMaxZ - minZ) * mapScale);
        } while ((long)mapWidth * mapHeight > MAX_MAP_PIXELS && mapScale > 1);
    }
    private void ensureMaps() {
        var track=displayTrack();
        var replay=app.replayViewing()?app.replayPlayer().replay().track():null;
        int requested=app.graphicsQuality().mapScale;
        if(appearance!=null && mappedTrack==track && mappedReplay==replay && requestedMapScale==requested) return;
        MapAppearance next;
        if(replay!=null) next=new MapAppearance(track.geometryKey(),track.scene(),replay.theme(),replay.seed());
        else {
            var environment=TrackCatalog.forLayout(track);
            next=new MapAppearance(track.geometryKey(),track.scene(),environment.theme(),environment.scenerySeed());
        }
        if(requestedMapScale!=requested || !next.equals(appearance)) rebuildMaps(track,requested);
        appearance=next;
        mappedTrack=track;
        mappedReplay=replay;
        requestedMapScale=requested;
    }
    private void releaseMaps() {
        invalidateReplayRaster();
        timeTrialMarkers=null;
        if(circuitMap!=null) circuitMap.flush();if(trainingMap!=null) trainingMap.flush();circuitMap=trainingMap=null;
        circuitProps=trainingProps=PropLayer.EMPTY;
    }
    private void invalidateReplayRaster() {
        drawnReplay=null;
        drawnReplayFrame=null;
    }
    public void close() { releaseMaps();leaveReplayCamera();decoratedCars.close();liveryPreview.close();replaySprites.close();regionStencil=null;barrierCrop=null;frame.flush(); }
    public String carAtlasIdentity() {return assets.carAtlasIdentity;}
    public int carFrameSize() {return assets.definition.carFrameSize;}
    public PaintRegions paintRegions() {return assets.paintRegions!=null && assets.paintRegions.vehicle().equals(app.vehicleSpec.id)?assets.paintRegions:null;}
    public String paintRegionWarning() {return paintRegions()==null && assets.regionWarning.isEmpty()?"部位蒙版对应其他车型。":assets.regionWarning;}
    public void paintRegionOutline(Graphics2D g,int x,int y,int size,CarLivery.Target target) {if(target!=CarLivery.Target.ALL && paintRegions()!=null) {if(regionStencil==null) regionStencil=new PaintRegionStencil(paintRegions());regionStencil.outline(g,target,x,y,size);}}
    public void update(float dt) {
        ensureMaps();
        if(app.replayViewing()) {
            updateReplayCamera(app.replayPlayer(),dt);
            return;
        }
        leaveReplayCamera();
        if(app.screen==FormulaGame.Screen.GAME_SETUP && app.launchSetup()!=null) { previewCamera();return; }
        if(app.player==null) return;
        CameraTarget target;
        if(app.store.profile.camera==2 || preview()) {
            target=overviewTarget();
        } else {
            var position=app.player.position(carPosition);
            var forward=app.player.forward(carRotation,carForward);
            target=Vector3f.isValidVector(position) && Vector3f.isValidVector(forward)
                ?followTarget(position.x,position.z,forward.x,forward.z,app.player.velocity(carVelocity).length()):null;
        }
        if(target==null) {
            if(cameraReady) return;
            target=overviewTarget();
        }
        moveCamera(target,dt);
    }

    private boolean replayCameraNeedsCut(ReplayPlayer replay) {
        return !cameraReady || cameraReplay!=replay || cameraCut!=replay.cameraCutVersion();
    }

    private void updateReplayCamera(ReplayPlayer replay,float dt) {
        boolean cut=replayCameraNeedsCut(replay);
        var followed=replay.overview()?null:replay.followed();
        if(cut || followed!=replayCameraSample) {
            replayCameraSample=followed;
            replayCameraTarget=followed==null?overviewTarget():followTarget(followed.x(),followed.z(),
                    (float)Math.sin(followed.yaw()),(float)Math.cos(followed.yaw()),followed.speed());
        }
        cameraReplay=replay;
        cameraCut=replay.cameraCutVersion();
        if(cut) {
            cameraReady=false;
            if(replayCameraTarget==null) replayCameraTarget=overviewTarget();
        }
        if(replayCameraTarget!=null) moveCamera(replayCameraTarget,dt);
    }

    private void leaveReplayCamera() {
        if(cameraReplay==null) return;
        cameraReplay=null;
        replayCameraSample=null;
        replayCameraTarget=null;
        cameraReady=false;
    }

    private void moveCamera(CameraTarget target,float dt) {
        if(!cameraReady) {
            centerX=target.x();
            centerZ=target.z();
            zoom=target.zoom();
            cameraReady=true;
            return;
        }
        if(centerX==target.x() && centerZ==target.z() && zoom==target.zoom()) return;
        float blend=Float.isFinite(dt) && dt>0?1-(float)Math.exp(-dt*7):0;
        centerX+=(target.x()-centerX)*blend;centerZ+=(target.z()-centerZ)*blend;zoom+=(target.zoom()-zoom)*blend;
    }

    private CameraTarget overviewTarget() {
        if(trainingView()) return new CameraTarget(0,5,1.05f);
        var bounds=displayTrack().bounds();
        return new CameraTarget(bounds.centerX(),bounds.centerZ(),bounds.fitScale(W-40,(H-75)/verticalScale(),24));
    }

    static CameraTarget followTarget(float x,float z,float forwardX,float forwardZ,float speed) {
        float desiredX=x+forwardX*16,desiredZ=z+forwardZ*16;
        if(!Float.isFinite(desiredX) || !Float.isFinite(desiredZ) || !Float.isFinite(speed) || speed<0
                || !Float.isFinite(desiredX*desiredX+desiredZ*desiredZ)) return null;
        return new CameraTarget(desiredX,desiredZ,Math.max(3.7f,5.2f-speed*.025f));
    }
    public void resetCamera() { cameraReady=false; }
    public BufferedImage render() {
        var replay=app.replayViewing()?app.replayPlayer():null;
        if(replay==null) {
            leaveReplayCamera();
            if(replaySprites.prepared()) replaySprites.close();
        }
        ensureMaps();
        if(replay!=null && replayCameraNeedsCut(replay)) updateReplayCamera(replay,0);
        if(app.screen==FormulaGame.Screen.GAME_SETUP && app.launchSetup()!=null) previewCamera();
        boolean training = trainingView();
        if(app.graphicsQuality()==com.f1car.model.GraphicsQuality.LOW) {
            if(training && circuitMap!=null) {circuitMap.flush();circuitMap=null;circuitProps=PropLayer.EMPTY;}
            else if(!training && trainingMap!=null) {trainingMap.flush();trainingMap=null;trainingProps=PropLayer.EMPTY;}
        }
        if(training) { if(trainingMap==null) trainingMap=buildMap(true); }
        else if(circuitMap==null) circuitMap=buildMap(false);
        var sample=replay==null?null:replay.frame();
        int follow=replay==null?-1:replay.follow();
        // Compare the fixed-camera sprite placement separately from the HUD's playback time.
        boolean fixedView = replay != null && replay == drawnReplay && follow == drawnFollow
                && centerX == drawnCenterX && centerZ == drawnCenterZ && zoom == drawnZoom;
        if (fixedView) {
            findReplayDamage(drawnReplayFrame, sample, follow);
            if (replayDamage.isEmpty()) {
                drawnReplayFrame = sample;
                return frame;
            }
        }
        invalidateReplayRaster();
        Graphics2D graphics = frame.createGraphics();
        try {
            if (fixedView) graphics.clipRect(replayDamage.x,replayDamage.y,replayDamage.width,replayDamage.height);
            drawFrame(graphics,training,replay,sample,follow,fixedView ? replayDamage : VIEWPORT);
        }
        finally { graphics.dispose(); }
        if(replay!=null) {
            drawnReplay=replay;
            drawnReplayFrame=sample;
            drawnFollow=follow;
            drawnCenterX=centerX;
            drawnCenterZ=centerZ;
            drawnZoom=zoom;
        }
        return frame;
    }

    private void drawFrame(Graphics2D g,boolean training,ReplayPlayer replay,RaceReplay.Frame sample,int follow,Rectangle damage) {
        crisp(g);
        g.setColor(assets.color("grass"));g.fillRect(0,0,W,H);
        float vertical=verticalScale();
        AffineTransform mapTransform=new AffineTransform();
        mapTransform.translate(W*.5,H*.5);
        mapTransform.scale(zoom/mapScale,zoom*vertical/mapScale);
        mapTransform.translate(-(centerX-mapMinX)*mapScale,-(mapMaxZ-centerZ)*mapScale);
        g.drawImage(training?trainingMap:circuitMap,mapTransform,null);
        drawProps(g,training?trainingProps:circuitProps,vertical,damage);
        if(app.screen==FormulaGame.Screen.GAME_SETUP) return;
        if(replay!=null) { drawReplay(g,replay.replay(),sample,follow,damage);return; }
        drawWorkshop(g);drawCourse(g);drawExperience(g);drawCircuitTrial(g);
        drawTimeTrial(g);
        int size=Math.max(7,Math.round(assets.definition.carWorldSize*zoom));
        int markerX=0, markerY=-5;
        for(var racer:app.racers) {
            Vector3f p=racer.car.position(carPosition);
            if(!Vector3f.isValidVector(p)) continue;
            int x=sx(p.x),y=screenRow(p.z,centerZ,zoom,vertical);
            if(x < -size || x>W+size || y < -size || y>H+size) continue;
            var forward=racer.car.forward(carRotation,carForward);
            if(!Vector3f.isValidVector(forward)) continue;
            g.setColor(CAR_SHADOW);g.fillRect(x-size/5+2,y-size/3+3,size*2/5,size*2/3);
            double angle=Math.atan2(forward.x,forward.z*vertical);
            BufferedImage sprite=racer.car==app.player?decoratedCar(racer.car.tuning.livery(),angle):assets.car(racer.car.tuning.livery(),angle);
            g.drawImage(sprite,x-size/2,y-size/2,size,size,null);
            if(racer.car==app.player) {
                markerX=x;
                markerY=y-size/2-5;
                if(racer.car.hasCup()) drawProp(g,p.x+.7f,screenRow(p.z+.7f,centerZ,zoom,vertical),7,1.1f,damage);
            }
        }
        if (markerY > -4) {
            g.setColor(FOLLOWED_CAR);
            g.fillRect(markerX-2,markerY,5,2);
            g.fillRect(markerX-1,markerY+2,3,2);
        }
    }
    private void previewCamera() {
        var camera=app.launchSetup().preview;zoom=camera.scale()/2;centerX=camera.centerX()-((float)com.f1car.model.TrackPreview.AREA.getCenterX()/2-W*.5f)/zoom;centerZ=camera.centerZ()+((float)com.f1car.model.TrackPreview.AREA.getCenterY()/2-H*.5f)/zoom;cameraReady=true;
    }
    private TrackLayout displayTrack() { return app.screen==FormulaGame.Screen.GAME_SETUP && app.launchSetup()!=null?app.launchSetup().preview.track():app.replayViewing()?app.replayPlayer().track():app.circuit; }
    private boolean trainingView() { return app.screen==FormulaGame.Screen.GAME_SETUP && app.launchSetup()!=null?app.launchSetup().training:!app.replayViewing() && app.isTraining(); }

    private void findReplayDamage(RaceReplay.Frame previous, RaceReplay.Frame next, int follow) {
        // A rectangle with negative dimensions takes its first added point as its origin.
        replayDamage.setBounds(0, 0, -1, -1);
        if (previous == next) return;
        var before = previous.cars();
        var after = next.cars();
        if (before.size() != after.size()) {
            replayDamage.setBounds(0, 0, W, H);
            return;
        }
        int size = replayCarSize();
        for (int i = 0; i < before.size(); i++) {
            var a = before.get(i);
            var b = after.get(i);
            if (a == b) continue;
            int ax = sx(a.x()), ay = screenRow(a.z(), centerZ, zoom, 1);
            int bx = sx(b.x()), by = screenRow(b.z(), centerZ, zoom, 1);
            boolean aVisible = replaySpriteVisible(ax, ay, size);
            boolean bVisible = replaySpriteVisible(bx, by, size);
            if (!aVisible && !bVisible) continue;
            if (aVisible != bVisible || a.id() != b.id() || ax != bx || ay != by
                    || replaySpriteFaded(a) != replaySpriteFaded(b)
                    || replaySpriteDirection(a) != replaySpriteDirection(b)) {
                if (aVisible) includeReplaySprite(ax, ay, size, a.id() == follow);
                if (bVisible) includeReplaySprite(bx, by, size, b.id() == follow);
            }
        }
    }

    private void includeReplaySprite(int x, int y, int size, boolean followed) {
        int left = x - size / 2, top = y - size / 2;
        int right = Math.min(W, left + size), bottom = Math.min(H, top + size);
        left = Math.max(0, left);
        top = Math.max(0, followed ? top - 5 : top);
        if (left < right && top < bottom) {
            replayDamage.add(left, top);
            replayDamage.add(right, bottom);
        }
    }

    private int replayCarSize() { return Math.max(7, Math.round(assets.definition.carWorldSize * zoom)); }
    private int replaySpriteDirection(RaceReplay.Car car) { return PixelAssets.direction(car.yaw(), assets.definition.directions); }
    private static boolean replaySpriteFaded(RaceReplay.Car car) { return car.retired() || !car.connected(); }
    private static boolean replaySpriteVisible(int x, int y, int size) {
        // The follow marker can still be visible above a sprite just below the viewport.
        return x >= -size && x <= W + size && y >= -size && y <= H + size + 5;
    }

    private void drawReplay(Graphics2D g,RaceReplay replay,RaceReplay.Frame sample,int follow,Rectangle repaint) {
        replaySprites.prepare(replay,assets.cars,app.vehicleSpec.id,assets.carAtlasIdentity);
        int size=replayCarSize();
        var composite=g.getComposite();
        int markerX=0, markerY=-2;
        for(var car:sample.cars()) {
            int x=sx(car.x()),y=screenRow(car.z(),centerZ,zoom,1);
            if(!replaySpriteVisible(x,y,size)) continue;
            int left=x-size/2,top=y-size/2;
            if(car.id()==follow && repaint.intersects(x-2,top-5,5,2)) {
                markerX=x;
                markerY=top-5;
            }
            if(!repaint.intersects(left,top,size,size)) continue;
            var sprite=replaySprites.image(car.id(),replaySpriteDirection(car));
            boolean faded=replaySpriteFaded(car);
            if(faded) g.setComposite(FADED_CAR);
            g.drawImage(sprite,left,top,size,size,null);
            if(faded) g.setComposite(composite);
        }
        if (markerY > -2) {
            g.setColor(FOLLOWED_CAR);
            g.fillRect(markerX-2,markerY,5,2);
        }
    }
    public void showcase(Graphics2D g,int x,int y,int size) {
        int direction=app.screen==FormulaGame.Screen.GARAGE?2:1;
        BufferedImage sprite=decoratedCar(app.store.profile.tuning.livery(),direction*Math.PI*2/assets.definition.directions);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(sprite,x,y,size,size,null);
    }
    private BufferedImage decoratedCar(int livery,double angle) {
        int direction=PixelAssets.direction(angle,assets.definition.directions);
        return decoratedCars.image(assets.cars[livery],app.activeLivery(),app.journal.state().look(),direction,paintRegions());
    }
    public void liveryPreview(Graphics2D g,int x,int y,int size,CarLivery design,int quarter) {int direction=PixelAssets.direction(quarter*Math.PI/2,assets.definition.directions);var sprite=liveryPreview.image(assets.cars[app.store.profile.tuning.livery()],design,app.journal.state().look(),direction,paintRegions());g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);g.drawImage(sprite,x,y,size,size,null);}
    private void drawTimeTrial(Graphics2D g) {
        var trial=app.timeTrial;
        if(trial==null) return;
        if(timeTrialMarkers==null) {
            var track=mappedTrack;
            timeTrialMarkers=new TrackLayout.Sample[]{track.sample(0),track.sample(track.length/3),track.sample(track.length*2/3)};
        }
        g.setFont(COURSE_MARKER_FONT);
        for(int i=0;i<timeTrialMarkers.length;i++) {
            var marker=timeTrialMarkers[i];
            gate(g,marker.position(),marker.right(),mappedTrack.width*.5f,false);
            g.setColor(COURSE_MARKER_TEXT);
            g.drawString(TIME_TRIAL_MARKER_LABELS[i],sx(marker.position().x)+4,sy(marker.position().z)-5);
        }
        if(!app.store.profile.ghostEnabled) return;
        var pose=trial.ghost();
        if(pose==null) return;
        int x=sx(pose.x()),y=sy(pose.z());
        int size=Math.max(7,Math.round(assets.definition.carWorldSize*zoom));
        double angle=Math.atan2(Math.sin(pose.yaw()),Math.cos(pose.yaw())*verticalScale());
        g.setComposite(AlphaComposite.SrcOver.derive(app.store.profile.ghostOpacity));
        g.drawImage(assets.car(trial.ghostLivery(),angle),x-size/2,y-size/2,size,size,null);
        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(COURSE_MARKER_TEXT);g.drawString(trial.rivalReference()?"FRIEND":"PB",x-4,y-size/2-4);
    }
    private void drawCircuitTrial(Graphics2D g) {
        if(app.circuitTrial()==null && app.activityTrial()==null || app.racers.isEmpty() || app.racers.get(0).progress.finished()) return;
        var progress = app.racers.get(0).progress;
        progress.nextGatePositionAndRight(gatePosition, gateRight);
        gate(g, gatePosition, gateRight, app.circuit.width * .5f, true);
        nextGateLabel(g, progress.nextGate(), gatePosition.x, gatePosition.z);
    }
    private void drawExperience(Graphics2D g) {
        var trial=app.experience();if(trial==null) return;
        int index = trial.nextGate();
        float halfWidth = displayTrack().width * .5f;
        trial.gatePositionAndRight(index, gatePosition, gateRight);
        gate(g, gatePosition, gateRight, halfWidth, true);
        float nextX = gatePosition.x, nextZ = gatePosition.z;
        trial.gatePositionAndRight(trial.course().gates(), gatePosition, gateRight);
        gate(g, gatePosition, gateRight, halfWidth, false);
        nextGateLabel(g, index, nextX, nextZ);
        if(trial.state()==ExperienceTrial.State.FAILED) { var point=trial.observedPosition();int x=sx(point.x),y=sy(point.z);g.setStroke(new BasicStroke(2));g.drawLine(x-6,y-6,x+6,y+6);g.drawLine(x-6,y+6,x+6,y-6); }
        var reference=app.experienceReference();if(reference==null || !app.store.profile.ghostEnabled || trial.state()==ExperienceTrial.State.READY || trial.state()==ExperienceTrial.State.FAILED) return;
        var sample=reference.at(trial.elapsed());if(sample==null) return;int size=Math.max(7,Math.round(assets.definition.carWorldSize*zoom));
        double angle=Math.atan2(Math.sin(sample.yaw()),Math.cos(sample.yaw())*verticalScale());var composite=g.getComposite();g.setComposite(AlphaComposite.SrcOver.derive(app.store.profile.ghostOpacity));g.drawImage(assets.car(reference.livery(),angle),sx(sample.x())-size/2,sy(sample.z())-size/2,size,size,null);g.setComposite(composite);g.drawString("PB",sx(sample.x())-4,sy(sample.z())-size/2-4);
    }
    public String source() { return assets.source; }
    private boolean preview() {
        return app.screen==FormulaGame.Screen.START_MENU || app.screen==FormulaGame.Screen.GAME_SETUP || app.screen==FormulaGame.Screen.MINI_HOME || app.screen==FormulaGame.Screen.EXPERIENCE || app.screen==FormulaGame.Screen.CONTROLS || app.screen==FormulaGame.Screen.WORKSHOP || app.screen==FormulaGame.Screen.TUNING_LAB || app.screen==FormulaGame.Screen.MENU || app.screen==FormulaGame.Screen.GARAGE
                || app.screen==FormulaGame.Screen.COURSES || app.screen==FormulaGame.Screen.CHALLENGES || app.screen==FormulaGame.Screen.BOOK || app.screen==FormulaGame.Screen.JOURNAL || app.screen==FormulaGame.Screen.EVENTS || app.screen==FormulaGame.Screen.TRACKS || app.screen==FormulaGame.Screen.MAP_EDITOR || app.screen==FormulaGame.Screen.SETTINGS || app.screen==FormulaGame.Screen.RACE_ROOM || app.screen==FormulaGame.Screen.RACE_SETUP;
    }
    private float verticalScale() { return !app.replayViewing() && !preview() && app.store.profile.camera==1 ? .72f : 1; }
    private int sx(float x) { return Math.round(W*.5f+(x-centerX)*zoom); }
    private int sy(float z) {return screenRow(z,centerZ,zoom,verticalScale());}
    private static int screenRow(float z,float centerZ,float zoom,float vertical) {
        return Math.round(H*.5f-(z-centerZ)*zoom*vertical);
    }
    private int mx(float x) { return Math.round((x-mapMinX)*mapScale); }
    private int my(float z) { return Math.round((mapMaxZ-z)*mapScale); }
    public static void crisp(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
    }
    private TexturePaint tile(int index) {
        return new TexturePaint(assets.tiles[index],new Rectangle(0,0,assets.definition.tileSize,assets.definition.tileSize));
    }
    private BufferedImage buildMap(boolean training) {
        BufferedImage map = new BufferedImage(mapWidth,mapHeight,BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = map.createGraphics();
        try {
            var props = new ArrayList<Prop>();
            paintMap(graphics, displayTrack(), training, props);
            float widest=0;
            for(var prop:props) widest=Math.max(widest,prop.width());
            var layer=new PropLayer(List.copyOf(props),widest);
            if(training) trainingProps=layer;
            else circuitProps=layer;
        } finally {
            graphics.dispose();
        }
        return map;
    }

    private void paintMap(Graphics2D g, TrackLayout track, boolean training, List<Prop> props) {
        crisp(g);g.setPaint(tile(0));g.fillRect(0,0,mapWidth,mapHeight);
        var environment=appearance;
        var guide=training?null:TrackGuide.forTrack(track);
        if(!training && environment.theme()==TrackCatalog.Theme.PARK) { g.setColor(new Color(127,167,125,96));g.fillRect(0,0,mapWidth,mapHeight); }
        if(!training && environment.theme()==TrackCatalog.Theme.STREET) { g.setPaint(tile(4));g.fillRect(0,0,mapWidth,mapHeight); }
        float waterEdge=track.bounds().minZ()-65;
        if(training) {
            g.setPaint(tile(4));g.fillRect(mx(-125),my(140),250*mapScale,300*mapScale);
            g.setPaint(tile(1));g.fillRect(mx(-74),my(80),18*mapScale,210*mapScale);
            g.setColor(assets.color("roadEdge"));
            for(float x:new float[]{-73,-57}) g.fillRect(mx(x),my(80),2,210*mapScale);
            g.setColor(new Color(0x39866c));g.fillRect(mx(-71),my(38),12*mapScale,20*mapScale);
            g.setColor(new Color(0x78dfac));g.setStroke(new BasicStroke(2));g.drawRect(mx(-71),my(38),12*mapScale,20*mapScale);
            g.setColor(assets.color("mark"));g.fillRect(mx(-72),my(-18)-2,14*mapScale,4);
            var circle=TrackLayout.circle(false);
            road(g,circle,10,false);
            for(int i=0;i<16;i++) { var s=circle.sample(i*circle.length/16);Vector3f p=s.position().add(s.right().mult(5.8f));props.add(new Prop(p.x,p.z,0,1.6f)); }
            for(var gate:PracticeSession.SLALOM_GATES) {
                float left=gate.x()-gate.halfWidth(),right=gate.x()+gate.halfWidth();
                g.setColor(new Color(0x7cbed0));g.fillRect(mx(left),my(gate.z()),mx(right)-mx(left),2);
                props.add(new Prop(left,gate.z(),0,1.6f));props.add(new Prop(right,gate.z(),0,1.6f));
            }
            for(float z:new float[]{-115,-18,50}) for(float x:new float[]{-75,-55}) props.add(new Prop(x,z,0,1.6f));
            for(int i=0;i<8;i++) barrier(g,new Vector3f(-117,0,-115+i*28),Vector3f.UNIT_Z,20,1.2f);
            label(g,"STOP",-70,29,3);label(g,"BRAKE",-73,-9,2);label(g,"SLALOM",-9,-95,2);label(g,"CIRCLE",42,20,2);
        } else {
            if(environment.theme()==TrackCatalog.Theme.HARBOR || environment.theme()==TrackCatalog.Theme.STREET) { g.setPaint(tile(3));g.fillRect(0,my(waterEdge),mapWidth,mapHeight); }
            road(g,track,track.width,true);
            var start=track.sample(0);
            finishLine(g, track, start);
            label(g,"START",start.position().x-8,start.position().z-7,2);
            for(var wall:TrackScenery.pitWalls(track)) barrier(g,wall.position(),wall.tangent(),wall.length(),wall.width());
            for(var building:TrackScenery.buildings(track)) building(g,building);
            if(track.scene()==null) {
                for(int i=0;i<6;i++) {var p=TrackScenery.paddock(track,i);if(TrackScenery.clearOfRoad(track,p,12)) props.add(new Prop(p.x,p.z,5,18));}
                for(int i=0;i<4;i++) {var s=track.sample(track.length*(.67f+i*.025f));var p=s.position().add(s.right().mult(track.width*.5f+24));if(TrackScenery.clearOfRoad(track,p,12)) props.add(new Prop(p.x,p.z,3,16));}
            } else for(var item:track.scene().items()) if(!item.solid()) props.add(new Prop(item.x(),item.z(),item.kind()==CircuitScene.Kind.TREE?1:6,item.kind()==CircuitScene.Kind.TREE?7:3));
            for(int i=0;i<12;i++) {var s=track.sample(i*track.length/12);Vector3f p=s.position().add(s.right().mult(track.width*.5f+4));props.add(new Prop(p.x,p.z,6,3));}
            for(var marker:guide.markers()) distanceBoard(g,marker);
        }
        Random random=new Random(training?623:environment.seed());
        for(int i=0;i<(!training && environment.theme()==TrackCatalog.Theme.STREET?0:!training && environment.theme()==TrackCatalog.Theme.PARK?75:140);i++) {
            float x=treeArea.minX()+25+random.nextFloat()*(treeArea.width()-50);
            float z=treeArea.maxZ()-25-random.nextFloat()*(treeArea.depth()-50);
            if(training ? Math.abs(x)<135 && Math.abs(z+10)<165 : track.project(new Vector3f(x,0,z)).separation()<22 || environment.theme()==TrackCatalog.Theme.HARBOR && z<waterEdge+10) continue;
            if(guide!=null && guide.closeToMarker(x,z)) continue;
            props.add(new Prop(x,z,1,7));
        }
        props.sort(Comparator.comparingDouble(p->-p.z));
    }

    private void finishLine(Graphics2D g, TrackLayout track, TrackLayout.Sample start) {
        Graphics2D finish = (Graphics2D)g.create();
        try {
            finish.translate(mx(start.position().x), my(start.position().z));
            finish.rotate((float)Math.atan2(start.tangent().x, start.tangent().z));
            int columns = (int)Math.ceil(track.width);
            float cell = track.width / columns;
            for (int x = 0; x < columns; x++) for (int y = 0; y < 2; y++) {
                finish.setColor(new Color((x+y)%2==0?0xe3e3c4:0x192637));
                finish.fillRect(Math.round((x * cell - track.width * .5f) * mapScale), y * 4, (int)Math.ceil(cell * mapScale), 4);
            }
        } finally {
            finish.dispose();
        }
    }
    private void distanceBoard(Graphics2D g,TrackGuide.Marker marker) {
        var position=marker.position();
        int x=mx(position.x),y=my(position.z);
        g.setColor(new Color(0x294449));g.fillRect(x-2,y+5,4,8);
        g.setColor(new Color(0xf0e9cd));g.fillRect(x-12,y-7,24,14);g.setColor(new Color(0x294449));g.drawRect(x-12,y-7,24,14);
        g.setFont(new Font(Font.MONOSPACED,Font.BOLD,10));String value=Integer.toString(marker.meters());g.drawString(value,x-g.getFontMetrics().stringWidth(value)/2,y+4);
    }
    private void building(Graphics2D g,TrackScenery.Wall building) {
        var roof = (Graphics2D)g.create();
        try {
            var position=building.position();
            var tangent=building.tangent();
            roof.translate(mx(position.x),my(position.z));roof.rotate(Math.atan2(-tangent.z,tangent.x));
            int length=Math.round(building.length()*mapScale),width=Math.round(building.width()*mapScale),x=-length/2,y=-width/2;
            roof.setColor(new Color(0x355660));roof.fillRect(x,y,length,width);roof.setColor(new Color(0x92a6a5));roof.drawRect(x,y,length,width);
            roof.setColor(new Color(0x607d83));for(int at=x+8;at<x+length-4;at+=12) roof.drawLine(at,y+3,at,y+width-3);
            roof.setColor(new Color(0xe4cb8c));roof.fillRect(x+3,y+3,Math.min(16,length-6),Math.max(2,width-6));
        } finally {
            roof.dispose();
        }
    }
    private void road(Graphics2D g,TrackLayout track,float width,boolean kerbs) {
        Path2D.Float path=new Path2D.Float();
        for(int i=0;i<track.points.size();i++) {Vector3f p=track.points.get(i);if(i==0)path.moveTo(mx(p.x),my(p.z));else path.lineTo(mx(p.x),my(p.z));}
        g.setStroke(new BasicStroke((width+2)*mapScale,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g.setColor(assets.color("roadEdge"));g.draw(path);
        if(kerbs) {
            g.setStroke(new BasicStroke((width+2)*mapScale,BasicStroke.CAP_BUTT,BasicStroke.JOIN_ROUND,1,new float[]{16*mapScale,16*mapScale},0));g.setColor(assets.color("kerb"));g.draw(path);
        }
        g.setStroke(new BasicStroke(width*mapScale,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g.setPaint(tile(1));g.draw(path);
        g.setStroke(new BasicStroke(1));
    }
    private void barrier(Graphics2D g,Vector3f position,Vector3f tangent,float length,float width) {
        // Project the same static wall footprint used by WorldScene; art never defines collision size.
        BufferedImage sprite=assets.props[2];
        if (barrierCrop == null) barrierCrop = visibleBounds(sprite);
        var crop = barrierCrop;
        if (crop.isEmpty()) return;
        Graphics2D wall = (Graphics2D)g.create();
        try {
            wall.translate(mx(position.x),my(position.z));
            wall.rotate(Math.atan2(-tangent.z,tangent.x));
            int w=Math.round(length*mapScale),h=Math.max(3,Math.round(width*mapScale));
            wall.drawImage(sprite,-w/2,-h/2,-w/2+w,-h/2+h,crop.x,crop.y,crop.x+crop.width,crop.y+crop.height,null);
        } finally {
            wall.dispose();
        }
    }

    private static Rectangle visibleBounds(BufferedImage sprite) {
        int left=sprite.getWidth(),top=sprite.getHeight(),right=-1,bottom=-1;
        for(int y=0;y<sprite.getHeight();y++) for(int x=0;x<sprite.getWidth();x++) {
            if((sprite.getRGB(x,y)>>>24)==0) continue;
            left=Math.min(left,x);right=Math.max(right,x);top=Math.min(top,y);bottom=Math.max(bottom,y);
        }
        return right < left ? new Rectangle() : new Rectangle(left, top, right - left + 1, bottom - top + 1);
    }
    private void drawProps(Graphics2D g,PropLayer layer,float vertical,Rectangle damage) {
        var props=layer.sorted();
        if(props.isEmpty()) return;
        int widest=propSize(layer.widest());
        int maximumHeight=propHeight(widest);
        // Only the foot extends below the anchor. The rest of a tall prop can reach up into the repaint.
        int maximumFoot=propFoot(widest,maximumHeight);
        int first=firstPropAtOrBelow(props,centerZ,zoom,vertical,damage.y-maximumFoot+1);
        int end=damage.y+damage.height+maximumHeight-1;
        for(int index=first;index<props.size();index++) {
            var prop=props.get(index);
            int y=screenRow(prop.z(),centerZ,zoom,vertical);
            if(y>=end) break;
            drawProp(g,prop.x(),y,prop.id(),prop.width(),damage);
        }
    }

    /** Props are sorted by descending world Z, so their rounded screen rows are non-decreasing. */
    static int firstPropAtOrBelow(List<Prop> props,float centerZ,float zoom,float vertical,int row) {
        int low=0,high=props.size();
        while(low<high) {
            int middle=(low+high)/2;
            if(screenRow(props.get(middle).z(),centerZ,zoom,vertical)<row) low=middle+1;
            else high=middle;
        }
        return low;
    }

    private int propSize(float worldWidth) {return Math.max(3,Math.round(worldWidth*zoom));}
    private int propHeight(int size) {return Math.max(1,Math.round(size*assets.definition.propHeight/(float)assets.definition.propWidth));}

    private static int propFoot(int width,int height) {return Math.min(height,Math.max(1,width/6));}

    private void drawProp(Graphics2D g,float x,int py,int id,float worldWidth,Rectangle damage) {
        int size=propSize(worldWidth),height=propHeight(size);
        int left=sx(x)-size/2;
        int top=py-height+propFoot(size,height);
        if(damage.intersects(left,top,size,height)) g.drawImage(assets.props[id],left,top,size,height,null);
    }
    private void drawCourse(Graphics2D g) {
        var practice=app.workshop()!=null?app.workshop().flow():app.tuningTrial()!=null?app.tuningTrial().flow():app.practice;
        if(app.tuningTrial()!=null && app.tuningTrial().exercise()==com.f1car.model.TuningTrial.Exercise.BRAKING && !app.tuningTrial().complete()) drawGate(g,-65,-18,4,0,true);
        if(practice==null || practice.complete()) return;
        var lesson=practice.lesson;
        var guides=practice.guideGates();
        if(lesson==PracticeSession.Lesson.SLALOM) {
            drawGuideGate(g,guides.get(Math.min(guides.size()-1,practice.gates)),true);
            return;
        }
        int first=lesson==PracticeSession.Lesson.CORNER || lesson==PracticeSession.Lesson.FLOW?practice.gates:0;
        for(int i=first;i<guides.size();i++) drawGuideGate(g,guides.get(i),i==first && !lesson.circle());
    }
    private void drawWorkshop(Graphics2D g) {
        var trial=app.workshop();if(trial==null) return;
        if(trial.kind()==com.f1car.model.WorkshopSession.Kind.PARK) {
            var zone=new Path2D.Float();zone.moveTo(sx(-69),sy(18));zone.lineTo(sx(-61),sy(18));zone.lineTo(sx(-61),sy(38));zone.lineTo(sx(-69),sy(38));zone.closePath();
            g.setColor(new Color(76,235,193,65));g.fill(zone);g.setColor(new Color(76,235,193));g.setStroke(new BasicStroke(2));g.draw(zone);int x=sx(-65),y=sy(28);g.drawLine(x-4,y,x+4,y);g.drawLine(x,y-4,x,y+4);
        } else if(trial.lap()!=null && !trial.complete()) {
            trial.lap().nextGatePositionAndRight(gatePosition, gateRight);
            gate(g, gatePosition, gateRight, displayTrack().width * .5f, true);
        }
    }
    private void drawGuideGate(Graphics2D g,PracticeSession.GuideGate gate,boolean active) {
        drawGate(g,gate.x(),gate.z(),gate.rightX()*gate.halfWidth(),gate.rightZ()*gate.halfWidth(),active);
    }
    private void nextGateLabel(Graphics2D g, int index, float x, float z) {
        g.setColor(NEXT_GATE_TEXT);
        g.setFont(COURSE_MARKER_FONT);
        g.drawString("NEXT " + index, sx(x) + 4, sy(z) - 5);
    }
    private void gate(Graphics2D g,Vector3f center,Vector3f right,float halfWidth,boolean active) {
        drawGate(g,center.x,center.z,right.x*halfWidth,right.z*halfWidth,active);
    }
    private void drawGate(Graphics2D g,float centerX,float centerZ,float x,float z,boolean active) {
        g.setColor(active?FOLLOWED_CAR:COURSE_GATE);
        g.setStroke(active?ACTIVE_GATE_STROKE:COURSE_GATE_STROKE);
        g.drawLine(sx(centerX+x),sy(centerZ+z),sx(centerX-x),sy(centerZ-z));
    }
    private void label(Graphics2D g,String text,float x,float z,int scale) {
        g.setFont(new Font(Font.MONOSPACED,Font.BOLD,6*scale));g.setColor(new Color(0xd7e2c1));g.drawString(text,mx(x),my(z));
    }
}
