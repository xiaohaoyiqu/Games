import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Random;

/** Original, editable pixel-art source. Run: java scripts/GeneratePixelAssets.java assets/pixel */
public class GeneratePixelAssets {
    private static Graphics2D graphics(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        return g;
    }
    private static void rect(Graphics2D g, int color, int x, int y, int w, int h) {
        g.setColor(new Color(color)); g.fillRect(x,y,w,h);
    }
    private static BufferedImage car(int paint) {
        BufferedImage image = new BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(image);
        int dark=0x172333, rubber=0x101721, edge=0x3b4b5c, light=0xe5eedb;
        for(int x:new int[]{19,40}) {
            rect(g,dark,x,17,6,13); rect(g,rubber,x,18,6,10);
            rect(g,edge,x+1,19,1,7);
            rect(g,dark,x-1,41,7,13); rect(g,rubber,x-1,42,7,10);
            rect(g,edge,x,43,1,7);
        }
        rect(g,dark,24,17,16,2); rect(g,dark,23,47,18,2);
        rect(g,dark,27,14,10,43); rect(g,paint,28,15,8,39);
        rect(g,dark,29,6,6,15); rect(g,paint,30,7,4,16);
        rect(g,light,31,7,1,17);
        rect(g,dark,17,6,30,6); rect(g,paint,18,7,28,3);
        rect(g,light,20,7,24,1); rect(g,dark,17,5,3,8); rect(g,dark,44,5,3,8);
        rect(g,dark,23,30,18,14); rect(g,paint,24,31,6,12); rect(g,paint,35,31,5,12);
        rect(g,light,24,32,2,8); rect(g,edge,38,33,2,9);
        rect(g,dark,28,25,8,10); rect(g,0x3c6570,29,26,6,6);
        rect(g,0xffcf69,30,26,4,5); rect(g,dark,30,29,4,2);
        rect(g,light,27,24,10,1); rect(g,light,27,24,1,9); rect(g,light,36,24,1,9);
        rect(g,dark,31,22,2,5); rect(g,paint,29,37,6,13);
        rect(g,light,31,37,2,12);
        rect(g,dark,17,53,30,6); rect(g,paint,18,54,28,3);
        rect(g,light,21,54,22,1); rect(g,dark,16,52,3,8); rect(g,dark,45,52,3,8);
        rect(g,0xf86e5b,31,58,2,2);
        g.dispose(); return image;
    }
    private static Path bundledPixelDirectory() {
        Path script = Path.of(System.getProperty("jdk.launcher.sourcefile", "scripts/GeneratePixelAssets.java"));
        return script.toAbsolutePath().normalize().getParent().resolve("../src/main/resources/pixel").normalize();
    }

    private static boolean writeCarSheet(Path output, BufferedImage cars) throws IOException {
        Path bundled = bundledPixelDirectory().resolve("cars.png");
        Path target = output.resolve("cars.png");
        if (Files.isRegularFile(bundled)) {
            BufferedImage original = ImageIO.read(bundled.toFile());
            if (original != null && samePixels(cars, original)) {
                // Masks and recorded replay appearances identify the exact encoded PNG bytes.
                if (!Files.exists(target) || !Files.isSameFile(bundled, target))
                    Files.copy(bundled, target, StandardCopyOption.REPLACE_EXISTING);
                return true;
            }
        }
        ImageIO.write(cars, "png", target.toFile());
        return false;
    }

    private static boolean samePixels(BufferedImage a, BufferedImage b) {
        int width = a.getWidth(), height = a.getHeight();
        if (width != b.getWidth() || height != b.getHeight()) return false;
        int[] left = new int[width], right = new int[width];
        for (int y = 0; y < height; y++) {
            a.getRGB(0, y, width, 1, left, 0, width);
            b.getRGB(0, y, width, 1, right, 0, width);
            if (!Arrays.equals(left, right)) return false;
        }
        return true;
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length>0 ? args[0] : "assets/pixel");
        Files.createDirectories(output);
        int[] paints={0x39d9bb,0xf47a65,0x849cf0,0xf2cf68,0xd8e9e8};
        BufferedImage cars = new BufferedImage(1024,320,BufferedImage.TYPE_INT_ARGB);
        Graphics2D cg=graphics(cars);
        for(int row=0;row<paints.length;row++) {
            BufferedImage body = car(paints[row]);
            for(int direction=0;direction<16;direction++) {
                Graphics2D frame=(Graphics2D)cg.create(direction*64,row*64,64,64);
                frame.rotate(direction*Math.PI/8,32,32); frame.drawImage(body,0,0,null); frame.dispose();
            }
        }
        cg.dispose();
        boolean originalCars = writeCarSheet(output, cars);

