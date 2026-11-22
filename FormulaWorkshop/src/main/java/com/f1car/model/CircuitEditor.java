package com.f1car.model;

import java.util.*;

/** Immutable snapshots make a complete pointer gesture one undo step. */
public final class CircuitEditor {
    public enum Tool { SELECT,POINT,TREE,SIGN,BARRIER,BUILDING }
    public enum Axis { X,Z }
    /** Session-only scenery snapshots; map identities and driving proofs are never copied. */
    public static final class Clipboard {
        private List<CircuitScene.Item> items=List.of();
        public int size() { return items.size(); }
    }
    private record Draft(CircuitDocument document,CircuitTrial.Proof proof) {}
    private static final class PointerDrag {
        private final Draft before;
        private final float startX,startZ,baseX,baseZ,grabX,grabZ,limit;
        private float minDx,maxDx,minDz,maxDz;
        private float lastDx=Float.NaN,lastDz;

        PointerDrag(Draft before,float startX,float startZ,float baseX,float baseZ,float limit) {
            this.before=before;
            this.startX=startX;
            this.startZ=startZ;
            this.baseX=baseX;
            this.baseZ=baseZ;
            this.grabX=startX-baseX;
            this.grabZ=startZ-baseZ;
            this.limit=limit;
            minDx=minDz=-2*limit;
            maxDx=maxDz=2*limit;
        }

        void include(float x,float z) {
            minDx=Math.max(minDx,shiftToBoundary(x,-limit));
            maxDx=Math.min(maxDx,shiftToBoundary(x,limit));
            minDz=Math.max(minDz,shiftToBoundary(z,-limit));
            maxDz=Math.min(maxDz,shiftToBoundary(z,limit));
        }

        private static float shiftToBoundary(float position,float boundary) {
            float shift=boundary-position;
            float moved=position+shift;
            // The float subtraction can round outwards when the two positions are far apart.
            if(boundary<0 && moved<boundary) return Math.nextUp(shift);
            if(boundary>0 && moved>boundary) return Math.nextDown(shift);
            return shift;
        }

