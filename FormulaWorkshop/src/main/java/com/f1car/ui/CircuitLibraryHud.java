package com.f1car.ui;

import com.f1car.FormulaGame;
import com.f1car.model.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.BiFunction;

/** Browsing is read-only; switching the active draft uses the existing save-before-leave rule. */
final class CircuitLibraryHud {
    private static final int PAGE_SIZE=4;
    private static final Color INK=new Color(235,245,240),MUTED=new Color(162,186,189),MINT=new Color(76,235,193),GOLD=new Color(245,215,132);
    private final Map<String,Card> cards=new LinkedHashMap<>(64,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,Card> entry) {return size()>64;}
    };
    private String selected="";
    private String query="";
    private String[] queryTerms=new String[0];
    private CircuitCard.Difficulty difficulty;
    private CircuitCard.Status filterStatus;
    private int page;
    private final CircuitCardPreview.Cached[] rowPreviews=new CircuitCardPreview.Cached[PAGE_SIZE];
    private final CircuitCardPreview.Cached detailPreview=new CircuitCardPreview.Cached();
    private boolean previewsUsed;
    private final CircuitShowcaseHud showcase=new CircuitShowcaseHud();

    CircuitLibraryHud() {Arrays.setAll(rowPreviews,index->new CircuitCardPreview.Cached());}

    static final class Card {
        final CircuitDocument document;
        private CircuitTrial.Proof proof;
        private CircuitInspector.Report savedReport;
        private CircuitCard.Status status;
        private String statusPhysics,searchText;

        Card(CircuitDocument document) {this.document=document;}

        void proof(CircuitTrial.Proof proof) {
            if(this.proof!=proof) {
                this.proof=proof;
                status=null;
            }
        }

        CircuitInspector.Report report(CircuitEditor editor) {
            if(editor!=null && editor.document()==document) return editor.inspection();
            if(savedReport==null) savedReport=CircuitInspector.inspect(document);
            return savedReport;
        }

        CircuitCard.Status status(CircuitEditor editor,String physics) {
            if(status==null || !physics.equals(statusPhysics)) {
                status=CircuitCard.status(document,proof,physics,report(editor));
                statusPhysics=physics;
            }
            return status;
        }

        boolean contains(String[] terms) {
            if(terms.length==0) return true;
            if(searchText==null) {
                var metadata=document.metadata();
                searchText=fold(document.name()+" "+metadata.author()+" "+metadata.description()+" "+metadata.revision());
            }
            for(String term:terms) if(!searchText.contains(term)) return false;
            return true;
        }
    }

    boolean showcaseOpen() {return showcase.showing();}
    boolean closeShowcase() {return showcase.close();}
    void clearPreviews() {
        if(!previewsUsed) return;
        for(var preview:rowPreviews) preview.clear();
        detailPreview.clear();
        previewsUsed=false;
    }
    void changedDraft() { selected="";page=0;showcase.close(); }
    private void newDraft(FormulaGame app) { app.newCircuit(null);search("");difficulty=null;filterStatus=null;selected=app.circuitEditor()==null?"":app.circuitEditor().document().id(); }
    void render(HudRenderer h,Graphics2D g,FormulaGame app,Runnable close) {
        if(showcase.showing()) {showcase.render(h,g,app);return;}
        var editor=app.circuitEditor();
        var all=entries(app,editor);
        var physics=app.vehicleSpec.physicsVersion;
        var entries=all.stream().filter(e->matches(e,editor,physics)).toList();
        int pages=Math.max(1,(entries.size()+PAGE_SIZE-1)/PAGE_SIZE);page=Math.max(0,Math.min(page,pages-1));
        h.smallButton(44,126,300,34,"搜索："+(query.isEmpty()?"名称／作者／说明":shorten(query,15)),()->h.prompt("搜索地图名称、作者、简介或修订",query,80,this::search));
        h.smallButton(354,126,200,34,"难度："+(difficulty==null?"全部":difficulty.title),()->{difficulty=next(CircuitCard.Difficulty.values(),difficulty);page=0;});
        h.smallButton(564,126,300,34,"状态："+(filterStatus==null?"全部":filterStatus.title),()->{filterStatus=next(CircuitCard.Status.values(),filterStatus);page=0;});
        h.smallButton(876,126,168,34,"清除筛选",()->{search("");difficulty=null;filterStatus=null;});
        h.text(entries.size()+" / "+all.size()+" 张",1064,151,16,MUTED);
        h.panel(44,174,380,500,new Color(24,49,60));h.panel(444,174,788,500,new Color(24,49,60));
        h.smallButton(444,698,180,34,"新建草稿",()->newDraft(app));
        if(entries.isEmpty()) {
            clearPreviews();
            selected="";
            h.text(all.isEmpty()?"没有草稿":"没有符合条件的地图",464,225,24,INK,true);
            h.wrapped(all.isEmpty()?"点击下方新建草稿，或返回空画布导入／复制内置地图。":"可修改搜索词或清除筛选。筛选不会删除草稿，也不会切换当前编辑地图。",464,274,710,17,MUTED,28);
            return;
        }
        previewsUsed=true;
        int first=page*PAGE_SIZE,end=Math.min(entries.size(),first+PAGE_SIZE);
        var selectedCard=entries.get(first);
        for(int i=first;i<end;i++) if(entries.get(i).document.id().equals(selected)) {
            selectedCard=entries.get(i);
            break;
        }
        selected=selectedCard.document.id();
        for(int i=first;i<end;i++) {
            var entry=entries.get(i);var document=entry.document;int row=i-first,y=188+row*116;var report=entry.report(editor);var status=entry.status(editor,physics);
            h.smallButton(58,y,352,104,"",()->selected=document.id());rowPreviews[row].draw(g,document,report,new Rectangle(68,y+12,96,76));
            h.text((selected.equals(document.id())?"● ":"")+shorten(document.name(),12),178,y+28,16,INK,true);
            h.text(status.title,178,y+54,13,status==CircuitCard.Status.QUALIFIED?MINT:GOLD);
            h.text(shorten(document.metadata().author().isBlank()?"未署名":document.metadata().author(),14),178,y+78,13,MUTED);
        }
        var document=selectedCard.document;var card=document.metadata();var report=selectedCard.report(editor);
        var status=selectedCard.status(editor,physics);boolean current=editor!=null && document==editor.document();
        h.text(shorten(document.name(),28),464,211,24,INK,true);h.text(status.title+(current && editor.dirty()?" · 未保存":""),464,241,16,status==CircuitCard.Status.QUALIFIED?MINT:GOLD);
        detailPreview.draw(g,document,report,new Rectangle(464,258,230,180));
        h.smallButton(714,258,498,34,"作者："+shorten(card.author().isBlank()?"未署名":card.author(),23),()->edit(h,app,document,"作者署名（最多 40 字）",card.author(),40,CircuitCard::author));
        h.smallButton(714,303,498,34,"难度："+card.difficulty().title+"（作者标注，点击切换）",()->{
            if(!editable(app,document.id())) return;var value=app.circuitEditor().document().metadata();var levels=CircuitCard.Difficulty.values();app.circuitEditor().card(value.difficulty(levels[(value.difficulty().ordinal()+1)%levels.length]));
        });
        h.smallButton(714,348,498,34,"简介："+shorten(card.description().isBlank()?"点击填写":card.description(),25),()->edit(h,app,document,"地图简介（最多 240 字，单行）",card.description(),240,CircuitCard::description));
        h.smallButton(714,393,498,34,"修订："+shorten(card.revision().isBlank()?"点击填写":card.revision(),25),()->edit(h,app,document,"修订备注（最多 120 字，单行）",card.revision(),120,CircuitCard::revision));
        h.text(report.track()==null?document.anchors().size()+" 个路线点 · 尚不能构造路线":Math.round(report.track().length)+" m · 路宽 "+Math.round(document.width())+" m",464,465,15,MINT);
        h.wrapped(shorten(card.description().isBlank()?"暂无简介。可说明这条路线的弯型、适合的驾驶目标和需要留意的位置。":card.description(),110),464,495,738,16,INK,24);
        h.wrapped("修订备注："+shorten(card.revision().isBlank()?"未填写":card.revision(),70),464,571,738,14,MUTED,20);
        h.smallButton(464,623,230,34,"打开并编辑路线",()->{if(open(app,document.id())) close.run();});
        h.smallButton(714,623,238,34,"载入并编辑地图卡",()->open(app,document.id()));
        h.smallButton(972,623,240,34,"保存当前地图卡",()->{if(editable(app,document.id())) app.saveCircuitDraft();});
        h.smallButton(714,440,498,30,"地图封面与完整作品说明",()->showcase.open(document.id()));
        h.smallButton(636,698,180,34,"修改名称",()->{if(editable(app,document.id())) h.editText("地图名称",app.circuitEditor().document().name(),"我的环线",60,value->{try { if(editable(app,document.id())) app.circuitEditor().rename(value); }catch(IllegalArgumentException error) { app.notifyUser("名称不能为空且最多 60 字符。"); }});});
        h.smallButton(828,698,180,34,"恢复地图卡默认",()->h.confirm("恢复地图卡默认？","清空作者、简介和修订备注，难度恢复未标注。\n路线与地图名称保留，修改可撤销。",()->{if(editable(app,document.id())) app.circuitEditor().card(CircuitCard.EMPTY);}));
        h.smallButton(1020,698,212,34,"删除选中草稿",()->h.confirm("删除这张草稿？",document.name()+"\n删除地图及其版本、试跑记录。\n正在编辑此图时会丢弃未保存修改。",()->app.deleteCircuitDraft(document.id())));
        h.smallButton(58,698,110,34,"上一页",()->page=Math.max(0,page-1));h.text((page+1)+" / "+pages+" · "+entries.size()+" 张",182,722,15,MUTED);h.smallButton(300,698,110,34,"下一页",()->page=Math.min(pages-1,page+1));
        h.text("Esc 返回画布 · Ctrl+S 保存 · Z/Y 撤销重做 · 点击修改其他卡片时会先保存当前草稿，再载入所选地图",44,752,13,MUTED);
    }
    private List<Card> entries(FormulaGame app,CircuitEditor editor) {
        var entries=new ArrayList<Card>();
        if(editor!=null) entries.add(card(editor.document(),editor.proof()));
        for(var saved:app.circuits.all()) {
            if(editor==null || !saved.document().id().equals(editor.document().id())) entries.add(card(saved.document(),saved.proof()));
        }
        return entries;
    }
    Card card(CircuitDocument document,CircuitTrial.Proof proof) {
        var card=cards.get(document.id());
        if(card==null || card.document!=document) {
            card=new Card(document);
            cards.put(document.id(),card);
        }
        card.proof(proof);
        return card;
    }
    private boolean matches(Card card,CircuitEditor editor,String physics) {
        return (difficulty==null || card.document.metadata().difficulty()==difficulty)
                && card.contains(queryTerms) && (filterStatus==null || card.status(editor,physics)==filterStatus);
    }
    private void search(String value) {query=value.strip();queryTerms=query.isEmpty()?new String[0]:fold(query).split("\\s+");page=0;}
    private static String fold(String value) { return java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT); }
    private static <T> T next(T[] values,T current) {
        if(current==null) return values[0];
        for(int i=0;i<values.length-1;i++) if(values[i]==current) return values[i+1];
        return null;
    }
    private boolean open(FormulaGame app,String id) { return app.circuitEditor()!=null && id.equals(app.circuitEditor().document().id()) || app.editCircuitDraft(id); }
    private boolean editable(FormulaGame app,String id) {
        return open(app,id);
    }
    private void edit(HudRenderer h,FormulaGame app,CircuitDocument document,String title,String value,int limit,BiFunction<CircuitCard,String,CircuitCard> setter) {
        if(!editable(app,document.id())) return;
        h.editText(title,value,"",limit,text->{if(!editable(app,document.id())) return;try { app.circuitEditor().card(setter.apply(app.circuitEditor().document().metadata(),text)); }catch(IllegalArgumentException error) { app.notifyUser("文字超出长度或含换行等控制字符，请检查地图卡要求。"); }});
    }
    private static String shorten(String value,int limit) { return value.codePointCount(0,value.length())<=limit?value:value.substring(0,value.offsetByCodePoints(0,limit-1))+"…"; }
}
