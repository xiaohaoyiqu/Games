package com.f1car.model;

import java.util.OptionalInt;

/**
 * Integer editing is staged locally. Selecting a digit never changes the arrow-key step.
 * Small/large steps follow https://www.w3.org/WAI/ARIA/apg/patterns/spinbutton/ ;
 * decimal-place navigation is the player's explicit desktop keyboard preference.
 */
public final class DigitNumberEditor {
    public static final int MAX_TEXT=32,MAX_VALUE=999_999_999;
    private final int min,max,width;
    private String text;
    private int place;
    private boolean replace=true,positional;
    private String error="";
    public DigitNumberEditor(int value,int min,int max) {
        if(min<0 || max<min || max>MAX_VALUE || value<min || value>max) throw new IllegalArgumentException("Invalid numeric range");
        this.min=min;this.max=max;width=Math.max(3,Integer.toString(max).length());text=Integer.toString(value);
    }
    public int min() {return min;}
    public int max() {return max;}
    public int place() {return place;}
    public boolean positional() {return positional;}
    public boolean replacing() {return replace;}
    public String text() {return text;}
    public String error() {return error;}
    public String display() {return positional?"0".repeat(Math.max(0,width-text.length()))+text:text;}
    public OptionalInt value() {
        var parsed=rawValue();if(parsed.isEmpty()) return parsed;int value=parsed.getAsInt();return value>=min && value<=max?parsed:OptionalInt.empty();
    }
    private OptionalInt rawValue() {
        if(text.isEmpty() || text.length()>MAX_TEXT || !text.chars().allMatch(c->c>='0' && c<='9')) return OptionalInt.empty();
        try {int value=Integer.parseInt(text);return value<=MAX_VALUE?OptionalInt.of(value):OptionalInt.empty();}
        catch(NumberFormatException error) {return OptionalInt.empty();}
    }
    public boolean validate() {
        if(value().isEmpty()) {error="请输入 "+min+"–"+max+" 之间的整数";return false;}
        return error.isEmpty();
    }
    /** A rejected oversized paste is never truncated into an apparently valid number. */
    public void paste(String value) {
        if(value==null || value.length()>MAX_TEXT) {error="输入过长，请重新输入完整数值";return;}
        text=value;replace=false;positional=false;error="";
    }
    public void selectAll() {replace=true;positional=false;error="";}
    public void type(char digit) {
        if(digit<'0' || digit>'9') {error="只接受整数数字";return;}
        if(positional) {
            char[] digits=display().toCharArray();digits[digits.length-1-place]=digit;text=new String(digits);error="";return;
        }
        if(!replace && text.length()>=MAX_TEXT) {error="输入过长，请重新输入完整数值";return;}
        text=replace?String.valueOf(digit):text+digit;replace=false;positional=false;error="";
    }
    public void backspace() {
        if(replace) text="";
        else if(positional) {String shown=display();int index=shown.length()-1-place;text=shown.substring(0,index)+"0"+shown.substring(index+1);}
        else if(!text.isEmpty()) text=text.substring(0,text.length()-1);
        replace=false;error="";
    }
    /** Left selects a more significant digit, right selects a less significant digit. */
    public void move(int direction) {
        var parsed=rawValue();if(parsed.isEmpty() || Integer.toString(parsed.getAsInt()).length()>width) {validate();return;}
        text=Integer.toString(parsed.getAsInt());place=(int)Math.max(0,Math.min(width-1,(long)place+direction));positional=true;replace=false;error="";
    }
    public void selectPlace(int value) {
        if(value<0 || value>=width) throw new IllegalArgumentException("Invalid digit place");
        place=value;move(0);
    }
    public void step(int amount) {
        var value=value();if(value.isEmpty()) {validate();return;}
        text=Long.toString(Math.max(min,Math.min(max,(long)value.getAsInt()+amount)));replace=false;error="";
    }
    public void boundary(boolean maximum) {text=Integer.toString(maximum?max:min);replace=false;error="";}
}
