package com.f1car.scene;

import com.f1car.model.PracticeSession;
import com.f1car.model.TrackLayout;
import com.f1car.model.TrackScenery;
import com.f1car.model.TrackCatalog;
import com.f1car.model.CircuitScene;
import com.f1car.model.TrackGuide;
import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.bullet.objects.PhysicsRigidBody;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import jme3tools.optimize.GeometryBatchFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class WorldScene implements AutoCloseable {
    public final Node node = new Node("world");
    private final PhysicsSpace space;
    private final List<PhysicsRigidBody> solids = new ArrayList<>();
    public final boolean training;
    private final SceneFactory f;

    public WorldScene(SceneFactory factory, PhysicsSpace space, TrackLayout track, boolean training) {
        this.f = factory; this.space = space; this.training = training;
        f.box(node,"ground",0,-.5f,0,650,.45f,650,training?0x668a75:TrackCatalog.forLayout(track).theme()==TrackCatalog.Theme.STREET?0x777e7b:TrackCatalog.forLayout(track).theme()==TrackCatalog.Theme.PARK?0x7fa77d:0x719381);
        solid(new Vector3f(0, -.5f, 0), new Vector3f(650, .45f, 650));
        if (training) trainingGround(); else circuit(track);
        scenery(track);
        GeometryBatchFactory.optimize(node);
        node.setShadowMode(com.jme3.renderer.queue.RenderQueue.ShadowMode.CastAndReceive);
    }

    private void circuit(TrackLayout track) {
        var road=new RoadRibbon(track);
        ribbon(road, -track.width * .5f - 1.2f, track.width * .5f + 1.2f, -.005f, 0x4f645d, false);
        ribbon(road, -track.width * .5f, track.width * .5f, .015f, 0x37464d, false);
        for (int side : new int[]{-1, 1}) {
            float edge = side * (track.width * .5f - .18f);
            ribbon(road, edge - .12f, edge + .12f, .027f, 0xdce9de, false);
            float curb = side * (track.width * .5f + .4f);
            ribbon(road, curb - .45f, curb + .45f, .035f, 0xe9e4d4, true);
        }
        var start = track.sample(0);
        Node finish = new Node("finish line");
        finish.setLocalTranslation(start.position());
        finish.setLocalRotation(new Quaternion().fromAngleAxis((float) Math.atan2(start.tangent().x, start.tangent().z), Vector3f.UNIT_Y));
        node.attachChild(finish);
        int columns=(int)Math.ceil(track.width);float cell=track.width/columns;
        for (int i = 0; i < columns; i++) for (int j = 0; j < 2; j++) {
            f.box(finish, "chequer", -track.width*.5f+cell*(i+.5f), .035f, j * .8f, cell*.5f, .012f, .4f, (i + j) % 2 == 0 ? 0xe9f0e3 : 0x18252d);
        }
        for (float side : new float[]{-track.width*.5f-2.5f, track.width*.5f+2.5f}) {
            f.box(finish, "gantry post", side, 3.5f, 0, .22f, 3.5f, .22f, 0x203943);
        }
        f.box(finish, "gantry", 0, 7.2f, 0, track.width*.5f+2.8f, .75f, .35f, 0x17323f);
        for (int i = 0; i < 5; i++) f.box(finish, "start light", -2.4f + i * 1.2f, 7.1f, .38f, .26f, .26f, .035f, 0xf4644e);
        for (int i = 1; i <= 12; i++) {
            var s = track.sample(i * track.length / 12);
            for (int side : new int[]{-1, 1}) {
                Vector3f p = s.position().add(s.right().mult(side * (track.width * .5f + 1.3f)));
                f.box(node, "distance marker", p.x, 1, p.z, .25f, 1, .25f, i % 3 == 0 ? 0xffcc68 : 0xd4e8df);
            }
        }
        int wallIndex=0;
        for (var wall:TrackScenery.pitWalls(track)) {
            Vector3f p=wall.position();
            Vector3f tangent=wall.tangent();
            Quaternion rotation = new Quaternion().fromAngleAxis((float) Math.atan2(tangent.x, tangent.z), Vector3f.UNIT_Y);
            f.box(node, "pit wall", p.x, .65f, p.z, wall.width()*.5f, .65f, wall.length()*.5f, wallIndex++ % 2 == 0 ? 0xdbe8dd : 0x18b59b).setLocalRotation(rotation);
            solid(new Vector3f(p.x, .65f, p.z), new Vector3f(wall.width()*.5f, .65f, wall.length()*.5f)).setPhysicsRotation(rotation);
        }
        for(var marker:TrackGuide.forTrack(track).markers()) {
            Node board=new Node("guide-"+marker.section()+"-"+marker.meters());board.setLocalTranslation(marker.position());
            var tangent=marker.tangent();
            board.setLocalRotation(new Quaternion().fromAngleAxis((float)Math.atan2(tangent.x,tangent.z),Vector3f.UNIT_Y));node.attachChild(board);
            f.box(board,"guide post",0,1,0,.14f,1,.14f,0x294449);
            f.box(board,"distance board",0,2,0,3,.9f,.12f,0xf0e9cd);
        }
        for(var building:TrackScenery.buildings(track)) {
            var p=building.position();
            var tangent=building.tangent();
            var rotation=new Quaternion().fromAngleAxis((float)Math.atan2(tangent.x,tangent.z),Vector3f.UNIT_Y);
            f.box(node,"low warehouse",p.x,1.5f,p.z,building.width()*.5f,1.5f,building.length()*.5f,0x7c9296).setLocalRotation(rotation);
            f.box(node,"warehouse roof",p.x,3.08f,p.z,building.width()*.5f,.08f,building.length()*.5f,0x355660).setLocalRotation(rotation);
            solid(new Vector3f(p.x,1.58f,p.z),new Vector3f(building.width()*.5f,1.58f,building.length()*.5f)).setPhysicsRotation(rotation);
        }
    }

    private void trainingGround() {
        f.box(node, "training apron", 0, -.015f, -10, 125, .025f, 150, 0x43535b);
        for (float x : new float[]{-73, -57}) f.box(node, "braking lane", x, .024f, -25, .12f, .015f, 105, 0xd6e7dc);
        f.box(node, "target parking", -65, .023f, 28, 6, .015f, 10, 0x207d6c);
        for (float x : new float[]{-71, -59}) f.box(node, "parking outline", x, .043f, 28, .13f, .01f, 10, 0x5cf2c0);
        for (float z : new float[]{18, 38}) f.box(node, "parking outline", -65, .043f, z, 6, .01f, .13f, 0x5cf2c0);
        f.box(node, "speed entry", -65, .045f, -18, 7, .015f, .4f, 0xf4cb65);
        for (int i = 0; i < 9; i++) {
            f.box(node, "braking marker", -74, .07f, -70 + i * 12, .6f, .02f, .18f, 0xdce9de);
        }
        TrackLayout circle = TrackLayout.circle(false);
        var road=new RoadRibbon(circle);
        ribbon(road, -5, 5, .045f, 0x2d3e47, false);
        ribbon(road, -5.1f, -4.9f, .06f, 0x74d5cb, false);
        ribbon(road, 4.9f, 5.1f, .06f, 0x74d5cb, false);
        for (int i = 0; i < 16; i++) {
            var s = circle.sample(i * circle.length / 16);
            Vector3f p = s.position().add(s.right().mult(5.8f));
            f.cone(node, p.x, p.z);
        }
        for (var gate : PracticeSession.SLALOM_GATES) {
            f.cone(node, gate.x()-gate.halfWidth(), gate.z()); f.cone(node, gate.x()+gate.halfWidth(), gate.z());
            f.box(node, "slalom gate", gate.x(), .05f, gate.z(), gate.halfWidth()-.1f, .015f, .22f, 0x65b9db);
        }
        for (float z : new float[]{-115, -18, 50}) for (float x : new float[]{-75, -55}) f.cone(node, x, z);
        for (int i = 0; i < 8; i++) {
            f.box(node, "training barrier", -117, .55f, -115 + i * 28, .6f, .55f, 10, i % 2 == 0 ? 0xf2ebd7 : 0x17ae98);
            solid(new Vector3f(-117, .55f, -115 + i * 28), new Vector3f(.6f, .55f, 10));
        }
    }

    private void ribbon(RoadRibbon road,float left,float right,float y,int rgb,boolean alternating) {
        var mesh=road.mesh(left,right,y,alternating);
        if(mesh.primary().length>0) f.triangles(node,"road ribbon",mesh.positions(),mesh.primary(),rgb);
        if(mesh.alternate().length>0) f.triangles(node,"red kerb",mesh.positions(),mesh.alternate(),0xe26a5a);
    }

    static final class RoadRibbon {
        record MeshData(float[] positions,int[] primary,int[] alternate) {}

        private final List<Vector3f> points;
        private final float[] rightX,rightZ;

        RoadRibbon(TrackLayout track) {
            points=List.copyOf(track.points);
            rightX=new float[points.size()];
            rightZ=new float[points.size()];
            int last=points.size()-1;
            var tangent=new Vector3f();
            for(int i=0;i<points.size();i++) {
                int before=track.closed?Math.floorMod(i-1,last):Math.max(0,i-1);
                int after=track.closed?(i+1)%last:Math.min(last,i+1);
                tangent.set(points.get(after)).subtractLocal(points.get(before)).normalizeLocal();
                rightX[i]=tangent.z;
                rightZ[i]=-tangent.x;
            }
        }

        MeshData mesh(float left,float right,float y,boolean alternating) {
            var positions=new float[points.size()*6];
            for(int i=0;i<points.size();i++) {
                var point=points.get(i);
                int offset=i*6;
                positions[offset]=point.x+rightX[i]*left;
                positions[offset+1]=y;
                positions[offset+2]=point.z+rightZ[i]*left;
                positions[offset+3]=point.x+rightX[i]*right;
                positions[offset+4]=y;
                positions[offset+5]=point.z+rightZ[i]*right;
            }

            int segments=points.size()-1;
            int alternateSegments=alternating?2*(segments/4)+Math.min(2,segments%4):0;
            var primary=new int[(segments-alternateSegments)*6];
            var alternate=new int[alternateSegments*6];
            int primaryOffset=0,alternateOffset=0;
            for(int i=0;i<segments;i++) {
                boolean red=alternating && i%4<2;
                var indices=red?alternate:primary;
                int offset=red?alternateOffset:primaryOffset;
                int near=2*i,far=near+2;
                indices[offset]=near;
                indices[offset+1]=far;
                indices[offset+2]=near+1;
                indices[offset+3]=near+1;
                indices[offset+4]=far;
                indices[offset+5]=far+1;
                if(red) alternateOffset+=6;
                else primaryOffset+=6;
            }
            return new MeshData(positions,primary,alternate);
        }
    }

    private void scenery(TrackLayout track) {
        if(!training && track.scene()!=null) {
            for(var item:track.scene().items()) {
                if(item.kind()==CircuitScene.Kind.TREE) {
                    f.cylinder(node,"custom tree trunk",item.x(),1.5f,item.z(),.4f,3,0x78694d);f.box(node,"custom tree crown",item.x(),4,item.z(),2.4f,2.1f,2.4f,0x397c66);
                } else if(item.kind()==CircuitScene.Kind.SIGN) {
                    f.box(node,"custom sign post",item.x(),1,item.z(),.15f,1,.15f,0x203943);
                    f.box(node,"custom sign",item.x(),2,item.z(),1.2f,.6f,.15f,0xf5d784).setLocalRotation(new Quaternion().fromAngleAxis(item.yaw(),Vector3f.UNIT_Y));
                }
            }
            if(track.scene().theme()==TrackCatalog.Theme.STREET) f.box(node,"dock water",0,-.13f,track.bounds().minZ()-195,650,.03f,130,0x73b4bd);
            return;
        }
        var environment=TrackCatalog.forLayout(track);boolean park=environment.theme()==TrackCatalog.Theme.PARK;
        var guide=training?null:TrackGuide.forTrack(track);
        Random random=new Random(training?8142:environment.scenerySeed());
        for(int i=0;i<(park && !training?35:65);i++) {
            float x = random.nextFloat() * 560 - 280, z = random.nextFloat() * 560 - 280;
            if ((training && Math.abs(x) < 135 && Math.abs(z + 10) < 160) || (!training && track.project(new Vector3f(x, 0, z)).separation() < 22)) continue;
            if(guide!=null && guide.closeToMarker(x,z)) continue;
            f.cylinder(node, "tree trunk", x, 1.5f, z, .4f, 3, 0x78694d);
            f.box(node, "tree crown", x, 4, z, 2.4f, 2.1f + random.nextFloat(), 2.4f, i % 2 == 0 ? 0x397c66 : 0x477c68);
        }
        if(environment.theme()==TrackCatalog.Theme.HARBOR)
            f.box(node, "water", 0, -.13f, track.bounds().minZ()-195, 650, .03f, 130, 0x73b4bd);
        for (int i = 0; i < 6; i++) {
            var p=TrackScenery.paddock(track,i);
            if(!TrackScenery.clearOfRoad(track,p,12)) continue;
            f.box(node, "paddock hall", p.x, 3, p.z, 9, 3, 7, 0xd1d8ca);
            f.box(node, "paddock roof", p.x, 6.2f, p.z, 10, .25f, 8, 0x21404a);
            f.box(node, "garage door", p.x, 2, p.z+7.2f, 6, 1.9f, .15f, 0x497079);
        }
        for (int row = 0; row < 5; row++) {
            f.box(node, "grandstand", 160 + row * 2.5f, .8f + row * .7f, -80, 1.2f, .45f + row * .3f, 35, row % 2 == 0 ? 0xd4ddd2 : 0x4a8390);
        }
    }

    private PhysicsRigidBody solid(Vector3f position, Vector3f halfExtents) {
        PhysicsRigidBody body = new PhysicsRigidBody(new BoxCollisionShape(halfExtents), 0);
        body.setPhysicsLocation(position);
        body.setFriction(1f);
        space.add(body); solids.add(body);
        return body;
    }
    @Override public void close() {
        for (PhysicsRigidBody body : solids) space.remove(body);
        solids.clear(); node.removeFromParent();
    }
}
