package com.f1car.model;

import java.util.*;
import java.util.function.UnaryOperator;

/** Pure document editing, with bounded reversible snapshots and a separate saved checkpoint. */
public final class LiveryEditor {
    public static final int HISTORY=24;
    public record State(CarLivery value,int selected) {
        public State {if(value==null || selected< -1 || selected>=value.layers().size() || selected==-1 && !value.layers().isEmpty()) throw new IllegalArgumentException("Invalid livery selection");}
    }
    public record Snapshot(State current,CarLivery saved,List<State> undo,List<State> redo) {
        public Snapshot {
            if(current==null || undo==null || redo==null || undo.size()+redo.size()>HISTORY) throw new IllegalArgumentException("Invalid livery history");
            undo=List.copyOf(undo);redo=List.copyOf(redo);
            var values=new ArrayList<State>(undo);values.addAll(redo);
            for(var state:values) if(!state.value().id().equals(current.value().id()) || !state.value().vehicle().equals(current.value().vehicle())) throw new IllegalArgumentException("Mixed livery history");
            if(saved!=null && (!saved.id().equals(current.value().id()) || !saved.vehicle().equals(current.value().vehicle()))) throw new IllegalArgumentException("Mixed saved checkpoint");
        }
    }
    private State state;private CarLivery saved;
    private long revision;
    private final Deque<State> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();
    public LiveryEditor(CarLivery value,boolean alreadySaved) {Objects.requireNonNull(value);state=new State(value,value.layers().isEmpty()?-1:0);saved=alreadySaved?value:null;}
    public CarLivery value() {return state.value;}
    public CarLivery saved() {return saved;}
    public long revision() {return revision;}
    public Snapshot snapshot() {return new Snapshot(state,saved,List.copyOf(undo),List.copyOf(redo));}
    public static LiveryEditor restore(Snapshot snapshot) {Objects.requireNonNull(snapshot);var editor=new LiveryEditor(snapshot.current().value(),false);editor.state=snapshot.current();editor.saved=snapshot.saved();editor.undo.addAll(snapshot.undo());editor.redo.addAll(snapshot.redo());return editor;}
    public boolean dirty() {return !value().equals(saved);}
    public int selected() {return state.selected;}
    public CarLivery.Layer layer() {return selected()<0?null:value().layers().get(selected());}
    public void select(int index) {if(index<0 || index>=value().layers().size()) throw new IllegalArgumentException("Invalid layer selection");if(index==selected()) return;state=new State(value(),index);revision++;}
    public int undoSize() {return undo.size();}
    public boolean canRedo() {return !redo.isEmpty();}
    private void change(CarLivery value,int index) {if(value.equals(value())) return;undo.addLast(state);while(undo.size()>HISTORY) undo.removeFirst();redo.clear();state=new State(value,index);revision++;}
    public void card(String title,String author,String notes) {change(value().card(title,author,notes),selected());}
    public void add(CarLivery.Shape shape) {if(value().layers().size()==CarLivery.CAPACITY) throw new IllegalArgumentException("最多十二层");var next=new ArrayList<>(value().layers());next.add(CarLivery.Layer.initial(shape));change(value().layers(next),next.size()-1);}
    public void update(UnaryOperator<CarLivery.Layer> operation) {if(layer()==null) return;var next=new ArrayList<>(value().layers());next.set(selected(),Objects.requireNonNull(operation.apply(layer())));change(value().layers(next),selected());}
    public void duplicate() {if(layer()==null) return;if(value().layers().size()==CarLivery.CAPACITY) throw new IllegalArgumentException("最多十二层");var next=new ArrayList<>(value().layers());next.add(selected()+1,layer());change(value().layers(next),selected()+1);}
    public void remove() {if(layer()==null) return;var next=new ArrayList<>(value().layers());next.remove(selected());change(value().layers(next),next.isEmpty()?-1:Math.min(selected(),next.size()-1));}
    public void reorder(int direction) {if(layer()==null || direction!=1 && direction!=-1) return;int target=selected()+direction;if(target<0 || target>=value().layers().size()) return;var next=new ArrayList<>(value().layers());Collections.swap(next,selected(),target);change(value().layers(next),target);}
    public void place(double x,double y) {if(!Double.isFinite(x) || !Double.isFinite(y)) throw new IllegalArgumentException("Invalid layer position");int px=(int)Math.round(Math.max(-400,Math.min(400,x))),py=(int)Math.round(Math.max(-400,Math.min(400,y)));update(layer->layer.position(px,py));}
    public void undo() {if(undo.isEmpty()) return;redo.addLast(state);state=undo.removeLast();revision++;}
    public void redo() {if(redo.isEmpty()) return;undo.addLast(state);state=redo.removeLast();revision++;}
    public boolean markSaved(CarLivery snapshot) {if(!value().equals(snapshot)) return false;if(!snapshot.equals(saved)) {saved=snapshot;revision++;}return true;}
}
