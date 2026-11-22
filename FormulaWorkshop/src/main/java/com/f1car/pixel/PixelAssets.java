package com.f1car.pixel;

import com.f1car.model.PaintRegions;
import com.f1car.model.YawAngles;
import com.f1car.persistence.PaintRegionsCodec;

import com.google.gson.Gson;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import java.util.HexFormat;
import java.util.Objects;
import java.security.MessageDigest;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStreamImpl;

/** External PNG/JSON skin overrides bundled assets. No physics data is loaded from a skin. */
public final class PixelAssets {
    private static final int MAX_MANIFEST_BYTES=64_000,MAX_PNG_BYTES=20_000_000;
    private static final long PNG_SIGNATURE=0x89504e470d0a1a0aL;
    private static final int PNG_HEADER=0x49484452, PNG_HEADER_BYTES=33;
    private static final int PNG_END=0x49454e44;
    public static final class Definition {
        public int schemaVersion, carFrameSize, directions, liveries, tileSize, propWidth, propHeight;
        public float carWorldSize;
        public String cars, tiles, props,paintRegions;
        public Map<String,String> palette;
    }
    public final Definition definition;
    public final BufferedImage[][] cars;
    public final BufferedImage[] tiles, props;
    public final String source;
    public final String carAtlasIdentity;
    public final PaintRegions paintRegions;
    public final String regionWarning;
    private final Map<String,Color> paletteColors;
    private record RegionLoad(PaintRegions value,String warning) {}