        void invalidateLastMove() {lastDx=Float.NaN;}
    }
    private Draft current,saved;
    private PointerDrag drag;
    private final Deque<Draft> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();
    private long historyRevision;
    private final CircuitInspector.DraftInspection inspection=new CircuitInspector.DraftInspection();
    private int anchor=-1,item=-1;
    private final LinkedHashSet<Integer> selectedItems=new LinkedHashSet<>();
    private final LinkedHashSet<Integer> selectedAnchors=new LinkedHashSet<>();
    private final Clipboard clipboard;
    private Tool tool=Tool.POINT;
    private CircuitCommission commission;
    private final CircuitCommission.Cache commissionCache=new CircuitCommission.Cache();
    private String restriction="";
    public CircuitEditor(CircuitDocument document,CircuitTrial.Proof proof,boolean alreadySaved) {
        this(document,proof,alreadySaved,new Clipboard());
    }
    public CircuitEditor(CircuitDocument document,CircuitTrial.Proof proof,boolean alreadySaved,Clipboard clipboard) {
        this.clipboard=Objects.requireNonNull(clipboard);
        Objects.requireNonNull(document);current=new Draft(document,proof!=null && proof.documentKey().equals(document.driveKey())?proof:null);saved=alreadySaved?current:null;
    }
    public CircuitDocument document() { return current.document(); }
    public CircuitTrial.Proof proof() { return current.proof(); }
    public CircuitCommission.Review commissionReview(String physics) {return commission==null?null:commissionCache.get(commission,document(),proof(),physics,inspection());}
    public int commissionReviewComputations() {return commissionCache.computations();}
    public boolean dirty() { return !current.equals(saved); }
    public long historyRevision() { return historyRevision; }
    public void saved() { endPointer();saved=current; }
    public void validated(CircuitTrial.Proof proof) {
        if(!proof.documentKey().equals(document().driveKey())) throw new IllegalArgumentException("Validation belongs to another draft");
        current=new Draft(document(),proof);
        if(drag!=null) drag.invalidateLastMove();
    }
    public int selectedAnchor() { return anchor; }
    public int selectedItem() { return item; }
    public int selectedItemCount() { return selectedItems.size(); }
    public int selectedAnchorCount() { return selectedAnchors.size(); }
    public boolean isAnchorSelected(int index) {return selectedAnchors.contains(index);}
    public boolean isItemSelected(int index) {return selectedItems.contains(index);}
    public Set<Integer> selectedItems() { return Set.copyOf(selectedItems); }
    public Set<Integer> selectedAnchors() { return Set.copyOf(selectedAnchors); }
    public int clipboardSize() { return clipboard.size(); }
    public int remainingAnchors() { return CircuitDocument.MAX_ANCHORS-document().anchors().size(); }
    public int remainingItems() { return CircuitScene.MAX_ITEMS-document().scene().items().size(); }
    public void clearSelection() { endPointer();restriction="";anchor=item=-1;selectedItems.clear();selectedAnchors.clear(); }
    public void selectAllAnchors() {
        clearSelection();tool=Tool.POINT;
        for(int i=0;i<document().anchors().size();i++) selectedAnchors.add(i);
        activeAnchor();
    }
    public boolean restore(CircuitDocument snapshot) {
        if(!document().id().equals(snapshot.id())) throw new IllegalArgumentException("Another circuit identity");
        boolean changed=change(snapshot);if(changed) clearSelection();return changed;
    }
    public void selectAllItems() {
        clearSelection();tool=Tool.SELECT;
        for(int i=0;i<document().scene().items().size();i++) selectedItems.add(i);
        activeItem();
    }
    public Tool tool() { return tool; }
    public void restrict(CircuitCommission value) {
        commission=value;
        restriction="";
        if(drag!=null) drag.invalidateLastMove();
    }
    public String restriction() { return restriction; }
    public void focus(CircuitInspector.Issue issue) {
        Objects.requireNonNull(issue);
        focusSelection(issue.anchor(),issue.item());
    }
    public boolean focus(CircuitObservationNearby nearby,CircuitObservationNearby.Target target) {
        if(!nearby.matches(document()) || !nearby.contains(target)) return false;
        focusSelection(target.routePoint()?target.index():-1,target.routePoint()?-1:target.index());
        return true;
    }
    private void focusSelection(int anchorIndex,int itemIndex) {
        clearSelection();
        tool=Tool.SELECT;
        if(anchorIndex>=0 && anchorIndex<document().anchors().size()) {
            anchor=anchorIndex;
            selectedAnchors.add(anchor);
        } else if(itemIndex>=0 && itemIndex<document().scene().items().size()) {
            item=itemIndex;
            selectedItems.add(item);
        }
    }
    public void tool(Tool value) { endPointer();tool=Objects.requireNonNull(value);restriction=""; }
    public boolean dragging() { return drag!=null; }
    public CircuitInspector.Report inspection() {return inspection.inspect(document());}
    /** Drawing-only road, or null while the draft cannot produce a route. */
    public TrackLayout roadPreview() {return inspection.preview(document());}
    public CircuitTrial fourCarTrial() {return new CircuitTrial(document(),CircuitTrial.Kind.FOUR_CARS,0,inspection());}
    public CircuitTrial soloTrial(int startAnchor) {return new CircuitTrial(document(),CircuitTrial.Kind.SOLO,startAnchor,inspection());}
    public void rename(String name) { change(document().renamed(name)); }
    public void card(CircuitCard value) { change(document().withCard(value)); }
    public void width(float delta) { change(document().roadWidth(Math.max(12,Math.min(24,document().width()+delta)))); }
    public void theme() { var scene=document().scene();var themes=TrackCatalog.Theme.values();change(document().scenery(new CircuitScene(themes[(scene.theme().ordinal()+1)%themes.length],scene.seed(),scene.items()))); }
    public void setStart() {
        endPointer();if(anchor<0 || anchor>=document().anchors().size()) return;
        var points=new ArrayList<>(document().anchors());Collections.rotate(points,-anchor);if(change(document().points(points))) { clearSelection();anchor=0;selectedAnchors.add(0); }
    }
    public void rotate(float delta) {
        endPointer();if(item<0 || item>=document().scene().items().size()) return;
        var scene=document().scene();var items=new ArrayList<>(scene.items());for(int index:selectedItems) items.set(index,items.get(index).rotate(delta));change(document().scenery(new CircuitScene(scene.theme(),scene.seed(),items)));
    }
    public void moveSelected(float x,float z) {
        endPointer();
        var base=document();
        if(anchor>=0 && anchor<base.anchors().size()) {
            var points=new ArrayList<>(base.anchors());
            var active=points.get(anchor);
            for(int index:selectedAnchors) {
                var old=points.get(index);
                points.set(index,new CircuitDocument.Point(movedCoordinate(old.x(),active.x(),x),movedCoordinate(old.z(),active.z(),z)));
            }
            change(base.points(points));
        } else if(item>=0 && item<base.scene().items().size()) {
            var scene=base.scene();
            var items=new ArrayList<>(scene.items());
            var active=items.get(item);
            for(int index:selectedItems) {
                var old=items.get(index);
                items.set(index,old.move(movedCoordinate(old.x(),active.x(),x),movedCoordinate(old.z(),active.z(),z)));
            }
            change(base.scenery(new CircuitScene(scene.theme(),scene.seed(),items)));
        }
    }
    private static float movedCoordinate(float value,float origin,float target) {
        if(value==origin) return target;
        if(origin==target) return value;
        return (float)(value+((double)target-origin));
    }
    public void yawSelected(float radians) {
        endPointer();if(item<0 || item>=document().scene().items().size()) return;
        var scene=document().scene();var items=new ArrayList<>(scene.items());var old=items.get(item);items.set(item,new CircuitScene.Item(old.kind(),old.x(),old.z(),radians,old.length(),old.width()));change(document().scenery(new CircuitScene(scene.theme(),scene.seed(),items)));
    }
    public void resizeSelected(float length,float width) {
        endPointer();if(item<0 || item>=document().scene().items().size()) return;
        var scene=document().scene();var items=new ArrayList<>(scene.items());var old=items.get(item);if(!old.solid()) throw new IllegalArgumentException("Only solid footprints have editable dimensions");
        items.set(item,new CircuitScene.Item(old.kind(),old.x(),old.z(),old.yaw(),length,width));change(document().scenery(new CircuitScene(scene.theme(),scene.seed(),items)));
    }
    public boolean duplicateSelected() {
        endPointer();return append(selection());
    }
    public boolean copySelected() {
        endPointer();restriction="";var selected=selection();if(selected.isEmpty()) return reject("请先选择场景物件；路线点可成组移动、对齐和删除，不能复制。");clipboard.items=selected;return true;
    }
    public boolean paste() { endPointer();if(clipboard.items.isEmpty()) return reject("剪贴板没有场景物件，请先选择物件并复制。");return append(clipboard.items); }
    public boolean alignSelected(Axis axis) {
        endPointer();Objects.requireNonNull(axis);
        if(selectedAnchors.size()>1) {
            var points=new ArrayList<>(document().anchors());var active=points.get(anchor);
            for(int index:selectedAnchors) { var old=points.get(index);points.set(index,new CircuitDocument.Point(axis==Axis.X?active.x():old.x(),axis==Axis.Z?active.z():old.z())); }
            return change(document().points(points));
        }
        if(selectedItems.size()<2) return false;
        var scene=document().scene();var items=new ArrayList<>(scene.items());var active=items.get(item);
        for(int index:selectedItems) { var old=items.get(index);items.set(index,old.move(axis==Axis.X?active.x():old.x(),axis==Axis.Z?active.z():old.z())); }
        return change(document().scenery(new CircuitScene(scene.theme(),scene.seed(),items)));
    }
    private List<CircuitScene.Item> selection() { return selectedItems.stream().sorted().map(document().scene().items()::get).toList(); }
    private boolean append(List<CircuitScene.Item> copied) {
        if(copied.isEmpty()) return reject("请先选择要复制的场景物件；路线点不能复制。");
        if(copied.size()>remainingItems()) return reject("无法添加 "+copied.size()+" 个物件，剩余 "+remainingItems()+" 位；整组未添加。请减少数量或先删除物件。");
        float dx=copyOffset(copied,true),dz=copyOffset(copied,false);var scene=document().scene();var items=new ArrayList<>(scene.items());int first=items.size();
        for(var old:copied) items.add(old.move(old.x()+dx,old.z()+dz));
        if(!change(document().scenery(new CircuitScene(scene.theme(),scene.seed(),items)))) return false;
        clearSelection();for(int i=first;i<items.size();i++) selectedItems.add(i);activeItem();tool=Tool.SELECT;return true;
    }
    private boolean reject(String reason) { restriction=reason;return false; }
    private float copyOffset(List<CircuitScene.Item> items,boolean xAxis) {
        float min=280,max=-280;for(var value:items) { float coordinate=xAxis?value.x():value.z();min=Math.min(min,coordinate);max=Math.max(max,coordinate); }
        if(max<=276) return 4;if(min>=-276) return -4;return 0;
    }
    private CircuitDocument translated(CircuitDocument base,float dx,float dz) {
        var scene=base.scene();var items=new ArrayList<>(scene.items());
        for(int index:selectedItems) { var old=items.get(index);items.set(index,old.move(old.x()+dx,old.z()+dz)); }
        return base.scenery(new CircuitScene(scene.theme(),scene.seed(),items));
    }
    private void activeItem() { item=-1;for(int index:selectedItems) item=index; }
    private void activeAnchor() { anchor=-1;for(int index:selectedAnchors) anchor=index; }
    private CircuitDocument translatedAnchors(CircuitDocument base,float dx,float dz) {
        var points=new ArrayList<>(base.anchors());
        for(int index:selectedAnchors) { var old=points.get(index);points.set(index,new CircuitDocument.Point(old.x()+dx,old.z()+dz)); }
        return base.points(points);
    }
    public void beginPointer(float x,float z,float radius,boolean erase) { beginPointer(x,z,radius,erase,false); }
    public void beginPointer(float x,float z,float radius,boolean erase,boolean additive) {
        endPointer();restriction="";var point=point(x,z);if(!Float.isFinite(radius) || radius<0) throw new IllegalArgumentException("Invalid selection radius");
        int hitAnchor=-1,hitItem=-1;float best=radius*radius;
        for(int i=0;i<document().anchors().size();i++) { var p=document().anchors().get(i);float d=square(p.x()-x)+square(p.z()-z);if(d<best) { best=d;hitAnchor=i; } }
        if(tool!=Tool.POINT || erase) for(int i=0;i<document().scene().items().size();i++) {
            var p=document().scene().items().get(i);float d=itemDistance(x,z,p);if(d<=best) { best=d;hitAnchor=-1;hitItem=i; }
        }
        if(!erase && tool==Tool.POINT && !additive && hitAnchor<0 && remainingAnchors()==0) {
            reject("路线点已满（"+CircuitDocument.MAX_ANCHORS+" / "+CircuitDocument.MAX_ANCHORS+"），请先删除路线点再添加；已有路线点仍可移动。");return;
        }
        if(!erase && tool!=Tool.POINT && tool!=Tool.SELECT && remainingItems()==0) {
            reject("场景物件已满（"+CircuitScene.MAX_ITEMS+" / "+CircuitScene.MAX_ITEMS+"），请先删除物件再添加；可切换「选择／移动」继续编辑。");return;
        }
        if(additive && !erase && (tool==Tool.SELECT || tool==Tool.POINT)) {
            if(hitItem>=0) { anchor=-1;selectedAnchors.clear();if(!selectedItems.remove(hitItem)) selectedItems.add(hitItem);activeItem(); }
            else if(hitAnchor>=0) { item=-1;selectedItems.clear();if(!selectedAnchors.remove(hitAnchor)) selectedAnchors.add(hitAnchor);activeAnchor(); }
            return;
        }
        if(hitItem<0 || !selectedItems.contains(hitItem) || tool!=Tool.SELECT && !erase) selectedItems.clear();
        if(hitAnchor<0 || !selectedAnchors.contains(hitAnchor)) selectedAnchors.clear();
        anchor=hitAnchor;item=hitItem;if(item>=0) { selectedItems.remove(item);selectedItems.add(item); }
        if(anchor>=0) { selectedAnchors.remove(anchor);selectedAnchors.add(anchor); }
        if(erase) { delete();return; }
        if(tool==Tool.POINT || tool==Tool.SELECT) {
            if(anchor<0 && item<0 && tool==Tool.POINT) {
                var points=new ArrayList<>(document().anchors());int after=nearestSegment(point);points.add(after+1,point);if(change(document().points(points))) { anchor=after+1;selectedAnchors.clear();selectedAnchors.add(anchor); }return;
            }
            if(anchor>=0 || item>=0) beginDrag(x,z);
        } else {
            var scene=document().scene();var items=new ArrayList<>(scene.items());var kind=CircuitScene.Kind.valueOf(tool.name());
            items.add(new CircuitScene.Item(kind,point.x(),point.z(),0,kind==CircuitScene.Kind.BUILDING?16:kind==CircuitScene.Kind.BARRIER?8:2,kind==CircuitScene.Kind.BUILDING?10:1));
            if(change(document().scenery(new CircuitScene(scene.theme(),scene.seed(),items)))) { item=items.size()-1;anchor=-1;selectedAnchors.clear();selectedItems.clear();selectedItems.add(item); }
        }
    }
    private void beginDrag(float x,float z) {
        if(anchor>=0) {
            var points=document().anchors();
            var active=points.get(anchor);
            drag=new PointerDrag(current,x,z,active.x(),active.z(),240);
            for(int index:selectedAnchors) {
                var point=points.get(index);
                drag.include(point.x(),point.z());
            }
        } else {
            var items=document().scene().items();
            var active=items.get(item);
            drag=new PointerDrag(current,x,z,active.x(),active.z(),280);
            for(int index:selectedItems) {
                var selected=items.get(index);
                drag.include(selected.x(),selected.z());
            }
        }
    }

