package com.f1car.model;

import java.util.*;
import java.util.function.BiFunction;

/** Bounded sequence comparison. Indices belong to their own version; objects have no stable IDs. */
public record CircuitRevisionDiff(String beforeKey,String afterKey,List<Change> changes) {
    public enum Kind { IDENTITY,METADATA,WIDTH,START,ANCHOR,OBJECT,SCENERY }
    public record Change(Kind kind,String title,String before,String after,List<CircuitInspector.Location> locations,int anchor,int item) {
        public Change {locations=List.copyOf(locations);if(locations.size()>2 || anchor< -1 || anchor>47 || item< -1 || item>63 || anchor>=0 && item>=0) throw new IllegalArgumentException("Invalid revision location");}
        public String description() {return title+"："+before+" → "+after;}
        public CircuitInspector.Issue focusIssue() {return new CircuitInspector.Issue(CircuitInspector.Code.GEOMETRY,"版本变化："+title,locations,anchor,item);}
    }
    public CircuitRevisionDiff {changes=List.copyOf(changes);}
    public boolean drivingInputChanged() {return !beforeKey.equals(afterKey);}
    public static CircuitRevisionDiff compare(CircuitDocument before,CircuitDocument after) {
        Objects.requireNonNull(before);Objects.requireNonNull(after);
        String beforeKey=before.driveKey();
        if(before.equals(after)) return new CircuitRevisionDiff(beforeKey,beforeKey,List.of());
        var changes=new ArrayList<Change>();
        add(changes,Kind.IDENTITY,"地图身份",before.id(),after.id());add(changes,Kind.METADATA,"地图名称",before.name(),after.name());
        var a=before.metadata();var b=after.metadata();add(changes,Kind.METADATA,"作者",a.author(),b.author());add(changes,Kind.METADATA,"简介",a.description(),b.description());add(changes,Kind.METADATA,"作者难度标签",a.difficulty().title,b.difficulty().title);add(changes,Kind.METADATA,"作者修订说明",a.revision(),b.revision());
        add(changes,Kind.WIDTH,"路宽（米）",before.width(),after.width());add(changes,Kind.SCENERY,"主题",before.scene().theme(),after.scene().theme());add(changes,Kind.SCENERY,"布景种子",before.scene().seed(),after.scene().seed());
        int rotated=rotation(before.anchors(),after.anchors());
        if(rotated>0) changes.add(new Change(Kind.START,"起点／路线循环顺序","旧第 1 点","旧第 "+(rotated+1)+" 点成为当前起点",List.of(location(before.anchors().get(0)),location(after.anchors().get(0))),0,-1));
        else sequence(before.anchors(),after.anchors(),(oldIndex,newIndex)->{
            var old=oldIndex<0?null:before.anchors().get(oldIndex);var now=newIndex<0?null:after.anchors().get(newIndex);var positions=new ArrayList<CircuitInspector.Location>();if(old!=null) positions.add(location(old));if(now!=null) positions.add(location(now));
            return new Change(Kind.ANCHOR,old==null?"新增路线点 #"+(newIndex+1):now==null?"移除历史路线点 #"+(oldIndex+1):"路线条目 #"+(oldIndex+1)+" → #"+(newIndex+1),point(old),point(now),positions,newIndex,-1);
        },changes);
        sequence(before.scene().items(),after.scene().items(),(oldIndex,newIndex)->{
            var old=oldIndex<0?null:before.scene().items().get(oldIndex);var now=newIndex<0?null:after.scene().items().get(newIndex);var positions=new ArrayList<CircuitInspector.Location>();if(old!=null) positions.add(new CircuitInspector.Location(old.x(),old.z()));if(now!=null) positions.add(new CircuitInspector.Location(now.x(),now.z()));
            return new Change(Kind.OBJECT,old==null?"新增物件 #"+(newIndex+1):now==null?"移除历史物件 #"+(oldIndex+1):"物件条目 #"+(oldIndex+1)+" → #"+(newIndex+1),item(old),item(now),positions,-1,newIndex);
        },changes);
        return new CircuitRevisionDiff(beforeKey,after.driveKey(),changes);
    }
    /** Exact unchanged sequence is aligned first, so one insertion does not mark every later entry. */
    private static <T> void sequence(List<T> old,List<T> current,BiFunction<Integer,Integer,Change> change,List<Change> output) {
        if(old.equals(current)) return;
        int n=old.size(),m=current.size();var lcs=new int[n+1][m+1];for(int i=n-1;i>=0;i--) for(int j=m-1;j>=0;j--) lcs[i][j]=old.get(i).equals(current.get(j))?1+lcs[i+1][j+1]:Math.max(lcs[i+1][j],lcs[i][j+1]);
        int i=0,j=0;while(i<n || j<m) {
            if(i<n && j<m && old.get(i).equals(current.get(j))) {i++;j++;continue;}int fromOld=i,fromNew=j;
            while(i<n || j<m) {if(i<n && j<m && old.get(i).equals(current.get(j))) break;if(i==n) j++;else if(j==m || lcs[i+1][j]>=lcs[i][j+1]) i++;else j++;}
            int count=Math.max(i-fromOld,j-fromNew);for(int offset=0;offset<count;offset++) output.add(change.apply(fromOld+offset<i?fromOld+offset:-1,fromNew+offset<j?fromNew+offset:-1));
        }
    }
    private static int rotation(List<CircuitDocument.Point> before,List<CircuitDocument.Point> after) {if(before.size()!=after.size() || before.equals(after)) return 0;for(int start=1;start<before.size();start++) {boolean same=true;for(int i=0;i<before.size();i++) if(!before.get((i+start)%before.size()).equals(after.get(i))) {same=false;break;}if(same) return start;}return 0;}
    private static void add(List<Change> changes,Kind kind,String title,Object before,Object after) {if(!Objects.equals(before,after)) changes.add(new Change(kind,title,Objects.toString(before),Objects.toString(after),List.of(),-1,-1));}
    private static CircuitInspector.Location location(CircuitDocument.Point point) {return new CircuitInspector.Location(point.x(),point.z());}
    private static String point(CircuitDocument.Point point) {return point==null?"无":"("+point.x()+", "+point.z()+") 米";}
    private static String item(CircuitScene.Item value) {return value==null?"无":value.kind()+" ("+value.x()+", "+value.z()+")，朝向 "+Math.toDegrees(value.yaw())+"°，"+value.length()+" × "+value.width()+" 米";}
    public static final class Cache {
        private CircuitDocument before,after;private CircuitRevisionDiff value;private int computations;
        public CircuitRevisionDiff get(CircuitDocument previous,CircuitDocument current) {Objects.requireNonNull(previous);Objects.requireNonNull(current);if(before!=previous || after!=current) {value=compare(previous,current);before=previous;after=current;computations++;}return value;}
        public int computations() {return computations;}
    }
}
