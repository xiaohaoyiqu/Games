package com.f1car.scene;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.*;
import com.jme3.scene.shape.Box;
import com.jme3.scene.shape.Cylinder;
import com.jme3.util.BufferUtils;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SceneFactory {
    private final AssetManager assets;
    private final Map<Integer, Material> materials = new HashMap<>();
    public SceneFactory(AssetManager assets) { this.assets = assets; }
    public static ColorRGBA color(int rgb) { return new ColorRGBA(((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f, 1); }
    public Material material(int rgb) {
        return materials.computeIfAbsent(rgb, key -> {
            Material m = new Material(assets, "Common/MatDefs/Light/Lighting.j3md");
            m.setBoolean("UseMaterialColors", true);
            m.setColor("Diffuse", color(rgb));
            m.setColor("Ambient", color(rgb));
            m.setColor("Specular", new ColorRGBA(.14f, .14f, .14f, 1));
            m.setFloat("Shininess", 24);
            return m;
        });
    }
    public Geometry box(Node parent, String name, float x, float y, float z, float hx, float hy, float hz, int rgb) {
        Geometry g = new Geometry(name, new Box(hx, hy, hz));
        g.setMaterial(material(rgb));
        g.setLocalTranslation(x, y, z);
        parent.attachChild(g);
        return g;
    }
    public Geometry cylinder(Node parent, String name, float x, float y, float z, float radius, float height, int rgb) {
        Geometry g = new Geometry(name, new Cylinder(2, 12, radius, height, true));
        g.rotate((float) Math.PI / 2, 0, 0);
        g.setLocalTranslation(x, y, z);
        g.setMaterial(material(rgb));
        parent.attachChild(g);
        return g;
    }
    public void cone(Node parent, float x, float z) {
        box(parent, "cone base", x, .055f, z, .34f, .055f, .34f, 0x19262b);
        Geometry c = new Geometry("training cone", new Cylinder(2, 8, .045f, .25f, .75f, true, false));
        c.rotate((float) Math.PI / 2, 0, 0);
        c.setLocalTranslation(x, .43f, z);
        c.setMaterial(material(0xff6d26));
        parent.attachChild(c);
        cylinder(parent, "cone stripe", x, .40f, z, .16f, .09f, 0xf5ede0);
    }
    public Geometry triangles(Node parent, String name, List<Vector3f> vertices, int rgb) {
        int[] indices = new int[vertices.size()];
        for (int i = 0; i < indices.length; i++) indices[i] = i;
        return triangles(parent,name,BufferUtils.createFloatBuffer(vertices.toArray(Vector3f[]::new)),indices,rgb);
    }

    Geometry triangles(Node parent,String name,float[] positions,int[] indices,int rgb) {
        return triangles(parent,name,BufferUtils.createFloatBuffer(positions),indices,rgb);
    }

    private Geometry triangles(Node parent,String name,FloatBuffer positions,int[] indices,int rgb) {
        var normals=BufferUtils.createFloatBuffer(positions.remaining());
        while(normals.hasRemaining()) normals.put(0).put(1).put(0);
        normals.flip();
        Mesh mesh = new Mesh();
        mesh.setBuffer(VertexBuffer.Type.Position,3,positions);
        mesh.setBuffer(VertexBuffer.Type.Normal,3,normals);
        mesh.setBuffer(VertexBuffer.Type.Index, 3, BufferUtils.createIntBuffer(indices));
        mesh.updateCounts();
        mesh.updateBound();
        Geometry geometry = new Geometry(name, mesh);
        geometry.setMaterial(material(rgb));
        geometry.getMaterial().getAdditionalRenderState().setFaceCullMode(com.jme3.material.RenderState.FaceCullMode.Off);
        geometry.setShadowMode(com.jme3.renderer.queue.RenderQueue.ShadowMode.Receive);
        parent.attachChild(geometry);
        return geometry;
    }
}