    public void movePointer(float x,float z) {
        var gesture=drag;
        if(gesture==null) return;
        requirePointer(x,z);
        if(Math.abs(x-gesture.startX)<.0001f && Math.abs(z-gesture.startZ)<.0001f) {
            current=gesture.before;
            restriction="";
            gesture.invalidateLastMove();
            return;
        }
        float adjustedX=x-gesture.grabX,adjustedZ=z-gesture.grabZ;
        requirePointer(adjustedX,adjustedZ);
        float dx=Math.max(gesture.minDx,Math.min(gesture.maxDx,snap(adjustedX,gesture.limit)-gesture.baseX));
        float dz=Math.max(gesture.minDz,Math.min(gesture.maxDz,snap(adjustedZ,gesture.limit)-gesture.baseZ));
        // Repeated input in the same cell or against the same group boundary has the same result.
        if(Float.compare(dx,gesture.lastDx)==0 && Float.compare(dz,gesture.lastDz)==0) return;
        var base=gesture.before.document();
        assign(anchor>=0?translatedAnchors(base,dx,dz):translated(base,dx,dz));
        gesture.lastDx=dx;
        gesture.lastDz=dz;
    }
    public void endPointer() {
        if(drag==null) return;var before=drag.before;drag=null;
        if(!before.equals(current)) rememberEdit(before);
    }
    public void delete() {
        endPointer();
        if(anchor>=0 && anchor<document().anchors().size()) { var points=new ArrayList<CircuitDocument.Point>();for(int i=0;i<document().anchors().size();i++) if(!selectedAnchors.contains(i)) points.add(document().anchors().get(i));if(!change(document().points(points))) return; }
        else if(!selectedItems.isEmpty()) { var scene=document().scene();var items=new ArrayList<CircuitScene.Item>();for(int i=0;i<scene.items().size();i++) if(!selectedItems.contains(i)) items.add(scene.items().get(i));if(!change(document().scenery(new CircuitScene(scene.theme(),scene.seed(),items)))) return; }
        clearSelection();
    }
    public void undo() { endPointer();restriction="";if(!undo.isEmpty()) { redo.addLast(current);current=undo.removeLast();historyRevision++;clearSelection(); } }
    public void redo() { endPointer();restriction="";if(!redo.isEmpty()) { undo.addLast(current);current=redo.removeLast();historyRevision++;clearSelection(); } }
    private boolean change(CircuitDocument next) {
        endPointer();restriction="";if(document().equals(next)) return false;var before=current;if(!assign(next)) return false;rememberEdit(before);return true;
    }
    private boolean assign(CircuitDocument next) {
        restriction=commission==null?"":commission.scope(next);
        if(!restriction.isEmpty()) return false;
        var source=drag!=null?drag.before:current;
        // A draft's proof has already been checked against its document when loaded or awarded.
        var original=source.document();
        var proof=source.proof()!=null && original.id().equals(next.id()) && original.sameDrivingLayout(next)?source.proof():null;
        if(next.equals(document()) && Objects.equals(proof,current.proof())) return true;
        current=new Draft(next,proof);
        return true;
    }
    private void rememberEdit(Draft before) {
        undo.addLast(before);
        if(undo.size()>100) undo.removeFirst();
        redo.clear();
        historyRevision++;
    }
    private CircuitDocument.Point point(float x,float z) {
        requirePointer(x,z);
        return new CircuitDocument.Point(snap(x,240),snap(z,240));
    }
    private static float snap(float coordinate,float limit) {
        return Math.round(Math.max(-limit,Math.min(limit,coordinate))/2)*2;
    }
    private static void requirePointer(float x,float z) {
        if(!Float.isFinite(x) || !Float.isFinite(z)) throw new IllegalArgumentException("Invalid editor pointer");
    }
    private float square(float value) { return value*value; }
    private float itemDistance(float x,float z,CircuitScene.Item item) {
        float dx=x-item.x(),dz=z-item.z();
        if(!item.solid()) return square(Math.max(0,(float)Math.hypot(dx,dz)-3));
        var tangent=item.tangent();float along=dx*tangent.x+dz*tangent.z,across=dx*tangent.z-dz*tangent.x;
        return square(Math.max(0,Math.abs(along)-item.length()*.5f))+square(Math.max(0,Math.abs(across)-item.width()*.5f));
    }
    private int nearestSegment(CircuitDocument.Point point) {
        var points=document().anchors();if(points.size()<2) return points.size()-1;
        double best=Double.POSITIVE_INFINITY;int index=points.size()-1;
        for(int i=0;i<points.size();i++) { var a=points.get(i);var b=points.get((i+1)%points.size());double d=java.awt.geom.Line2D.ptSegDistSq(a.x(),a.z(),b.x(),b.z(),point.x(),point.z());if(d<best) { best=d;index=i; } }
        return index;
    }
}
