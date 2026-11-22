package com.f1car.ui;

import java.util.*;

/** Prewrapped full text for creator diagnostics and revision reviews; never ellipsizes content. */
final class ReviewPages {
    private static final int WIDTH=32,ROWS=5,MAX_TEXT=1_048_576;
    private final List<String> lines;
    private int page;
    ReviewPages(List<String> paragraphs) {
        var values=new ArrayList<String>();int total=0;
        for(String paragraph:paragraphs) {if(paragraph.length()>MAX_TEXT-total) throw new IllegalArgumentException("Review text exceeds bound");total+=paragraph.length();for(String line:paragraph.split("\n",-1)) {
            int start=0;while(start<line.length()) {int end=start,count=0;while(end<line.length() && count++<WIDTH) end+=Character.charCount(line.codePointAt(end));values.add(line.substring(start,end));start=end;}if(line.isEmpty()) values.add("");
        }}
        lines=values.isEmpty()?List.of(""):List.copyOf(values);
    }
    int page() {return page;}
    int pages() {return (lines.size()+ROWS-1)/ROWS;}
    void move(int delta) {page=(int)Math.max(0,Math.min(pages()-1,(long)page+delta));}
    void showRow(int index) {page=index/ROWS;}
    int firstIndex() {return page*ROWS;}
    List<String> visible() {return lines.subList(firstIndex(),Math.min(lines.size(),firstIndex()+ROWS));}
}