        BufferedImage tiles=new BufferedImage(256,32,BufferedImage.TYPE_INT_ARGB);
        Graphics2D tg=graphics(tiles); int[] colors={0x527b52,0x384655,0xa18a64,0x427e95,0x768486,0x192637,0xe47b65,0xe3e3c4};
        Random random=new Random(21);
        for(int tile=0;tile<8;tile++) {
            rect(tg,colors[tile],tile*32,0,32,32);
            for(int i=0;i<50;i++) {
                Color c=new Color(colors[tile]); int d=i%2==0?7:-7;
                rect(tg,new Color(Math.max(0,c.getRed()+d),Math.max(0,c.getGreen()+d),Math.max(0,c.getBlue()+d)).getRGB(),tile*32+random.nextInt(32),random.nextInt(32),1+i%2,1);
            }
        }
        for(int y=0;y<32;y+=8) for(int x=0;x<32;x+=8) rect(tg,(x/8+y/8)%2==0?0xe3e3c4:0x192637,160+x,y,8,8);
        tg.dispose();ImageIO.write(tiles,"png",output.resolve("tiles.png").toFile());

        BufferedImage props=new BufferedImage(256,48,BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg=graphics(props);
        for(int id=0;id<8;id++) {
            Graphics2D p=(Graphics2D)pg.create(id*32,0,32,48);
            switch(id) {
                case 0 -> { rect(p,0x172333,10,39,13,4);rect(p,0xf38b4a,13,29,7,10);rect(p,0xf6e9c6,14,33,5,2);rect(p,0xf38b4a,15,25,3,4); }
                case 1 -> {
                    rect(p,0x3a4331,14,29,5,15);rect(p,0x2b513e,9,25,16,9);
                    rect(p,0x284734,5,14,23,14);rect(p,0x3a6d48,7,9,19,17);
                    rect(p,0x6d9458,10,5,13,14);rect(p,0x85a968,11,7,4,6);rect(p,0x416c41,22,16,4,11);
                }
                case 2 -> {rect(p,0x192637,1,33,30,10);rect(p,0xe3e3c4,2,33,14,7);rect(p,0x38b399,16,33,14,7);rect(p,0x789281,2,40,28,3);}
                case 3 -> {rect(p,0x192637,0,25,32,19);for(int r=0;r<4;r++){rect(p,0x7c9396,1,27+r*4,30,2);for(int x=3;x<32;x+=6)rect(p,r%2==0?0xe3c369:0xe78a6e,x,25+r*4,3,3);}}
                case 4 -> {for(int y=29;y<43;y+=7){rect(p,0x16212b,6,y,20,6);rect(p,0x667881,9,y+1,14,1);rect(p,0x223749,11,y+2,9,3);}}
                case 5 -> {rect(p,0x253341,2,22,28,22);rect(p,0xd2d3af,3,23,26,18);rect(p,0x3b6d77,6,28,20,14);rect(p,0xd98a67,0,18,32,7);rect(p,0xf0b37b,2,18,28,2);}
                case 6 -> {rect(p,0x16212b,14,39,5,5);rect(p,0xf5ae6b,13,29,7,10);rect(p,0xe9e8d2,14,26,5,4);rect(p,0xf5d28c,15,22,4,5);rect(p,0xe27c63,21,24,8,5);rect(p,0xd6dac0,20,24,2,15);}
                case 7 -> {rect(p,0x172333,9,38,15,4);rect(p,0x5c7b7c,12,30,9,8);rect(p,0xe6ddba,11,24,11,8);rect(p,0x57b8c7,12,24,9,2);rect(p,0xe6ddba,22,26,3,4);}
            }
            p.dispose();
        }
        pg.dispose(); ImageIO.write(props,"png",output.resolve("props.png").toFile());
        Path mask = output.resolve("paint-regions.json");
        Path bundledMask = bundledPixelDirectory().resolve("paint-regions.json");
        if (originalCars && Files.notExists(mask, LinkOption.NOFOLLOW_LINKS) && Files.isRegularFile(bundledMask))
            Files.copy(bundledMask, mask);
        String maskEntry = Files.isRegularFile(mask, LinkOption.NOFOLLOW_LINKS)
                ? "  \"paintRegions\": \"paint-regions.json\",\n" : "";
        Files.writeString(output.resolve("manifest.json"),"""
                {
                  "schemaVersion": 1,
                  "cars": "cars.png", "tiles": "tiles.png", "props": "props.png",
                %s  "carFrameSize": 64, "directions": 16, "liveries": 5,
                  "carWorldSize": 5.2,
                  "tileSize": 32, "propWidth": 32, "propHeight": 48,
                  "palette": {"grass":"#527b52", "road":"#384655", "roadEdge":"#e3e3c4", "kerb":"#e47b65", "water":"#427e95", "mark":"#edd586", "training":"#596978"}
                }
                """.formatted(maskEntry));
        System.out.println("Generated original pixel assets: "+output.toAbsolutePath());
    }
}