    private PixelAssets(Definition d, BufferedImage carSheet, BufferedImage tileSheet, BufferedImage propSheet, String source,String carAtlasIdentity,RegionLoad regions,Map<String,Color> paletteColors) {
        definition=d; this.source=source;
        this.carAtlasIdentity=carAtlasIdentity;
        this.paletteColors=paletteColors;
        this.paintRegions=regions.value();this.regionWarning=regions.warning();
        cars=new BufferedImage[d.liveries][d.directions];
        for(int l=0;l<d.liveries;l++) for(int i=0;i<d.directions;i++) cars[l][i]=carSheet.getSubimage(i*d.carFrameSize,l*d.carFrameSize,d.carFrameSize,d.carFrameSize);
        tiles=new BufferedImage[8]; props=new BufferedImage[8];
        for(int i=0;i<8;i++) {
            tiles[i]=tileSheet.getSubimage(i*d.tileSize,0,d.tileSize,d.tileSize);
            props[i]=propSheet.getSubimage(i*d.propWidth,0,d.propWidth,d.propHeight);
        }
    }
    public static PixelAssets load(Path directory) {
        try {
            boolean external=directory!=null && !Files.notExists(directory.resolve("manifest.json"),LinkOption.NOFOLLOW_LINKS);
            Path root=external?directory.toRealPath():null;
            byte[] manifest;
            try(var input=openAsset(root,"manifest.json")) {
                manifest=readBounded(input,MAX_MANIFEST_BYTES,"像素素材配置");
            }
            String json=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(manifest)).toString();
            Definition d=new Gson().fromJson(json,Definition.class);
            validate(d);
            var colors=new HashMap<String,Color>();
            for(String key:new String[]{"grass","road","roadEdge","kerb","water","mark","training"}) {
                String value=d.palette.get(key);
                colors.computeIfAbsent(value,encoded->decodeColor(encoded,key));
            }
            var digest=MessageDigest.getInstance("SHA-256");
            var cars=loadPng(root,d.cars,d.carFrameSize*d.directions,d.carFrameSize*d.liveries,"车辆图集",digest);
            String identity="car-png-sha256-"+HexFormat.of().formatHex(digest.digest());
            var tiles=loadPng(root,d.tiles,d.tileSize*8,d.tileSize,"地面图集",null);
            var props=loadPng(root,d.props,d.propWidth*8,d.propHeight,"物件图集",null);
            var regions=regions(root,d.paintRegions,identity,d.carFrameSize);
            return new PixelAssets(d,cars,tiles,props,external?directory.toAbsolutePath().toString():"内置像素素材",identity,regions,colors);
        } catch(Exception e) { throw new IllegalArgumentException("素材载入失败："+e.getMessage(),e); }
    }
    private static void validate(Definition d) {
        if(d==null || d.schemaVersion!=1 || d.carFrameSize<16 || d.carFrameSize>256 || d.directions<8 || d.directions>32 || d.liveries!=5
                || d.tileSize<8 || d.tileSize>128 || d.propWidth<8 || d.propWidth>128 || d.propHeight<8 || d.propHeight>128
                || !Float.isFinite(d.carWorldSize) || d.carWorldSize<3 || d.carWorldSize>9 || d.palette==null)
            throw new IllegalArgumentException("像素素材配置格式或尺寸无效");
    }
    public Color color(String name) {
        String value=definition.palette==null?null:definition.palette.get(name);
        Color cached=paletteColors.get(value);
        return cached!=null?cached:decodeColor(value,name);
    }
    private static Color decodeColor(String value,String name) {
        try { return Color.decode(value); }
        catch(Exception e) { throw new IllegalArgumentException("调色板缺少有效颜色："+name); }
    }
    private static RegionLoad regions(Path root,String name,String identity,int size) {
        if(name==null || name.isBlank()) return new RegionLoad(null,"当前素材未提供部位蒙版。");
        try(var input=openAsset(root,name)) {
            var regions=PaintRegionsCodec.read(input);
            if(!regions.atlas().equals(identity) || regions.frameSize()!=size)
                return new RegionLoad(null,"部位蒙版与车辆图集不匹配，部位层暂不可用。");
            return new RegionLoad(regions,"");
        }catch(IOException | RuntimeException error) {
            return new RegionLoad(null,"部位蒙版无法读取，整车装饰仍可用。");
        }
    }
    public BufferedImage car(int livery,double angle) { return cars[Math.floorMod(livery,cars.length)][direction(angle,definition.directions)]; }
    public static int direction(double angle,int count) {
        double steps=angle/(Math.PI*2)*count;
        // Replay endpoints can retain any finite yaw. Reduce the heading before its cell index overflows.
        if(Math.abs(steps)>Integer.MAX_VALUE && Double.isFinite(angle))
            steps=YawAngles.principal(angle)/(Math.PI*2)*count;
        return Math.floorMod((int)Math.round(steps),count);
    }
    private static byte[] readBounded(InputStream input,int limit,String label) throws IOException {
        byte[] bytes=input.readNBytes(limit+1);
        if(bytes.length>limit) throw new IOException(label+"文件过大");
        return bytes;
    }
    private static BufferedImage loadPng(Path root,String name,int width,int height,String label,MessageDigest digest) throws IOException {
        byte[] bytes;
        try(var source=new BufferedInputStream(openAsset(root,name),PNG_HEADER_BYTES)) {
            source.mark(PNG_HEADER_BYTES);
            checkPngHeader(source.readNBytes(PNG_HEADER_BYTES),width,height,label);
            source.reset();
            bytes=readBounded(source,MAX_PNG_BYTES,label);
        }
        checkPngChunks(bytes,label);
        if(digest!=null) digest.update(bytes);
        var readers=ImageIO.getImageReadersByFormatName("PNG");
        if(!readers.hasNext()) throw new IOException("缺少 PNG 解码器");
        var reader=readers.next();
        try(var input=new PngInput(bytes)) {
            reader.setInput(input,true,true);
            if(reader.getWidth(0)!=width || reader.getHeight(0)!=height)
                throw new IOException(label+"应为 "+width+" × "+height+" 像素");
            return reader.read(0);
        }finally {
            reader.dispose();
        }
    }
    /** ImageIO can seek directly in the same PNG bytes used for validation and atlas identity. */
    static final class PngInput extends ImageInputStreamImpl {
        private byte[] bytes;

        PngInput(byte[] bytes) {
            this.bytes = Objects.requireNonNull(bytes);
        }

        @Override public int read() throws IOException {
            checkClosed();
            bitOffset = 0;
            if (streamPos >= bytes.length) return -1;
            return bytes[(int) streamPos++] & 0xff;
        }

        @Override public int read(byte[] destination, int offset, int length) throws IOException {
            checkClosed();
            Objects.checkFromIndexSize(offset, length, destination.length);
            bitOffset = 0;
            if (length == 0) return 0;
            if (streamPos >= bytes.length) return -1;
            int count = Math.min(length, bytes.length - (int) streamPos);
            System.arraycopy(bytes, (int) streamPos, destination, offset, count);
            streamPos += count;
            return count;
        }

        @Override public void close() throws IOException {
            super.close();
            bytes = null;
        }
    }
    private static void checkPngHeader(byte[] bytes,int width,int height,String label) throws IOException {
        var header=ByteBuffer.wrap(bytes);
        if(bytes.length<Long.BYTES || header.getLong()!=PNG_SIGNATURE) throw new IOException("素材必须为 PNG 图片");
        if(bytes.length!=PNG_HEADER_BYTES || header.getInt()!=13 || header.getInt()!=PNG_HEADER)
            throw new IOException(label+"的 PNG 文件头无效");
        var crc=new CRC32();
        crc.update(bytes,12,17); // IHDR type and its 13 data bytes.
        if((int)crc.getValue()!=header.getInt(PNG_HEADER_BYTES-Integer.BYTES))
            throw new IOException(label+"的 PNG 数据块校验失败");
        if(header.getInt()!=width || header.getInt()!=height)
            throw new IOException(label+"应为 "+width+" × "+height+" 像素");
    }
    private static void checkPngChunks(byte[] bytes,String label) throws IOException {
        var chunks=ByteBuffer.wrap(bytes);
        chunks.position(PNG_HEADER_BYTES);
        var crc=new CRC32();
        while(chunks.remaining()>=12) {
            long length=Integer.toUnsignedLong(chunks.getInt());
            int typeOffset=chunks.position();
            int type=chunks.getInt();
            if(length>chunks.remaining()-Integer.BYTES) throw new IOException("PNG 数据块长度无效");
            crc.reset();
            crc.update(bytes,typeOffset,Integer.BYTES+(int)length);
            chunks.position(chunks.position()+(int)length);
            if(chunks.getInt()!=(int)crc.getValue()) throw new IOException(label+"的 PNG 数据块校验失败");
            if(type==PNG_END) {
                if(length!=0) throw new IOException(label+"的 PNG 结束块必须为空");
                return;
            }
        }
        throw new IOException("PNG 缺少结束数据块");
    }
    private static InputStream openAsset(Path root,String name) throws IOException {
        if(name==null || name.isBlank()) throw new IOException("缺少素材路径");
        if(root==null) return resource(name);
        Path withinRoot=root.resolve(name).normalize();
        if(!withinRoot.startsWith(root)) throw new IOException("所选文件必须位于素材目录内");
        Path real=withinRoot.toRealPath();
        if(!real.startsWith(root) || !Files.isRegularFile(real,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("所选文件必须是素材目录内的普通文件");
        return Files.newInputStream(real,LinkOption.NOFOLLOW_LINKS);
    }
    private static InputStream resource(String name) throws IOException {
        InputStream stream=PixelAssets.class.getResourceAsStream("/pixel/"+name);
        if(stream==null) throw new FileNotFoundException(name); return stream;
    }
}
